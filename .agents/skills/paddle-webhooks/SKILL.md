---
name: paddle-webhooks
description: Receive and verify Paddle webhooks in a Next.js Route Handler — signature verification, idempotency, retry semantics, and local testing.
---

# Receive Paddle webhooks securely in Next.js

## When to use this skill

Use this skill when building the server-side endpoint that receives Paddle webhook events (subscription changes, transactions, customer updates, payouts, etc.). It covers creating the notification destination in Paddle, writing a Next.js Route Handler that verifies signatures and processes events, handling retries and idempotency, and local testing.

This skill is the foundation for almost every server-side Paddle integration. Pair with:

- `subscription-sync` — turn webhook events into rows in your database.
- `sandbox-testing` — drive your endpoint with the webhook simulator.
- `checkout-web` — the client-side counterpart that triggers the events you'll receive here.

## The delivery contract

Every design choice in this skill follows from these facts about how Paddle delivers webhooks. Read this section first; everything else is mechanics.

- **Only `2xx` within 5 seconds is "delivered."** Any other response — `400`, `401`, `500`, `503`, a redirect, a connection timeout — is treated as a failed delivery and gets retried. There is no status code that means "stop retrying" on the integrator side. Paddle retries any non-2xx response.
- **Retry schedule.** Sandbox: 3 attempts over ~15 minutes. Live: 60 attempts over ~3 days, exponential backoff (~60s × `attempt^1.1`). Connection timeouts count toward the same budget as non-2xx responses.
- **Same `event.eventId` on every retry.** Paddle re-sends the identical payload (modulo a fresh signature timestamp) until you 2xx or the retry budget is exhausted. That id is your dedup key.
- **No redirect following.** A `301` or `302` is treated as a failed delivery, not followed.
- **After the retry budget is exhausted, the event is gone.** You can replay manually from the dashboard's notification log, or via the Paddle MCP server with `client.notifications.replay(notificationId)` inside an `execute` call (note: `notificationId` is a positional path param, not a body field). Plan for this when bringing an endpoint back from extended downtime — query the API for current state rather than waiting for events.

> The Paddle MCP exposes three tools per server (`search`, `execute`, `report_missing_tool`). Workflow: call `search` to confirm the exact method name and parameter shapes, then call `execute` with an async function that calls `client.<resource>.<operation>(...)`. **Method paths are camelCase** (`client.clientTokens.create`, `client.pricingPreview.preview`). **Body params and response fields are snake_case** (`tax_category`, `product_id`, `unit_price`, `currency_code`). Pagination is `{ pagination: { hasMore }, data: [...] }` with `{ after: "<last_id>" }` — not `.next()` / `.hasMore`. Chain multi-step workflows inside one `execute`; variables don't persist between calls. Hard caps: 50 API calls per execute, 30s timeout, 32KB code.

## Prerequisites

You need:

- A Paddle account with a configured product and price (sandbox is fine for development) — see `catalog-setup` if you don't have one yet.
- A publicly reachable URL for your dev environment if you want real Paddle events to hit local code (see "Local testing" below).
- Three environment variables:

```bash
NEXT_PUBLIC_PADDLE_ENV=sandbox             # or "production"
PADDLE_API_KEY=pdl_sdbx_apikey_...         # server-side; never expose to the browser
PADDLE_NOTIFICATION_WEBHOOK_SECRET=pdl_ntfset_...  # secret for THIS notification destination
```

Install the server SDK:

```bash
npm install @paddle/paddle-node-sdk
```

## Step 1: Create a notification destination

A notification destination tells Paddle "send these events to this URL." Each destination has its own secret — sandbox and production should be separate destinations with separate secrets, never shared.

1. In the Paddle dashboard, go to **Paddle > Developer tools > Notifications**. (Or, if a Paddle MCP server is available to you, call `client.notificationSettings.create({ destination: "https://...", subscribed_events: [...], type: "url" })` inside an `execute` to create the destination programmatically — skip the dashboard steps below. CamelCase resource, snake_case body. See conventions above.)
2. Click **New destination**.
3. Set:
   - **Description**: `Local dev` (or `Production`).
   - **Type**: `Webhook`.
   - **URL**: your endpoint (e.g. `https://your-app.com/api/webhook` or your tunnelled local URL).
   - **Events**: select the events you need. Start with `transaction.completed`, `subscription.created`, `subscription.updated`, `subscription.canceled`, `customer.created`, `customer.updated`. You can subscribe to more later.
4. Save and **copy the secret key**. You'll only see it once. This goes in `PADDLE_NOTIFICATION_WEBHOOK_SECRET`.

## Step 2: Create a Paddle SDK helper

Create a single instance of the Node SDK so you don't initialize it on every request.

```ts
// utils/paddle/get-paddle-instance.ts
import {
  Environment,
  LogLevel,
  Paddle,
  type PaddleOptions,
} from "@paddle/paddle-node-sdk";

export function getPaddleInstance() {
  const options: PaddleOptions = {
    environment:
      (process.env.NEXT_PUBLIC_PADDLE_ENV as Environment) ??
      Environment.sandbox,
    logLevel: LogLevel.error,
  };
  if (!process.env.PADDLE_API_KEY) {
    throw new Error("PADDLE_API_KEY is not set");
  }
  return new Paddle(process.env.PADDLE_API_KEY, options);
}
```

## Step 3: Write the Route Handler

Two rules. Pre-validate inputs you can check cheaply (return `400`). Wrap everything else in a single try/catch that returns a non-2xx on any throw — including signature failures — so Paddle retries. Any non-2xx works (this skill uses `500`; `401` is equally valid); the only response that loses the event is a `2xx`.

```ts
// app/api/webhook/route.ts
import { NextRequest } from "next/server";
import { getPaddleInstance } from "@/utils/paddle/get-paddle-instance";
import { processEvent } from "@/utils/paddle/process-webhook";

export async function POST(request: NextRequest) {
  const signature = request.headers.get("paddle-signature") ?? "";
  const rawBody = await request.text();
  const secret = process.env.PADDLE_NOTIFICATION_WEBHOOK_SECRET ?? "";

  // Pre-validation: a request with no signature header or empty body
  // can't be verified or processed. 400 is fine here — Paddle will still
  // retry, but that's okay; this is a "you sent us nothing" case.
  if (!signature || !rawBody) {
    return Response.json(
      { error: "Missing signature or body" },
      { status: 400 },
    );
  }

  try {
    const paddle = getPaddleInstance();
    // Throws on signature mismatch, expired timestamp, or malformed event.
    const eventData = await paddle.webhooks.unmarshal(
      rawBody,
      secret,
      signature,
    );

    if (eventData) {
      await processEvent(eventData);
    }

    // Acknowledge fast. Heavy work belongs in a queue (see Step 6).
    return Response.json({ received: true });
  } catch (e) {
    console.error("Webhook error:", e);
    // Any non-2xx tells Paddle to retry — 401 and 500 are equally fine here;
    // only a 2xx would mark the event delivered and lose it. A thrown
    // unmarshal could be a tampered request OR a rotated secret that hasn't
    // been redeployed — they're indistinguishable, so retrying recovers the
    // second case automatically and the first is harmless. We return 500.
    return Response.json({ error: "Internal error" }, { status: 500 });
  }
}
```

`paddle.webhooks.unmarshal()` does three things in one call:

1. Verifies the HMAC signature in the `paddle-signature` header against the raw body using your secret.
2. Throws if the signature is invalid, the timestamp is too old, or the payload is malformed.
3. Returns a typed `EventEntity` with the deserialized payload.

Why a single catch returning one non-2xx, even for signature failures? A thrown `unmarshal` doesn't tell you _why_ it threw — a tampered request, a wrong/rotated secret, an expired timestamp, and a malformed event all surface as the same generic error. Every non-2xx is retried on the same budget, so `401` and `500` are equally event-safe: a rotated secret recovers automatically once you redeploy, whichever you pick. Don't try to split them (`401` for "forged", `500` for "transient") — you can't tell those cases apart, so choose one non-2xx and use it for the whole catch. The only choice that loses events is returning `2xx` on a failure.

## Step 4: Route the event to handlers

Keep the route handler thin. Move event-specific logic into a separate function and switch on `eventType` using the `EventName` enum so TypeScript narrows `event.data` correctly:

```ts
// utils/paddle/process-webhook.ts
import {
  type EventEntity,
  EventName,
  type SubscriptionCreatedEvent,
  type SubscriptionUpdatedEvent,
  type SubscriptionCanceledEvent,
  type TransactionCompletedEvent,
  type CustomerCreatedEvent,
  type CustomerUpdatedEvent,
} from "@paddle/paddle-node-sdk";

/**
 * Paddle delivers at-least-once. The same event.eventId arrives on every retry,
 * so every handler below MUST be idempotent — UPSERT keyed on the Paddle
 * resource id, or dedupe on event.eventId before non-DB side effects.
 */
export async function processEvent(event: EventEntity) {
  // Idempotency: handlers may run multiple times for the same event.eventId.
  switch (event.eventType) {
    case EventName.SubscriptionCreated:
    case EventName.SubscriptionUpdated:
    case EventName.SubscriptionCanceled:
      return handleSubscription(event);
    case EventName.TransactionCompleted:
      return handleTransactionCompleted(event);
    case EventName.CustomerCreated:
    case EventName.CustomerUpdated:
      return handleCustomer(event);
    default:
      // Subscribed to events you don't handle yet? No-op. Better than throwing.
      return;
  }
}

async function handleSubscription(
  event:
    | SubscriptionCreatedEvent
    | SubscriptionUpdatedEvent
    | SubscriptionCanceledEvent,
) {
  // TODO: idempotent UPSERT keyed on event.data.id. See subscription-sync.
  // e.g. db.subscriptions.upsert({ where: { id: event.data.id }, update: {...}, create: {...} })
}

async function handleTransactionCompleted(event: TransactionCompletedEvent) {
  // TODO: idempotent UPSERT keyed on event.data.id, OR dedupe on event.eventId
  // before non-DB side effects (sending receipts, granting credits).
}

async function handleCustomer(
  event: CustomerCreatedEvent | CustomerUpdatedEvent,
) {
  // TODO: idempotent UPSERT keyed on event.data.id.
}
```

The Node SDK exports `EventName` (the string discriminator) and a typed event for each — TypeScript narrows `event.data` correctly when you switch on `eventType`. String literal comparisons like `event.eventType === "subscription.created"` work at runtime but lose narrowing and are fragile to spec changes.

## Step 5: Make handlers idempotent

The default path: write handlers in an UPSERT shape keyed on the Paddle resource id. Then duplicate deliveries become idempotent for free, and you don't need any extra bookkeeping.

```ts
async function handleSubscription(
  event:
    | SubscriptionCreatedEvent
    | SubscriptionUpdatedEvent
    | SubscriptionCanceledEvent,
) {
  await db.subscriptions.upsert({
    where: { id: event.data.id },
    update: {
      status: event.data.status,
      currentBillingPeriod: event.data.currentBillingPeriod,
    },
    create: {
      /* ... */
    },
  });
}
```

Most webhook work fits this shape — subscription state, customer records, transaction records all have stable Paddle ids you can key on. The official starter kit ([`paddle-nextjs-starter-kit`](https://github.com/PaddleHQ/paddle-nextjs-starter-kit), see `src/utils/paddle/process-webhook.ts`) uses `supabase.from("subscriptions").upsert({...})` exactly this way and has no explicit dedup ledger.

### When UPSERT isn't enough: an event-id ledger

Some side effects aren't naturally idempotent — sending a receipt email, granting one-time credits, calling a third-party API that bills per request. For those, dedupe explicitly on `event.eventId`:

```ts
import { db } from "@/lib/db";

export async function processEvent(event: EventEntity) {
  const seen = await db.processedWebhooks.findUnique({
    where: { eventId: event.eventId },
  });
  if (seen) return; // Already handled — return 200 from the route.

  await db.$transaction(async (tx) => {
    await routeEvent(event, tx);
    await tx.processedWebhooks.create({ data: { eventId: event.eventId } });
  });
}
```

The minimal table:

```sql
CREATE TABLE processed_webhooks (
  event_id TEXT PRIMARY KEY,
  processed_at TIMESTAMP NOT NULL DEFAULT NOW()
);
```

Use the ledger only when UPSERT-shaping doesn't cover the side effect. For most subscription/customer/transaction sync work, UPSERT is enough.

## Step 6: Acknowledge fast — queue heavy work

The 5-second timeout is real. If your handler takes longer, Paddle treats it as a connection timeout, marks the delivery as timed out, and counts the attempt against your retry budget.

Pattern:

1. Verify the signature.
2. Record `event.eventId` (or upsert the resource synchronously if it's cheap).
3. Push anything heavier — sending emails, calling third-party APIs, fetching from `paddle.transactions.list()`, generating PDFs — onto a background queue.
4. Return `200`.

Concretely: don't run `paddle.transactions.list()` inline inside a webhook handler. Don't send an email inline. Don't call Stripe or any other third-party from inside the route handler. Verify, queue, ack.

If you don't have a queue yet, a Vercel Queue, AWS SQS, or even a simple "insert into a `pending_jobs` table and process from a cron" is enough.

## Local testing

Two patterns:

**A. Tunnel a public URL to localhost.** Use `ngrok`, `cloudflared`, or `vercel dev --listen 0.0.0.0` then set up a tunnel.

```bash
ngrok http 3000
# Use the https URL as your notification destination URL: https://abc123.ngrok.app/api/webhook
```

Real Paddle events from the sandbox will then hit your local route handler.

**B. Use the webhook simulator.** No tunnel needed for the simulator's "single event" mode targeted at a public URL, but for local testing the easiest flow is:

1. Tunnel to localhost.
2. In the dashboard, go to **Paddle > Developer tools > Simulations**.
3. Pick an event type or a scenario (`subscription.created`, `transaction.paid`).
4. Paddle constructs a payload, signs it with your destination's secret, and POSTs it to your URL.

See `sandbox-testing` for the full sandbox + simulator workflow.

## Common pitfalls

- **Returning `2xx` on a failed verification.** This is the one status mistake that loses events: Paddle considers only 2xx responses as delivered, so a `2xx` on a failed `unmarshal` marks the event delivered and it's never retried. Every non-2xx — `400`, `401`, `500`, `503` — is retried on the same budget, so returning `401` on a signature failure does **not** lose events (a rotated secret still gets the full retry window to recover). The only status that stops retries from the integrator side is `2xx`. If you have actual abuse to fend off, do it at the edge with rate limits, not inside the handler.
- **Splitting the catch into "signature failure → 401" and "handler error → 500."** `unmarshal` throws indistinguishably for a tampered request, a wrong/rotated secret, an expired timestamp, and a malformed event, so the split is illusory — you can't actually tell which case you're in. Pick one non-2xx and use it for the whole catch. One catch, one status, one operational story.
- **Parsing the body before verification.** If you read JSON with `request.json()`, then re-serialize to verify, the byte sequence won't match what Paddle signed. Always use `await request.text()` and pass the raw string to `unmarshal()`.
- **Wrong secret.** Each notification destination has its own secret. Mixing the sandbox secret with a production destination (or vice versa) results in `unmarshal` throwing on every delivery. `PADDLE_NOTIFICATION_WEBHOOK_SECRET` must match the destination you're targeting — and is _not_ the same value as `PADDLE_API_KEY`.
- **Slow handlers.** 30 seconds of work in the route handler will time out at 5 seconds, count as a failed delivery, and burn a retry attempt. Verify, queue, ack.
- **Treating webhooks as ordered.** They aren't. `subscription.updated` can arrive before the corresponding `subscription.created` if the first delivery is being retried. Use `occurred_at` if you need ordering, but the cleanest approach is to make handlers convergent — UPSERT to latest state.
- **Missing events after extended downtime.** If your endpoint is down past the 3-day retry window, those events are gone. On next deploy, query the API for current state instead of waiting for replays — or replay specific events from the notification log.
- **Forgetting to subscribe to the right events.** A destination only sends what you've ticked in the dashboard. Adding a new handler in code without ticking the matching event will leave it silently never firing.
- **Body parsing on Pages Router.** Pages Router examples disabled `bodyParser` to access the raw body. App Router Route Handlers don't need this — `request.text()` always returns raw bytes.

## Verify the integration

1. Add a `console.log(event.eventType, event.eventId)` at the top of `processEvent`.
2. Tunnel localhost (`ngrok http 3000`) and update the destination URL to the tunnel.
3. In the dashboard, go to **Paddle > Developer tools > Simulations** and run a `subscription.created` simulation against your destination.
4. Confirm:
   - The route handler logs the event type.
   - The dashboard shows a 200 response under **Paddle > Developer tools > Notifications > [your destination] > Logs**. (If a Paddle MCP server is available, `client.notifications.logs.list(notificationSettingId, { per_page: 50 })` returns the same — note the path is nested under `notifications`, not a top-level resource, and `notificationSettingId` is a positional path param.)
5. Deliberately tamper with the secret in `.env.local` and re-simulate. Confirm:
   - The handler returns a non-2xx (this skill's handler returns 500).
   - The dashboard log shows the failed delivery and a queued retry.
   - Restore the correct secret afterwards — Paddle will retry the failed delivery and it should succeed.
6. Trigger a real flow: complete a sandbox checkout (see `checkout-web`) and confirm the resulting `transaction.completed` and `subscription.created` events arrive.

## Related docs

- [Webhooks overview](https://developer.paddle.com/webhooks.md)
- [Signature verification](https://developer.paddle.com/webhooks/about/signature-verification.md)
- [Notification destinations](https://developer.paddle.com/webhooks/about/notification-destinations.md)
- [Respond to webhooks (retry semantics)](https://developer.paddle.com/webhooks/about/respond-to-webhooks.md)
- [Test webhooks with the simulator](https://developer.paddle.com/webhooks/simulator/test-webhooks.md)
- [Subscription event reference](https://developer.paddle.com/webhooks/subscriptions/subscription-created.md)
- [Node SDK reference](https://developer.paddle.com/sdks/libraries/node.md)
- Reference implementation: [paddle-nextjs-starter-kit](https://github.com/PaddleHQ/paddle-nextjs-starter-kit) — see `src/app/api/webhook/route.ts` and `src/utils/paddle/process-webhook.ts`.
