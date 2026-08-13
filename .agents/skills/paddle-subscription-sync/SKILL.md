---
name: paddle-subscription-sync
description: Mirror Paddle subscription and customer state into your database via webhooks — schema, upsert pattern, status semantics, scheduled changes, and access gating.
---

# Sync Paddle subscription state into your database

## When to use this skill

Use this skill when you need a local copy of Paddle subscription and customer state — typically to gate features by subscription status, render account pages without round-tripping to the Paddle API, and report on revenue. This skill defines the table schema, the events to subscribe to, the upsert pattern, how to map your app's users to Paddle customers, status semantics, and how to handle scheduled changes (pauses, cancels effective at end of period).

This skill assumes you already receive verified webhooks. If you don't, do `webhooks` first — that skill covers the route handler and signature verification. The code here lives inside the event handler functions that skill creates.

## Prerequisites

- Working webhook endpoint with signature verification (`webhooks`).
- A database (the examples use Supabase, but any SQL store with `UPSERT` works).
- Subscribed events on your notification destination:
  - `customer.created`, `customer.updated`
  - `subscription.created`, `subscription.updated`, `subscription.canceled`
  - Optionally: `transaction.completed` (for one-off purchases), `subscription.activated` (when a trial converts)

## Why mirror state at all?

You could call the Paddle API every time you need to know "is this user on the Pro plan?" — but that's slow compared to checking your own DB.

Mirroring means: webhooks are the source of truth that updates your DB; your app reads its own DB; the API is reserved for write actions (cancel, update, refund) where you need an immediate response.

## Schema

The minimum viable schema, in SQL:

```sql
-- Maps a Paddle customer to your app user (by email is the easiest bridge).
CREATE TABLE customers (
  customer_id TEXT PRIMARY KEY,        -- "ctm_01h..."
  email TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX customers_email_idx ON customers(email);

CREATE TABLE subscriptions (
  subscription_id TEXT PRIMARY KEY,    -- "sub_01h..."
  customer_id TEXT NOT NULL REFERENCES customers(customer_id),
  subscription_status TEXT NOT NULL,   -- see "Status semantics" below
  price_id TEXT NOT NULL,
  product_id TEXT NOT NULL,
  scheduled_change TIMESTAMP,          -- non-null when a pause or cancel is pending
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX subscriptions_customer_id_idx ON subscriptions(customer_id);
CREATE INDEX subscriptions_status_idx ON subscriptions(subscription_status);
```

This is intentionally lean. Add columns as you need them (e.g. `currency_code`, `next_billed_at`, `discount_id`, `items` as JSONB for multi-item subscriptions). The Paddle data is much richer; only mirror what your UI actually queries.

## Map app users to Paddle customers

The cleanest bridge is **email**:

- Your app's user table has an email.
- Paddle's customer record has an email.
- Match on email when the user lands on a billing page.

The `customers.email` mirror lets you look up the Paddle `customer_id` from your authenticated user without an API call:

```ts
// utils/paddle/get-customer-id.ts
import { createClient } from "@/utils/supabase/server";

export async function getCustomerId(): Promise<string> {
  const supabase = await createClient();
  const { data: userData } = await supabase.auth.getUser();
  const email = userData?.user?.email;
  if (!email) return "";

  const { data } = await supabase
    .from("customers")
    .select("customer_id")
    .eq("email", email)
    .single();

  return data?.customer_id ?? "";
}
```

If a user signs up in your app and **then** checks out (no Paddle customer yet), `getCustomerId` returns `''`. After their first checkout, the `customer.created` webhook fires, your handler upserts the row, and subsequent calls find them.

## Process subscription events

Inside the route handler from `webhooks`, route to a typed handler:

```ts
// utils/paddle/process-webhook.ts
import {
  EventName,
  type EventEntity,
  type SubscriptionCreatedEvent,
  type SubscriptionUpdatedEvent,
  type SubscriptionCanceledEvent,
  type CustomerCreatedEvent,
  type CustomerUpdatedEvent,
} from "@paddle/paddle-node-sdk";
import { createClient } from "@/utils/supabase/server-internal";

type SubscriptionEvent =
  | SubscriptionCreatedEvent
  | SubscriptionUpdatedEvent
  | SubscriptionCanceledEvent;

export async function processEvent(event: EventEntity) {
  switch (event.eventType) {
    case EventName.SubscriptionCreated:
    case EventName.SubscriptionUpdated:
    case EventName.SubscriptionCanceled:
      return upsertSubscription(event);
    case EventName.CustomerCreated:
    case EventName.CustomerUpdated:
      return upsertCustomer(event);
  }
}

async function upsertSubscription(event: SubscriptionEvent) {
  const supabase = await createClient();
  const sub = event.data;

  const { error } = await supabase.from("subscriptions").upsert({
    subscription_id: sub.id,
    customer_id: sub.customerId,
    subscription_status: sub.status,
    price_id: sub.items[0]?.price?.id ?? "",
    product_id: sub.items[0]?.price?.productId ?? "",
    scheduled_change: sub.scheduledChange?.effectiveAt ?? null,
    updated_at: new Date().toISOString(),
  });

  if (error) throw error;
}

async function upsertCustomer(
  event: CustomerCreatedEvent | CustomerUpdatedEvent,
) {
  const supabase = await createClient();
  const { error } = await supabase.from("customers").upsert({
    customer_id: event.data.id,
    email: event.data.email,
    updated_at: new Date().toISOString(),
  });
  if (error) throw error;
}
```

A few notes:

- The webhook handler must use a Supabase client with the **service role key**, not the anon key — webhooks don't run as a logged-in user, and your RLS policies will block writes from anon. Keep the service-role client in a separate file (e.g. `server-internal.ts`) so it isn't accidentally used in user-facing code.
- `UPSERT` keyed on `subscription_id` makes the handler idempotent for state mirrors. Repeated deliveries converge on the latest state.
- The `items` array can have multiple line items for hybrid subscriptions. The example only stores the first; for multi-item subscriptions, store the whole array as JSONB.

## Status semantics

`subscription.status` is the field your access-gating logic should read. Values:

| Status     | Meaning                                                   | Should the user have access?      |
| ---------- | --------------------------------------------------------- | --------------------------------- |
| `active`   | Paying, current.                                          | Yes                               |
| `trialing` | In a free trial period.                                   | Yes                               |
| `past_due` | Most recent invoice failed; Paddle is retrying (dunning). | Usually yes — a few days of grace |
| `paused`   | Subscription is paused; no billing, no service.           | No (typically)                    |
| `canceled` | Cancellation has taken effect.                            | No                                |

`canceled` is the **terminal state** — it means the subscription has actually ended. If a user cancels but their billing period hasn't run out yet, the status stays `active` and `scheduled_change` is set. The status only flips to `canceled` when that scheduled date arrives.

A reasonable access gate:

```ts
function hasActiveSubscription(sub: Subscription | null): boolean {
  if (!sub) return false;
  return (
    sub.subscription_status === "active" ||
    sub.subscription_status === "trialing"
  );
}
```

We recommend granting access during `past_due`, but showing a banner to the user to update their payment method.Paddle Retain automatically retries payment for you.

## Handling scheduled changes

When a user cancels mid-period, or schedules a pause, Paddle sends a `subscription.updated` event with:

```json
{
  "data": {
    "id": "sub_01h...",
    "status": "active",
    "scheduled_change": {
      "action": "cancel",
      "effective_at": "2026-05-12T00:00:00Z"
    }
  }
}
```

The status is still `active` — they keep service until `effective_at`. Then Paddle sends a second event flipping status to `canceled` (or `paused`) and clearing `scheduled_change`.

In your UI, a non-null `scheduled_change` is the signal to show "Your subscription will cancel on May 12" instead of "Subscribe". Here's a typical pattern:

```ts
function getSubscriptionUiState(sub: Subscription) {
  if (!sub) return "no-subscription";
  if (sub.scheduled_change) {
    return sub.subscription_status === "paused"
      ? "pause-scheduled"
      : "cancel-scheduled";
  }
  return sub.subscription_status; // 'active' | 'trialing' | 'paused' | 'canceled' | 'past_due'
}
```

## Querying mirrored state

Once you mirror, you can read everything from your DB without API calls:

```ts
// utils/db/get-user-subscription.ts
"use server";

import { createClient } from "@/utils/supabase/server";
import { getCustomerId } from "@/utils/paddle/get-customer-id";

export async function getUserSubscription() {
  const customerId = await getCustomerId();
  if (!customerId) return null;

  const supabase = await createClient();
  const { data } = await supabase
    .from("subscriptions")
    .select("*")
    .eq("customer_id", customerId)
    .order("created_at", { ascending: false })
    .limit(1)
    .single();

  return data;
}
```

Use the Paddle API only for **mutations**: cancel, pause, resume, upgrade, refund. Reads should hit your DB.

## Initial backfill

Adding subscription sync to an existing app (with existing Paddle data) means you need to backfill before relying on the mirror.

> The Paddle MCP exposes three tools per server (`search`, `execute`, `report_missing_tool`). Workflow: call `search` to confirm the exact method name and parameter shapes, then call `execute` with an async function that calls `client.<resource>.<operation>(...)`. **Method paths are camelCase** (`client.clientTokens.create`, `client.pricingPreview.preview`). **Body params and response fields are snake_case** (`tax_category`, `product_id`, `unit_price`, `currency_code`). Pagination is `{ pagination: { hasMore }, data: [...] }` with `{ after: "<last_id>" }` — not `.next()` / `.hasMore`. Chain multi-step workflows inside one `execute`; variables don't persist between calls. Hard caps: 50 API calls per execute, 30s timeout, 32KB code.

If a Paddle MCP server is available to you, paginate explicitly inside one `execute` call:

```js
async (client) => {
  const customers = [];
  let after;
  do {
    const page = await client.customers.list({ after, per_page: 200 });
    customers.push(...page.data);
    after = page.pagination.hasMore ? page.data.at(-1).id : undefined;
  } while (after);
  return customers;
};
```

**The 50-call cap matters here.** With `per_page: 200` that's a ceiling of ~10,000 customers per `execute` invocation. If the user's account fits in one execute, this is fine. If not, split across multiple `execute` calls — pass the last seen ID back in as the starting `after` cursor each time — or fall back to the SDK script below, which has no such cap.

Otherwise (or for larger backfills), use a one-shot SDK script:

```ts
// scripts/backfill-paddle.ts
import { getPaddleInstance } from "@/utils/paddle/get-paddle-instance";
import { db } from "@/lib/db";

async function main() {
  const paddle = getPaddleInstance();
  const customers = paddle.customers.list();
  for await (const customer of customers) {
    await db.customers.upsert({
      where: { customer_id: customer.id },
      create: { customer_id: customer.id, email: customer.email },
      update: { email: customer.email },
    });
  }

  const subs = paddle.subscriptions.list();
  for await (const sub of subs) {
    await db.subscriptions.upsert({
      where: { subscription_id: sub.id },
      create: {
        subscription_id: sub.id,
        customer_id: sub.customerId,
        subscription_status: sub.status,
        price_id: sub.items[0]?.price?.id ?? "",
        product_id: sub.items[0]?.price?.productId ?? "",
        scheduled_change: sub.scheduledChange?.effectiveAt ?? null,
      },
      update: {
        /* same fields */
      },
    });
  }
}
main().catch(console.error);
```

Run it once before flipping access gating to read from your DB.

## Common pitfalls

- **Using a Supabase anon key in the webhook handler.** RLS policies block the writes. Use the service role key in a server-internal client.
- **Mapping users by `customer_id` before checkout.** A new app user has no Paddle customer until they complete a checkout. Match on email when the row first appears.
- **Treating `canceled` as "just hit cancel".** It's the terminal state. The "user pressed cancel" event is `subscription.updated` with a non-null `scheduled_change.action: 'cancel'`, unless you allow users to cancel immediately. Don't revoke access until status flips to `canceled`.
- **Reading status from a stale row.** If your handler is queued (which it should be for slow ops), the DB row is updated _after_ the queue worker runs. Latency between webhook receipt and row update is typically sub-second, but if you need read-after-write within the route handler itself, do the upsert synchronously.
- **Storing only one item from `subscription.items`.** If you sell hybrid plans (a base subscription + addons), store the whole array. Otherwise the UI shows the first item only.
- **No backfill before flipping reads to your DB.** Existing customers will look like "no subscription" until their next webhook — which might be when they cancel. Always backfill first.
- **Forgetting `customer.updated`.** If a customer changes their email in Paddle, you won't be able to look them up via `getCustomerId` until you sync the new email. Subscribe to `customer.updated` from day one.
- **Out-of-order events.** `subscription.updated` can occasionally arrive before its `subscription.created` if Paddle retries the first one. UPSERT handles this automatically (the second event also creates the row if missing). Don't rely on event ordering — rely on convergent state.

## Verify the integration

1. Create a sandbox subscription via your checkout flow.
2. Check your `customers` and `subscriptions` tables — both should have the new rows within a second of `checkout.completed`.
3. Cancel the subscription via your customer portal.
4. Confirm:
   - The `subscriptions` row shows `subscription_status: 'active'` with `scheduled_change` set.
   - In the dashboard, advance the clock or wait for the period — the status flips to `canceled`.
5. Use the simulator (see `sandbox-testing`) to fire `subscription.updated` with a `paused` status and confirm your UI handles it.

## Related docs

- [Provision access via webhooks](https://developer.paddle.com/build/subscriptions/provision-access-webhooks.md)
- [Cancel subscriptions](https://developer.paddle.com/build/subscriptions/cancel-subscriptions.md)
- [Pause subscriptions](https://developer.paddle.com/build/subscriptions/pause-subscriptions.md)
- Reference implementation: [paddle-nextjs-starter-kit](https://github.com/PaddleHQ/paddle-nextjs-starter-kit) — see `src/utils/paddle/process-webhook.ts` and `src/utils/paddle/get-customer-id.ts`.
