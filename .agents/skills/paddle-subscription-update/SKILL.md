---
name: paddle-subscription-update
description: Change a Paddle subscription's plan from a Next.js Server Action — auth, ownership check, `prorationBillingMode`, items-array replace semantics, preview-before-commit, and `on_payment_failure` handling.
---

# Update a Paddle subscription's plan from Next.js

## When to use this skill

Use this skill when building "Upgrade plan," "Switch tier," or "Change subscription" actions in your authenticated user's billing UI. It covers a Next.js 15 (App Router) Server Action that calls `paddle.subscriptions.update()`, the four `prorationBillingMode` choices and when to pick each, the _replace not append_ semantics of the items array, and the security checks every plan-change action needs.

This is the _initiating_ side of subscription changes. Pair it with:

- `subscription-sync` — your webhook handler will get a `subscription.updated` event after the change. Your DB mirror picks up the new plan, status, and pricing.
- `subscription-cancel` — same auth/ownership shape; if you're building both, share the helpers.

## Prerequisites

- A Paddle account with **at least two prices** the user can switch between (e.g. monthly Starter and monthly Pro). Sandbox is fine.
- Server-side `PADDLE_API_KEY` available — Server Action only.
- Customer + subscription state mirrored from webhooks (see `subscription-sync`).
- An auth system. Examples use Supabase.

```bash
NEXT_PUBLIC_PADDLE_ENV=sandbox             # or "production"
PADDLE_API_KEY=pdl_sdbx_apikey_...

NEXT_PUBLIC_SUPABASE_URL=...
NEXT_PUBLIC_SUPABASE_ANON_KEY=...
SUPABASE_SERVICE_ROLE_KEY=...              # or SUPABASE_SECRET_KEY (new opaque sb_secret_*)
```

## Choose your `prorationBillingMode`

This is the one parameter most people get wrong. Paddle's `subscriptions.update` accepts five values; for a typical "Upgrade to Pro" flow the right choice is **`prorated_immediately`**.

| Value                          | What happens                                                                                   | When to use                                                                  |
| ------------------------------ | ---------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| `prorated_immediately`         | Charge the prorated difference between old and new plan **now**, switch effective immediately. | "Upgrade plan" — user expects access to the new plan + a charge now.         |
| `prorated_next_billing_period` | Calculate the prorated amount but bill it on the next renewal. Plan switch is immediate.       | "Switch plan, but bill me later" — rare, but legitimate.                     |
| `full_immediately`             | Charge the **full new-plan price** now. Plan switch is immediate.                              | Not often. Over-charges the user for a partial period.                       |
| `full_next_billing_period`     | Charge full new-plan price on next renewal. Plan switch is immediate.                          | "Try the new plan free until renewal" — uncommon but legitimate.             |
| `do_not_bill`                  | Switch plans now. Don't bill anything.                                                         | Internal/team upgrades, customer service comps. **Never** for self-serve UX. |

There is no default. You must pass one of the five values.

## Upgrade, downgrade, or term change?

`subscriptions.update` is one endpoint covering several distinct user-visible flows. The right `prorationBillingMode` depends on which:

| Flow                      | Example                   | Typical `prorationBillingMode`                                                                                                                                                                          |
| ------------------------- | ------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Tier upgrade**          | Starter → Pro (same term) | `prorated_immediately` — user expects the better plan now and pays the difference now.                                                                                                                  |
| **Tier downgrade**        | Pro → Starter (same term) | `prorated_next_billing_period` — don't refund the customer mid-period; just adjust at renewal. (`prorated_immediately` would issue a prorated refund, which most apps don't want for a "downgrade" UX.) |
| **Term switch (longer)**  | Pro monthly → Pro annual  | `prorated_immediately` — annual is a much larger payment; charging now is correct.                                                                                                                      |
| **Term switch (shorter)** | Pro annual → Pro monthly  | `prorated_next_billing_period` — let the annual run out, switch at renewal. Refunding annual mid-period gets complicated.                                                                               |

### Same-billing-interval rule for items

A subscription's items must all share the same billing interval. So if the user has a **Pro monthly** plan **plus a monthly addon** and switches to **Pro annual**, the addon must also become annual at the same time — it cannot stay monthly. Practically:

- If you sell tier + addons, your `items` array on the update must include the new addon price IDs that match the new term.
- If you sell tier-only, this doesn't affect you.

This is a constraint on what you submit to the SDK, not just a UX consideration. The API rejects mixed-interval `items` arrays.

## Preview before committing

For non-trivial flows — especially term changes, anything where the prorated amount isn't obvious, or anything you're going to ask the user to confirm — show the preview before committing. Paddle's `subscriptions.previewUpdate` takes the same request body as `update` but doesn't apply anything:

```ts
const preview = await paddle.subscriptions.previewUpdate(subscriptionId, {
  items: [{ priceId: newPriceId, quantity: 1 }],
  prorationBillingMode: "prorated_immediately",
});

// preview.immediateTransaction       — the prorated charge details (if any)
// preview.recurringTransactionDetails — the new ongoing price
// preview.nextBilledAt                — when the next renewal will hit
```

Render those numbers on a confirm screen ("You'll be charged $X today, then $Y/year starting <date>"). When the user confirms, run the same body through `subscriptions.update`. **Don't try to compute the prorated amount yourself** — the preview is the source of truth, and the math accounts for tax, currency, and existing credit balances you may not be aware of.

For simple "Upgrade to Pro" flows where the price difference is small and obvious, you can skip the preview and trust the user to know what they're agreeing to from the button label. For everything else, the preview pays for itself in support tickets you don't get.

## How `paddle.subscriptions.update` handles items

The `items` field on the update is a **full replacement** of the subscription's line items, not an append:

```ts
// BEFORE: subscription has items: [{ priceId: 'pri_starter_monthly', quantity: 1 }]

await paddle.subscriptions.update(subscriptionId, {
  items: [{ priceId: "pri_pro_monthly", quantity: 1 }],
  prorationBillingMode: "prorated_immediately",
});

// AFTER: subscription has items: [{ priceId: 'pri_pro_monthly', quantity: 1 }]
//        the Starter item is gone — replaced by Pro.
```

For a single-item plan change, that's exactly what you want. **Do not** fetch the existing items and concat the new one — you'd end up with a subscription that's billed for both plans simultaneously.

For multi-item subscriptions (a base plan + addons), you do need to think about which items you're keeping. In that case, fetch the current subscription, modify the items array carefully, and pass the modified array back.

## The full Server Action

```ts
// src/actions/subscription.ts
"use server";

import { revalidatePath } from "next/cache";
import { getPaddleInstance } from "@/utils/paddle/get-paddle-instance";
import { createServerInternalClient } from "@/utils/supabase/server-internal";
import { createServerClient } from "@/utils/supabase/server";

export async function updateSubscription(
  subscriptionId: string,
  newPriceId: string,
) {
  // 1. Authenticate. Reject anonymous requests before any DB query or SDK call.
  const supabase = await createServerClient();
  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user || !user.email) {
    return { error: "Not authenticated" };
  }

  // 2. Verify the authenticated user owns this subscriptionId. Same email-bridge
  //    pattern as subscription-cancel.
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

  // 3. Apply the plan change. Replace items with the single new item; bill the
  //    prorated difference now (the right call for an "Upgrade" button). The
  //    default behaviour on payment failure is `prevent_change` — the upgrade
  //    only applies if the prorated charge succeeds. That's the safe choice for
  //    self-serve. See "Handling payment failure" below for when 'apply_change'
  //    is the right override.
  const paddle = getPaddleInstance();
  const subscription = await paddle.subscriptions.update(subscriptionId, {
    items: [{ priceId: newPriceId, quantity: 1 }],
    prorationBillingMode: "prorated_immediately",
  });

  // 4. Refresh cached UI.
  revalidatePath("/dashboard/subscriptions");

  // 5. Slim DTO — don't leak the raw Subscription.
  return {
    success: true,
    priceId: subscription.items[0]?.price?.id ?? null,
    status: subscription.status,
  };
}
```

## What the user sees vs what gets billed

A `prorated_immediately` upgrade triggers Paddle to:

1. Generate a **transaction** for the prorated difference between the old and new plan, billed against the customer's existing payment method.
2. Send a `transaction.created` (then `transaction.completed` if the charge succeeds, or `transaction.payment_failed` if it doesn't).
3. Send a `subscription.updated` reflecting the new items and `next_billed_at` recalculated for the new plan.

If the prorated charge **fails** (declined card, expired method), what happens depends on `onPaymentFailure` — see the next section.

## Handling payment failure

When you pick an immediate proration mode (`prorated_immediately` or `full_immediately`), Paddle attempts to charge the customer **synchronously** during the update call. If that charge fails, the `onPaymentFailure` field decides whether the plan change still applies:

| Value                      | What happens on payment failure                                                                                                                                                                                                     |
| -------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `prevent_change` (default) | The plan change is **not applied**. The subscription stays on its old plan. The API returns an error so the UI can surface a clear "payment failed, please retry" state.                                                            |
| `apply_change`             | The plan change **is applied** anyway. The transaction is left in `past_due` so you can collect the prorated amount later, but the _subscription itself_ doesn't enter dunning — because the user explicitly accepted this outcome. |

Pick based on the flow:

- **Default `prevent_change`** is appropriate for most self-serve UX: the user clicks Upgrade, the card declines, they see "Payment failed" and can update their card and retry. Every step is explicit. You don't need to set it explicitly — leaving it out gives you this behaviour.
- **`apply_change`** is appropriate for admin-driven flows (a CS rep upgrading a customer mid-trial) or for "switch tier first, collect later" patterns. The customer immediately gets the new plan; you collect the past-due transaction asynchronously. **Don't pick this without thinking about your collections process** — you've effectively extended credit.

For the admin-driven case, set `onPaymentFailure` explicitly:

```ts
// Admin-driven upgrade: apply the change even if the card fails. The customer
// gets the new plan; the prorated charge sits in past_due until you collect.
await paddle.subscriptions.update(subscriptionId, {
  items: [{ priceId: newPriceId, quantity: 1 }],
  prorationBillingMode: "prorated_immediately",
  onPaymentFailure: "apply_change",
});
```

The Node SDK uses camelCase (`onPaymentFailure`); the wire format is `on_payment_failure`.

## Common pitfalls

- **Wrong `prorationBillingMode`.** The defaults table is the heart of this skill. `do_not_bill` for a self-serve upgrade is a billing-accounting hole; `full_immediately` overcharges; `prorated_next_billing_period` surprises users who expected a charge. For "Upgrade to Pro" UX, **always `prorated_immediately`**.
- **Appending instead of replacing items.** Pulling `subscription.items` from the mirror, concatenating the new item, and sending the combined array results in a subscription billed for two plans. The endpoint replaces — pass only what you want.
- **No ownership check.** Same as `subscription-cancel`: trusting the `subscriptionId` input lets any authenticated user upgrade any subscription's plan.
- **No auth check.** Server Actions are callable from anywhere — anonymous, scripted, or via direct POST. Always verify the session.
- **Optimistic UI based on the action's return.** The action returning `{ success: true }` means Paddle accepted the change request. It does NOT mean the prorated charge succeeded. Wait for `transaction.completed` (via webhook) before granting features that depend on payment success.
- **Missing `revalidatePath`.** UI shows the old plan until manual refresh.
- **Returning the raw `Subscription`.** Same as cancel — the SDK's instance is large; slim it.
- **Skipping the preview step on a non-trivial change.** For term changes especially, the prorated calculation isn't obvious — Paddle may credit existing balances, apply tax, or convert currency in ways your back-of-envelope estimate misses. Showing the user a confirm screen with the actual numbers from `previewUpdate` before they commit avoids "wait, why was I charged this much?" tickets. Skip the preview only when the price impact is small and obvious.
- **Not knowing `onPaymentFailure` exists.** The default (`prevent_change`) is correct for self-serve and you don't need to override it. The trap is _not realizing the option exists_ when you have a flow where you do need `apply_change` — e.g. a CS rep upgrading a customer with a temporarily-failing card. If you don't know the field is there, you'll work around the limitation in clumsier ways.
- **Mixed billing intervals on items.** If you sell tier + addons and let users switch between monthly and annual, the addons must move with the tier. Submitting `[{ priceId: 'pri_pro_annual' }, { priceId: 'pri_addon_monthly' }]` will fail — Paddle rejects mixed-interval `items` arrays. When designing your "switch term" flow, plan for swapping addon price IDs alongside the tier.
- **Treating downgrade like upgrade.** `prorated_immediately` on a Pro → Starter switch issues a prorated refund mid-period — most apps don't want that for a "downgrade" UX. Use `prorated_next_billing_period` so the change applies at renewal without refunding.

## Verify the integration

1. Have a sandbox subscription on the cheaper of two plans (e.g. Starter monthly).
2. Call `paddle.subscriptions.previewUpdate` with the upgrade body. Confirm the returned numbers (immediate transaction, recurring details, next-billed-at) match what you'd expect.
3. Click "Upgrade to Pro." The action should return `{ success: true, priceId: 'pri_pro_monthly', status: 'active' }`.
4. In the Paddle dashboard:
   - Subscription's items are now Pro only (Starter is gone).
   - A new transaction with the prorated difference appears, and matches the preview from step 2.
5. Check your DB mirror after the `subscription.updated` webhook fires: the row has the new `price_id` and `product_id`.
6. Switch your sandbox card to a [test card that declines](https://developer.paddle.com/sdks/sandbox#test-cards) and retry the upgrade. With `onPaymentFailure: 'prevent_change'`, confirm the subscription stays on the old plan and your action returns an error. Switch to `onPaymentFailure: 'apply_change'` and confirm the subscription flips to the new plan and a `past_due` transaction appears.
7. Try the action with a `newPriceId` you don't actually offer. Confirm Paddle rejects it (or — better — your action validates the `newPriceId` against your `PricingTier` constant before calling the SDK).
8. Try the action while logged out, or with a forged `subscriptionId` belonging to a different user. Confirm both are rejected before the SDK is called.

## Related docs

- [Upgrade or downgrade a subscription](https://developer.paddle.com/build/subscriptions/replace-products-prices-upgrade-downgrade.md)
- [Proration billing modes](https://developer.paddle.com/concepts/subscriptions/proration.md)
- [Update a subscription - API](https://developer.paddle.com/api-reference/subscriptions/update-subscription.md)
- [Preview an update to a subscription - API](https://developer.paddle.com/api-reference/subscriptions/preview-update-subscription.md)
