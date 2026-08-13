---
name: paddle-subscription-cancel
description: Cancel a Paddle subscription from a Next.js Server Action — auth, ownership check, safe `effectiveFrom` default, revalidation, and the `canceled` vs `scheduledChange` distinction.
---

# Cancel a Paddle subscription from Next.js

## When to use this skill

Use this skill when building the "Cancel subscription" button (or equivalent) on the authenticated user's billing or account page. It covers a Next.js 15 (App Router) Server Action that calls `paddle.subscriptions.cancel()` with the right options, the security checks every cancel action needs, what to do after the cancel succeeds, and the relationship between Paddle's `canceled` status and a `scheduledChange` block.

This is the _initiating_ side of subscription cancellation. Pair it with:

- `subscription-sync` for the _receiving_ side — your webhook handler will get a `subscription.updated` event reflecting the schedule change, then a `subscription.canceled` event when the period actually ends.
- `webhooks` if you haven't set up the webhook endpoint yet.

## Prerequisites

- A working Paddle account with at least one active subscription (sandbox is fine).
- Server-side `PADDLE_API_KEY` available — this action runs in a Server Action, never in the browser.
- A `customers` table and a `subscriptions` table mirrored from webhooks (see `subscription-sync`). You need to know which Paddle customer the authenticated user is, and which subscription they're trying to cancel.
- An auth system. The examples use Supabase, but any session-based auth works.

```bash
NEXT_PUBLIC_PADDLE_ENV=sandbox             # or "production"
PADDLE_API_KEY=pdl_sdbx_apikey_...

NEXT_PUBLIC_SUPABASE_URL=...
NEXT_PUBLIC_SUPABASE_ANON_KEY=...
SUPABASE_SERVICE_ROLE_KEY=...              # or SUPABASE_SECRET_KEY (new opaque sb_secret_*)
```

## How `paddle.subscriptions.cancel` works

```ts
const subscription = await paddle.subscriptions.cancel(subscriptionId, {
  effectiveFrom: "next_billing_period", // or "immediately"
});
```

`effectiveFrom` controls _when_ the cancellation takes effect:

| Value                 | Meaning                                                                                                                                                      | When to use                                                                                          |
| --------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------- |
| `next_billing_period` | Cancellation is **scheduled** for the end of the current billing period. The status stays `active` until then; `scheduledChange` is set on the subscription. | Default for a generic "Cancel subscription" button — the user paid for the period, let them keep it. |
| `immediately`         | Cancellation takes effect **right now**. Paddle prorates a refund for the unused portion.                                                                    | A different, much rarer flow ("Cancel and refund"). Surprises users if used as the default.          |

If you only want the user to be able to cancel at the end of the period (the typical UX), pass `'next_billing_period'` and don't expose `'immediately'` at all.

## The full Server Action

```ts
// src/actions/subscription.ts
"use server";

import { revalidatePath } from "next/cache";
import { getPaddleInstance } from "@/utils/paddle/get-paddle-instance";
import { createServerInternalClient } from "@/utils/supabase/server-internal";
import { createServerClient } from "@/utils/supabase/server";

export async function cancelSubscription(subscriptionId: string) {
  // 1. Authenticate. Reject anonymous requests before any DB query or SDK call.
  const supabase = await createServerClient();
  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user || !user.email) {
    return { error: "Not authenticated" };
  }

  // 2. Verify the authenticated user owns this subscriptionId. The check
  //    bridges through the customers table (matched on the user's email)
  //    and the subscriptions table (matched on customer_id).
  const internal = createServerInternalClient();

  const { data: customerRow } = await internal
    .from("customers")
    .select("customer_id")
    .eq("email", user.email)
    .single();
  if (!customerRow) return { error: "No Paddle customer" };

  const { data: subRow } = await internal
    .from("subscriptions")
    .select("customer_id")
    .eq("subscription_id", subscriptionId)
    .single();
  if (!subRow || subRow.customer_id !== customerRow.customer_id) {
    return { error: "Forbidden" };
  }

  // 3. Cancel via the Paddle Node SDK. Schedule for end-of-period (the safe
  //    default for a generic Cancel button — the user keeps service through
  //    what they've already paid for).
  const paddle = getPaddleInstance();
  const subscription = await paddle.subscriptions.cancel(subscriptionId, {
    effectiveFrom: "next_billing_period",
  });

  // 4. Refresh any cached UI that depends on subscription state.
  revalidatePath("/dashboard/subscriptions");

  // 5. Return a slim DTO. Don't return the raw Subscription object —
  //    it's large and leaks Paddle internal fields.
  return {
    success: true,
    status: subscription.status,
    scheduledChange: subscription.scheduledChange?.effectiveAt ?? null,
  };
}
```

## What the user sees vs what the database stores

A successful cancel does **not** flip the subscription's `status` to `canceled` immediately. Two events fire over time:

1. **Right now (synchronous):** the SDK call returns. Paddle stores `status: 'active'` with `scheduledChange.action: 'cancel'` and `scheduledChange.effectiveAt: '<period-end-date>'`.
2. **Right now (asynchronous):** Paddle fires a `subscription.updated` webhook with the same payload. Your `subscription-sync` handler upserts the row. The mirrored row now has `subscription_status: 'active'` and `scheduled_change: <date>`.
3. **At the end of the period:** Paddle fires a second `subscription.updated` (or `subscription.canceled`) webhook. The mirrored row's `subscription_status` becomes `'canceled'` and `scheduled_change` clears.

Your access-gating logic should read from the _mirrored_ row, not from anything you compute optimistically in this action. The user keeps access until step 3 actually happens.

## Common pitfalls

- **No ownership check.** Trusting the `subscriptionId` from the action input lets any authenticated user cancel any subscription whose ID they can guess or scrape. The check has to compare the authenticated user's `customer_id` against the subscription's `customer_id`.
- **No auth check.** Even worse than the previous one — anyone (including unauthenticated requests) could cancel any subscription. Server Actions can be invoked from anywhere; never assume "no UI = no access."
- **Defaulting to `effectiveFrom: 'immediately'`.** The user pressed "Cancel," not "Cancel right now." Immediate cancellation removes their access right away. Use `'next_billing_period'` unless your UX explicitly asks the user "do you want to cancel immediately?"
- **Treating the SDK's return as the new permanent state.** The cancel call returns the _current_ subscription with the schedule attached. The status is still `active`. If your UI shows "Subscription status: {return.status}" it'll say `active`, which is correct — but if you optimistically show "Canceled," the user will see contradictory information when the page refreshes from the mirror.
- **Missing `revalidatePath`.** Server Components reading from your DB mirror won't re-fetch on their own. After the cancel succeeds, call `revalidatePath` (or `revalidateTag`) for the affected route(s). Without it, the user clicks Cancel and the UI keeps showing "active" until they hit refresh.
- **Returning the raw `Subscription`.** The SDK's `Subscription` instance is ~30 fields including internal Paddle metadata. The client only needs to know "did it work" and maybe "when does it actually end." Slim it to a DTO.
- **Confusing `canceled` and `scheduledChange.action: 'cancel'`.** `canceled` is the _terminal_ state, set after the period ends. `scheduledChange.action: 'cancel'` is the "user pressed cancel, ending soon" state. Access-gating should only revoke on the former, not the latter.
- **No customer record yet.** If a brand-new user (no checkout completed) somehow triggers the cancel action, there's no `customer_id` to look up. Handle the `customerRow == null` case explicitly — return an error rather than letting `null` flow into the comparison.

## Verify the integration

1. Sign in to your dev app and complete a sandbox checkout so you have a real subscription.
2. Wait for the `subscription.created` webhook to populate your `subscriptions` table.
3. Click Cancel in your UI. The server action should:
   - Return `{ success: true, status: 'active', scheduledChange: '<some date>' }`.
   - Trigger a `revalidatePath` so the UI refreshes.
4. In the Paddle dashboard, confirm the subscription shows "Scheduled to cancel" with the expected date.
5. Try the action while logged out (or with a forged `subscriptionId` belonging to a different customer). Confirm you get an error and `paddle.subscriptions.cancel` was NOT called.
6. Once the period ends, confirm the second `subscription.updated` event flips your mirrored row to `subscription_status: 'canceled'`.

## Related docs

- [Cancel a subscription](https://developer.paddle.com/build/subscriptions/cancel-subscriptions.md)
- [Cancel a subscription - API](https://developer.paddle.com/api-reference/subscriptions/cancel-subscription.md)
- Reference implementation: [paddle-nextjs-starter-kit](https://github.com/PaddleHQ/paddle-nextjs-starter-kit) — see `src/app/dashboard/subscriptions/actions.ts`.
