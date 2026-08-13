---
name: paddle-sandbox-testing
description: Test a Paddle integration end-to-end using the sandbox environment, test cards, the webhook simulator, and local tunnels — without taking real money.
---

# Test a Paddle integration in the sandbox

## When to use this skill

Use this skill when verifying a Paddle integration before going live — running real-looking checkouts, triggering webhook events, and exercising subscription state changes without taking real money. Covers sandbox configuration, test card numbers, the webhook simulator (single events and scenarios), local development with public tunnels, and the differences between sandbox and live behavior that bite people in production.

This skill is the safety net for everything else. Apply it alongside `checkout-web`, `webhooks`, and `subscription-sync` whenever you ship — sandbox testing is the cheapest way to catch integration bugs.

## Sandbox vs production at a glance

Paddle gives you two completely separate environments:

|                               | **Sandbox**                           | **Production**             |
| ----------------------------- | ------------------------------------- | -------------------------- |
| Purpose                       | Development and testing               | Real customers, real money |
| API base                      | `sandbox-api.paddle.com`              | `api.paddle.com`           |
| Dashboard                     | `sandbox-vendors.paddle.com`          | `vendors.paddle.com`       |
| API keys                      | Separate set                          | Separate set               |
| Client tokens                 | Separate set                          | Separate set               |
| Notification destinations     | Separate set                          | Separate set               |
| Products / prices / customers | Separate (none synced)                | —                          |
| Cards accepted                | Test cards only (real cards rejected) | Real cards                 |
| Adjustments / refunds         | Auto-approved                         | Manual approval            |
| Tax                           | Calculated but not collected          | Real tax handling          |
| Domain approval               | No approval required (any domain)     | Manual approval per domain |

**Nothing crosses between them.** A sandbox `pri_01h...` does not exist in production. Notification destinations are separate. Customers are separate. This is intentional — it makes "test in sandbox, then promote to live" the safe default.

## Step 1: Configure sandbox

Sign up for the sandbox at [sandbox-vendors.paddle.com](https://sandbox-vendors.paddle.com) (separate signup from production). Then:

1. Set up at least one **product** with one **price** — see `catalog-setup` if you haven't done this yet.
2. Go to **Paddle > Developer tools > Authentication** and create:
   - A **client-side token** for use in `Paddle.js`.
   - A **server-side API key** for the Node SDK.
3. Note the IDs and set them in your local env:

```bash
# .env.local — sandbox values
NEXT_PUBLIC_PADDLE_ENV=sandbox
NEXT_PUBLIC_PADDLE_CLIENT_TOKEN=test_...
PADDLE_API_KEY=pdl_sdbx_apikey_...
PADDLE_NOTIFICATION_WEBHOOK_SECRET=pdl_ntfset_...
```

The `PADDLE_API_KEY` for sandbox is prefixed `pdl_sdbx_apikey_` — production keys are prefixed `pdl_live_apikey_`. Glance at the prefix anytime you're not sure which environment you're hitting.

## Step 2: Use test cards

In sandbox, **only test cards work**. Real cards are rejected. The standard test card:

```
Card number: 4242 4242 4242 4242
Expiry:      any future month/year
CVC:         any 3 digits
ZIP/Postal:  any value
Name:        any value
```

Other test cards trigger specific scenarios:

| Card number           | Result                                                 |
| --------------------- | ------------------------------------------------------ |
| `4242 4242 4242 4242` | Valid card without 3DS                                 |
| `4000 0038 0000 0446` | Valid card with 3DS                                    |
| `4000 0566 5566 5556` | Valid Visa debit card                                  |
| `4000 0000 0000 0002` | Declined card                                          |
| `4000 0027 6000 3184` | Initial success, subsequent renewals decline (dunning) |

Full list at [developer.paddle.com/sdks/sandbox#test-cards](https://developer.paddle.com/sdks/sandbox#test-cards). Use the failure cards to confirm your `checkout.payment-error` handling, dunning flow, and `subscription.past_due` handling work end-to-end.

## Step 3: The webhook simulator

The simulator lets you fire **fake-but-correctly-signed** webhook events at your endpoint without going through a real flow. Two modes:

**Single event.** Pick one event type (e.g. `subscription.canceled`), edit the JSON payload, hit "Send". Paddle signs it with your destination's secret and POSTs it to your URL. Use this to test specific handlers in isolation — e.g. "what does my UI do when `subscription.paused` arrives?"

**Scenario.** A pre-defined sequence of events that mirrors a real flow. Examples:

- `subscription_created` — fires `customer.created`, `transaction.completed`, `subscription.created`, and more depending on the checkout flow.
- `subscription_renewed` — fires `subscription.updated`, `transaction.created`, `transaction.updated`, `transaction.completed`, `transaction.paid`.
- `subscription_canceled` — fires `subscription.updated` (with `scheduled_change`), then `subscription.canceled` after a delay.

Find the simulator at **Paddle > Developer tools > Simulations**. If a Paddle MCP server is available to you, you can drive the simulator programmatically inside one `execute` call instead of using the dashboard — create the simulation, then create a run against it:

```js
async (client) => {
  const sim = await client.simulations.create({
    name: "Subscription created flow",
    type: "subscription_created",
    notification_setting_id: "ntfset_...",
  });
  const run = await client.simulations.runs.create(sim.id, {});
  return { simulation_id: sim.id, run_id: run.id };
};
```

Note `client.simulations.runs.create` is nested under `simulations` (not a top-level `client.simulationRuns`), and the simulation ID is a positional path param.

> The Paddle MCP exposes three tools per server (`search`, `execute`, `report_missing_tool`). Workflow: call `search` to confirm the exact method name and parameter shapes, then call `execute` with an async function that calls `client.<resource>.<operation>(...)`. **Method paths are camelCase** (`client.clientTokens.create`, `client.pricingPreview.preview`). **Body params and response fields are snake_case** (`tax_category`, `product_id`, `unit_price`, `currency_code`). Pagination is `{ pagination: { hasMore }, data: [...] }` with `{ after: "<last_id>" }` — not `.next()` / `.hasMore`. Chain multi-step workflows inside one `execute`; variables don't persist between calls. Hard caps: 50 API calls per execute, 30s timeout, 32KB code.

To use the simulator:

1. In the dashboard, navigate to the simulator.
2. Pick a scenario or single event.
3. Set the notification destination URL — your tunnelled local URL or a deployed preview URL.
4. (Optional) Edit the payload JSON to use specific IDs (e.g. a `customer_id` you've already created in your DB).
5. (Optional) Configure your simulation (e.g. set the payment outcome to `failed` to simulate a failed payment).
6. Send. Watch your local server logs.

The signature on a simulated event uses the **destination's secret**, so your normal verification code works without changes. If verification fails, the secret you've set in `PADDLE_NOTIFICATION_WEBHOOK_SECRET` doesn't match the destination you're simulating against.

## Step 4: Tunnel localhost for real events

The simulator covers most testing. For real flows (e.g. completing an actual sandbox checkout to verify the full chain), Paddle needs to reach your local machine. Use a tunnel:

**Hookdeck** (recommended):

```bash
# Install: brew install hookdeck/hookdeck/hookdeck
hookdeck listen 3000
# Copy the https URL: https://hkdk.events/abc123xyz
```

Then update your sandbox notification destination URL to the tunnelled URL (e.g. `https://hkdk.events/abc123xyz`).

## Step 5: A typical end-to-end test

Here's the loop for verifying a fresh integration:

1. **Tunnel** localhost: `hookdeck listen 3000`.
2. **Update the sandbox destination** URL to the tunnel.
3. **Open** your pricing page → click Subscribe.
4. **Pay** with `4242 4242 4242 4242`.
5. **Confirm**:
   - Browser lands on your `successUrl`.
   - Server logs show all events
   - Your DB has the new customer and subscription rows (if you've wired `subscription-sync`).
   - The dashboard shows the transaction under **Transactions**.
6. **Cancel** the subscription using simulator, passing the subscription ID from the simulated subscription creation event.
7. **Confirm**:
   - A `subscription.updated` event arrives.
   - (Optional) Your DB row updates `scheduled_change`, status remains `active` momentarily, `scheduled_change.action: 'cancel'`
   - A `subscription.canceled` event arrives with status `canceled`; your row should flip to `canceled`.

## Sandbox vs live: differences that may catch you out

These behaviors only happen in production.

| **Behavior**                      | **Sandbox**                              | **Live**                                                          | **Why it matters**                                                                                                                             |
| --------------------------------- | ---------------------------------------- | ----------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| **Adjustments (refunds/credits)** | Auto-approved every ten minutes          | High value refunds require approval from Paddle                   | Adjustments stay `pending` until approved—your handler should account for `adjustment.updated` flipping `status` from `pending` to `approved`. |
| **Payouts**                       | No payouts                               | Real money to your bank                                           | Test payout webhook handling against simulated payouts only.                                                                                   |
| **Domain approval**               | All domains automatically approved       | Manual per-domain in **Checkout settings → Default payment link** | Forgetting domain approval means production checkout fails to load.                                                                            |
| **Dunning (Retain)**              | Not available                            | Available                                                         | Retain handles canceling or pausing past due subscriptions.                                                                                    |
| **Email delivery**                | Only sent to the registered email domain | Sent to the email on the customer record (real)                   | Use an email from your domain in sandbox.                                                                                                      |

## Common pitfalls

- **Mixing sandbox and live keys.** Always confirm `PADDLE_API_KEY` and `NEXT_PUBLIC_PADDLE_ENV` are for the correct environment.
- **Sandbox environment param in production.** Always confirm the environment parameter is set to `production` when initializing Paddle.js in production.
- **Forgetting to update the notification destination URL after restarting hookdeck.** Symptoms: events fire on Paddle's side but never reach your code.
- **Testing with a real card "just to be sure"**. Sandbox rejects them. Production accepts them and charges them.
- **Not testing the failure path.** Use `4000 0000 0000 0002` to confirm `checkout.payment.error` handling, then use simulator to test a failed subscription renewal.
- **Using the same notification destination secret in code for both environments.** They are not the same. Each environment, each destination, has its own secret.
- **Testing only the happy path.** Real users may hit weird states — declined cards, expired trials, immediate cancellations. Use the simulator to fire each edge case and watch your code respond.

## Related docs

- [Sandbox overview](https://developer.paddle.com/sdks/sandbox.md)
- [Test cards](https://developer.paddle.com/sdks/sandbox.md#test-cards)
- [Test webhooks with the simulator](https://developer.paddle.com/webhooks/simulator/test-webhooks.md)
- [Simulator scenarios reference](https://developer.paddle.com/webhooks/simulator.md)
- [Go-live checklist](https://developer.paddle.com/build/go-live-checklist.md)
- [API authentication](https://developer.paddle.com/api-reference/about/authentication.md)
