---
name: paddle-customer-portal
description: Mint a Paddle customer portal session URL from a Next.js Server Action — the portal-vs-custom-billing-screen trade-off, auth, ownership, URL structure (overview vs deep links), and the security model.
---

# Mint Paddle Customer Portal session URLs from Next.js

## When to use this skill

Use this skill when you want **Paddle to host the UI** for customer self-service — viewing invoices, updating payment methods, canceling subscriptions — and you just want to send the user to that hosted UI from a "Manage subscription" button in your app. It covers a Next.js 15 (App Router) Server Action that authenticates the user, looks up their Paddle customer ID, calls `paddle.customerPortalSessions.create`, and returns the URL to redirect them to.

This is the _portal_ approach. The alternative is to build a custom billing screen using the Paddle API directly — see "Should you use the portal at all?" below for the trade-off.

## Should you use the portal at all?

The Paddle customer portal is **one option** for letting users manage their subscriptions. The other is to build your own billing UI that talks to the Paddle Node SDK directly. Each has its place:

| Option                                                                                                                                                   | When it fits                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| -------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Paddle customer portal** (this skill)                                                                                                                  | You want the fastest path to "users can self-service their subscription." Paddle ships the UI, handles localization, layout, and updates as features ship. Good for early-stage products, MVPs, or any product where the billing surface isn't the differentiator.                                                                                                                                                                                     |
| **Custom billing screen** (see `subscription-cancel`, `subscription-update`, plus direct API calls for listing transactions, downloading invoices, etc.) | You want full brand control, deep integration with your app's design system, custom flows (in-app upgrade prompts, retention offers in the cancel flow, custom invoice presentation), or tight composition with non-billing UI. The [Paddle Next.js starter kit](https://github.com/PaddleHQ/paddle-nextjs-starter-kit) takes this approach — its dashboard renders subscriptions and transactions natively and uses Server Actions for cancel/update. |

You can also **mix**: use the portal for the long tail of management actions (download invoice, change payment method) and build custom UI for the high-value flows (cancel, with a retention offer; upgrade, with a confirmation showing the prorated charge). The portal session URL has deep links for specific subscription actions if you want to embed them rather than send the user to the overview.

The choice isn't permanent. A common evolution: ship the portal first to unblock customer self-service, then progressively replace specific flows with custom UI as those flows become important to your product.

## Prerequisites

- A Paddle account with at least one customer record (sandbox is fine).
- Server-side `PADDLE_API_KEY` available — this action runs in a Server Action, never in the browser.
- A `customers` table mirrored from webhooks (see `subscription-sync`). You need the user's Paddle `customer_id` to mint a session, and you look it up via the email bridge.
- Optionally, a `subscriptions` table — you'll pass active subscription IDs to the SDK to get per-subscription deep links in the response.
- An auth system. Examples use Supabase, but any session-based auth works.

```bash
NEXT_PUBLIC_PADDLE_ENV=sandbox             # or "production"
PADDLE_API_KEY=pdl_sdbx_apikey_...

NEXT_PUBLIC_SUPABASE_URL=...
NEXT_PUBLIC_SUPABASE_ANON_KEY=...
SUPABASE_SERVICE_ROLE_KEY=...              # or SUPABASE_SECRET_KEY (new opaque sb_secret_*)
```

## How portal sessions work

```ts
const session = await paddle.customerPortalSessions.create(
  customerId, // "ctm_01h..."
  subscriptionIds, // string[] — e.g. ["sub_01h...", "sub_02h..."]
);
```

The returned `session` exposes:

- `session.urls.general.overview` — **the main "open my portal" URL.** This is what you redirect the user to for the standard self-service flow. They land on the portal home and can navigate from there.
- `session.urls.subscriptions[]` — array of per-subscription deep links, one entry per `subscriptionId` you passed:
  - `id` — matches the input subscription ID.
  - `cancelSubscription` — direct link to the cancel UI for that specific sub.
  - `updateSubscriptionPaymentMethod` — direct link to the payment method UI for that sub.

These URLs are **one-time use and time-limited.** Don't cache them. Don't reuse them. Mint a fresh session every time the user clicks Manage.

If you have no active subscriptions to pass, an empty `subscriptionIds` array is valid — the portal still works for general account/invoice access; you just don't get the per-subscription deep links.

## The full Server Action

```ts
// src/actions/portal.ts
"use server";

import { getPaddleInstance } from "@/utils/paddle/get-paddle-instance";
import { createServerInternalClient } from "@/utils/supabase/server-internal";
import { createServerClient } from "@/utils/supabase/server";

export async function createPortalSession() {
  // 1. Authenticate. Reject anonymous requests before any DB query or SDK call.
  const supabase = await createServerClient();
  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user || !user.email) {
    return { error: "Not authenticated" };
  }

  // 2. Look up the authenticated user's Paddle customer_id via the email
  //    bridge. The customers table is the mirror your webhook handler
  //    populates from customer.created / customer.updated events.
  const internal = createServerInternalClient();
  const { data: customerRow } = await internal
    .from("customers")
    .select("customer_id")
    .eq("email", user.email)
    .single();

  if (!customerRow?.customer_id) {
    // The user signed up but hasn't completed a checkout yet, so no Paddle
    // customer record exists. Don't pass an empty string to the SDK — it
    // will 400. Either redirect them to a "subscribe first" page or return
    // a clear error the UI can render.
    return { error: "No Paddle customer" };
  }

  // 3. (Optional) Look up the customer's active subscriptions, so the portal
  //    response includes per-subscription deep links you can use elsewhere
  //    in your UI. An empty array is fine — the portal overview URL works
  //    either way.
  const { data: subRows } = await internal
    .from("subscriptions")
    .select("subscription_id")
    .eq("customer_id", customerRow.customer_id);

  const subscriptionIds = (subRows ?? []).map((r) => r.subscription_id);

  // 4. Mint the session. Notice the customerId comes from step 2 (the
  //    authenticated user's record), NOT from any input parameter — a user
  //    must never be able to request a portal session for someone else's ID.
  const paddle = getPaddleInstance();
  const session = await paddle.customerPortalSessions.create(
    customerRow.customer_id,
    subscriptionIds,
  );

  // 5. Return ONLY the URL. Don't leak the raw session object — it includes
  //    the customer_id, session id, and full deep-link table that the client
  //    doesn't need for a "redirect to portal" flow.
  return { url: session.urls.general.overview };
}
```

The client component calling this is trivial:

```tsx
// src/components/manage-subscription-button.tsx
"use client";
import { createPortalSession } from "@/actions/portal";

export function ManageSubscriptionButton() {
  return (
    <button
      onClick={async () => {
        const result = await createPortalSession();
        if ("error" in result) {
          // surface the error to the user
          return;
        }
        window.location.href = result.url;
      }}
    >
      Manage subscription
    </button>
  );
}
```

## Security model

Three things matter:

1. **Auth before anything else.** The action must check the session before any DB query or SDK call. A portal URL is a key to a customer's billing data — minting one for an unauthenticated request is a critical bug.
2. **`customerId` from the authenticated user, not from input.** The action takes no `customerId` parameter (or any client-supplied identifier that influences which customer's portal is created). The `customerId` is _resolved server-side_ from the auth session via the customers table. This prevents an authenticated user from requesting "the portal URL for ctm_01h-someone-else."
3. **Return only the URL.** The full `session` object includes `customerId`, the session ID, and the deep-link table. The client only needs the redirect URL — return only that.

## Common pitfalls

- **No auth check.** Server Actions are callable from anywhere — anonymous, scripted, direct POST. Without auth, anyone can mint portal URLs.
- **Trusting `customerId` from the action input.** Any pattern where the client passes a `customerId` and the action uses it directly is a security hole — an authed user could request a portal for any customer they know the ID of.
- **Caching the URL.** Portal session URLs are one-time use and time-limited. A cached URL will fail when used. Always mint a fresh session per click.
- **Returning the raw session object.** Leaks `customer_id`, session metadata, and per-subscription deep links the client doesn't need. Return only `urls.general.overview` (or, if you do need the deep links for embedding, the specific URL — never the whole session).
- **Not handling the no-customer case.** A new user who hasn't checked out yet has no Paddle `customer_id`. Passing an empty string or null to `customerPortalSessions.create` returns a 400 with a confusing error. Detect the missing customer explicitly and surface a "subscribe first" message.
- **Confusing "open the portal" with "cancel via the portal".** `urls.general.overview` is the _home_ of the portal — it lets the user do anything the portal supports. If you want to send the user _directly_ to the cancel UI (for a specific subscription), use `urls.subscriptions[i].cancelSubscription` instead. Most apps just use the overview.
- **Using the portal when you should use a custom screen, or vice versa.** The portal-vs-custom decision is a real product decision (see "Should you use the portal at all?" above), not just a coding shortcut. If your cancel flow needs a retention step, the portal won't get you there — build it custom. If you don't have brand-or-flow requirements, the portal is the right call until you do.

## Verify the integration

1. As a logged-in user with a Paddle customer record (i.e. one who has completed a sandbox checkout), click "Manage subscription." The action should return `{ url: '<some https url>' }`. Visiting that URL opens the portal home.
2. Confirm the URL is unique on every click — the `urls.general.overview` value should differ each time. If it doesn't, you're caching the session.
3. Try the action while logged out. Confirm it returns `{ error: 'Not authenticated' }` and the SDK was NOT called.
4. Try the action as a brand-new user (no checkout completed, no row in `customers`). Confirm it returns `{ error: 'No Paddle customer' }` and the SDK was NOT called.
5. (If you've added input parameters to the action — don't, but if you have for some other reason —) confirm there is no way for the action to mint a portal URL for a customer the authenticated user doesn't own.
6. Inspect the response from `customerPortalSessions.create` directly. Confirm `session.urls.subscriptions` includes entries for each subscription ID you passed, with `cancelSubscription` and `updateSubscriptionPaymentMethod` URLs you could use as deep links elsewhere.

## Related docs

- [Customer portal overview](https://developer.paddle.com/concepts/customer-portal/overview.md)
- [`customerPortalSessions.create` API reference](https://developer.paddle.com/api-reference/customer-portals/create-portal-session.md)
- [Paddle Node SDK reference](https://developer.paddle.com/sdks/libraries/node.md)
- Reference implementation (the _custom_ screen approach, not portal): [paddle-nextjs-starter-kit](https://github.com/PaddleHQ/paddle-nextjs-starter-kit) — see `src/app/dashboard/subscriptions/` and `src/app/dashboard/payments/`.
