---
name: paddle-checkout-web
description: Add a Paddle Checkout to a Next.js web app — overlay or inline, with event handling, customer pre-fill, and dynamic line item updates.
---

# Build a Paddle Checkout in Next.js

## When to use this skill

Use this skill when adding a hosted Paddle checkout to a Next.js (App Router) app, either as an overlay (modal-style) or inline (embedded within your page). It covers initialization, opening checkout with line items, listening to events, customer pre-fill, the post-checkout redirect, and the throttled `updateItems` pattern for cart-like UIs.

This skill is client-side only. Pair it with:

- `webhooks` to receive `transaction.completed` / `subscription.created` events on your server.
- `subscription-sync` to mirror Paddle subscription state into your database.
- `sandbox-testing` to test the integration end-to-end in the Paddle sandbox.

## Prerequisites

You need:

- A Paddle account, with the **sandbox** environment active during development.
  - Sign up for sandbox: https://sandbox-vendors.paddle.com/
  - Sign up for live: https://vendors.paddle.com/
- At least one product and price set up. Use `catalog-setup` if you haven't yet — note the price ID (looks like `pri_01h...`) for use below.
- Your domain approved for checkout under **Paddle > Checkout > Website approval**. Domains are approved automatically in sandbox.
- Your default payment link set under **Paddle > Checkout > Checkout settings > Default payment link**. You can use `https://localhost/` for sandbox, then change later.
- Two environment variables from **Paddle > Developer tools > Authentication**:

```bash
NEXT_PUBLIC_PADDLE_CLIENT_TOKEN=test_...   # client-side token, safe to expose
NEXT_PUBLIC_PADDLE_ENV=sandbox             # or "production"
```

If a Paddle MCP server is available to you, call `client.clientTokens.create({ name: "Frontend dev token" })` inside an `execute` to provision the token programmatically rather than asking the user to copy it from the dashboard. Note `clientTokens` is camelCase.

> The Paddle MCP exposes three tools per server (`search`, `execute`, `report_missing_tool`). Workflow: call `search` to confirm the exact method name and parameter shapes, then call `execute` with an async function that calls `client.<resource>.<operation>(...)`. **Method paths are camelCase** (`client.clientTokens.create`, `client.pricingPreview.preview`). **Body params and response fields are snake_case** (`tax_category`, `product_id`, `unit_price`, `currency_code`). Pagination is `{ pagination: { hasMore }, data: [...] }` with `{ after: "<last_id>" }` — not `.next()` / `.hasMore`. Chain multi-step workflows inside one `execute`; variables don't persist between calls. Hard caps: 50 API calls per execute, 30s timeout, 32KB code.

Install the client library:

```bash
npm install @paddle/paddle-js
```

The Node SDK (`@paddle/paddle-node-sdk`) is for server-side work — you do not need it for checkout.

## Choose your checkout style

| Style       | When to use                                                          | What the user sees                                  |
| ----------- | -------------------------------------------------------------------- | --------------------------------------------------- |
| **Overlay** | Fastest to integrate. Opens over the current page.                   | Modal-style window that covers your page            |
| **Inline**  | When you want full control over the surrounding layout and branding. | Checkout fields render inside a `<div>` you control |

Both use the same `Paddle.Checkout.open()` call — the only difference is the `displayMode` setting and where it renders. Default to **overlay** unless you need branded inline. Overlay works without any layout changes; inline requires a target element.

## Choose your checkout variant

| Variant        | Description                                                                                                |
| -------------- | ---------------------------------------------------------------------------------------------------------- |
| **One-page**   | A single-page checkout experience with all fields (customer and payment details) on the same screen.       |
| **Multi-page** | A two-page checkout: customer details are collected on the first page, payment details on the second page. |

Multi-page is the default. One-page is recommended for most use cases.

## Overlay checkout — the minimum viable integration

This is the fastest path: a button that opens checkout for one price. Recommended.

```tsx
// app/buy/page.tsx
"use client";

import { initializePaddle, type Paddle } from "@paddle/paddle-js";
import { useEffect, useState } from "react";

export default function BuyPage() {
  const [paddle, setPaddle] = useState<Paddle | null>(null);

  useEffect(() => {
    if (
      !process.env.NEXT_PUBLIC_PADDLE_CLIENT_TOKEN ||
      !process.env.NEXT_PUBLIC_PADDLE_ENV
    ) {
      return;
    }
    initializePaddle({
      token: process.env.NEXT_PUBLIC_PADDLE_CLIENT_TOKEN,
      environment: process.env.NEXT_PUBLIC_PADDLE_ENV as
        | "sandbox"
        | "production",
    }).then((p) => p && setPaddle(p));
  }, []);

  function openCheckout() {
    paddle?.Checkout.open({
      items: [{ priceId: "pri_01h...", quantity: 1 }],
      settings: {
        variant: "one-page", // or "multi-page"
      },
    });
  }

  return (
    <button onClick={openCheckout} disabled={!paddle}>
      Buy now
    </button>
  );
}
```

That's it for overlay — no `displayMode` setting needed (overlay is the default). The user is shown the modal, completes payment, and Paddle handles the success page.

## Inline checkout with full event handling

Inline checkout is what you want when you need to render checkout next to other UI (e.g. a price summary, branded layout, custom success state). The full pattern:

```tsx
// app/checkout/[priceId]/checkout-contents.tsx
"use client";

import {
  type Environments,
  initializePaddle,
  type Paddle,
} from "@paddle/paddle-js";
import type { CheckoutEventsData } from "@paddle/paddle-js/types/checkout/events";
import throttle from "lodash.throttle";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";

interface Props {
  userEmail?: string;
}

export function CheckoutContents({ userEmail }: Props) {
  const { priceId } = useParams<{ priceId: string }>();
  const [quantity, setQuantity] = useState(1);
  const [paddle, setPaddle] = useState<Paddle | null>(null);
  const [checkoutData, setCheckoutData] = useState<CheckoutEventsData | null>(
    null,
  );

  // Throttle updateItems to avoid hammering Paddle on rapid quantity changes.
  const updateItems = useCallback(
    throttle((paddle: Paddle, priceId: string, quantity: number) => {
      paddle.Checkout.updateItems([{ priceId, quantity }]);
    }, 1000),
    [],
  );

  useEffect(() => {
    if (paddle?.Initialized) return;
    if (!process.env.NEXT_PUBLIC_PADDLE_CLIENT_TOKEN) return;

    initializePaddle({
      token: process.env.NEXT_PUBLIC_PADDLE_CLIENT_TOKEN,
      environment: process.env.NEXT_PUBLIC_PADDLE_ENV as Environments,
      eventCallback: (event) => {
        if (event.data && event.name) {
          setCheckoutData(event.data);
        }
      },
      checkout: {
        settings: {
          variant: "one-page",
          displayMode: "inline",
          theme: "dark",
          allowLogout: !userEmail,
          frameTarget: "paddle-checkout-frame",
          frameInitialHeight: 450,
          frameStyle:
            "width: 100%; background-color: transparent; border: none",
          successUrl: "/checkout/success",
        },
      },
    }).then((p) => {
      if (p && priceId) {
        setPaddle(p);
        p.Checkout.open({
          ...(userEmail && { customer: { email: userEmail } }),
          items: [{ priceId, quantity }],
        });
      }
    });
  }, [paddle?.Initialized, priceId, userEmail]);

  useEffect(() => {
    if (paddle?.Initialized && priceId) {
      updateItems(paddle, priceId, quantity);
    }
  }, [paddle, priceId, quantity, updateItems]);

  return (
    <div>
      <PriceSection
        checkoutData={checkoutData}
        quantity={quantity}
        onQuantityChange={setQuantity}
      />
      {/* The class name here MUST match `frameTarget` above. */}
      <div className="paddle-checkout-frame" />
    </div>
  );
}
```

Three things to notice:

1. **`frameTarget`** is a CSS class name (no leading dot). Whatever you set here, you must also render an element with that exact class. Paddle injects the iframe into it.
2. **`eventCallback`** fires for every event — use the `event.name` to discriminate (`checkout.loaded`, `checkout.items.updated`, `checkout.completed`, `checkout.error`, `checkout.payment-error`, etc.). The starter kit just keeps the latest event data so a sibling component can show price totals.
3. **`successUrl`** is where Paddle redirects after a successful payment. Make this a route that handles post-purchase logic (e.g. shows an order confirmation) — but **do not** rely on it for provisioning; the webhook is the source of truth.

## Customer pre-fill

If your user is already authenticated, pass their email so they don't need to type it:

```tsx
paddle.Checkout.open({
  customer: { email: "jane@example.com" },
  items: [{ priceId: "pri_01h...", quantity: 1 }],
});
```

To go further, pass an existing Paddle customer ID (created via the API or returned by a previous checkout):

```tsx
paddle.Checkout.open({
  customer: { id: "ctm_01h..." },
  items: [{ priceId: "pri_01h...", quantity: 1 }],
});
```

Setting `allowLogout: false` in the checkout settings prevents the user from signing out of their pre-filled session — useful when you've already authenticated them.

## Dynamic line items

To change quantity, swap a price, or add another item without closing the checkout, call `Paddle.Checkout.updateItems()`:

```tsx
paddle.Checkout.updateItems([
  { priceId: "pri_01h...", quantity: 3 },
  { priceId: "pri_02h...", quantity: 1 },
]);
```

Always **throttle** these calls (1 second is a good default — see the `lodash.throttle` example above). Paddle re-renders the checkout on each call, and unthrottled updates produce a flicker and rate-limit risk.

## Reading checkout state

Hook into the `eventCallback` to drive your own UI (running totals, line item breakdowns, applied discounts). The most useful events:

| Event                       | Fires when                                  | Common use                                |
| --------------------------- | ------------------------------------------- | ----------------------------------------- |
| `checkout.loaded`           | Checkout finishes initial render            | Hide a loading spinner                    |
| `checkout.items.updated`    | Line items change (incl. via `updateItems`) | Update a sibling price summary            |
| `checkout.customer.created` | New customer is created during checkout     | Capture the new `customer.id`             |
| `checkout.payment.selected` | User picks a payment method                 | Conditionally show region-specific copy   |
| `checkout.completed`        | Payment succeeds                            | Trigger a confetti animation, redirect    |
| `checkout.error`            | Something went wrong opening checkout       | Surface a fallback (mailto, support link) |
| `checkout.payment-error`    | A payment attempt failed                    | Show retry guidance — never the raw error |

Full list at [developer.paddle.com/paddle-js/events](https://developer.paddle.com/paddle-js/events.md).

## Post-checkout: redirect vs webhook

When checkout completes, two things happen in parallel:

1. The user's browser is sent to your `successUrl` (or your custom event handler).
2. Paddle fires a `transaction.completed` (and possibly `subscription.created`) webhook to your server.

**Provisioning belongs in the webhook**, not the redirect. The redirect is for UX (showing "Thanks for your order"); the webhook is the durable, retried, signed event you can trust. See `webhooks` for setup.

## Common pitfalls

- **Something went wrong message** — typically means the domain wasn't added to the approved domains list in the Paddle dashboard, or the user didn't add a default payment link in the Paddle dashboard.
- **`Paddle is not defined`** — you forgot to await `initializePaddle()` before calling `Paddle.Checkout.open()`. The promise resolves with the `Paddle` object; only then can you open checkout.
- **Checkout doesn't render inline** — your `frameTarget` class name doesn't match a rendered element, or the element isn't in the DOM yet when `Checkout.open()` runs. Render the target first; open in a `useEffect`.
- **Sandbox vs production drift** — `NEXT_PUBLIC_PADDLE_ENV` controls which environment Paddle.js talks to. If your client token is for sandbox but you set `NEXT_PUBLIC_PADDLE_ENV=production`, checkout will fail to load. Tokens and prices are environment-scoped — sandbox `pri_...` IDs don't exist in production.
- **Pasting price IDs into client code** — fine for a quick prototype, but for a real app load price IDs from the server (after fetching the catalog) so you can swap them without a redeploy.
- **Using the redirect for provisioning** — users close tabs, lose connections, or block redirects. Webhooks are the source of truth.
- **Throttling `updateItems`** — without throttling, rapid quantity changes flicker and may rate-limit. 1 second is a sensible default.
- **Calling `initializePaddle` twice** — the SDK warns and refuses on the second call. Guard with `paddle?.Initialized` (as in the example) or use a singleton pattern.

## Verify the integration

1. Run `npm run dev` and navigate to your checkout page.
2. Confirm the checkout loads or the iframe renders and the price matches what you expect.
3. Use a [Paddle sandbox test card](https://developer.paddle.com/sdks/sandbox#test-cards) — e.g. `4242 4242 4242 4242` with any future expiry and any 3-digit CVC.
4. Complete the purchase and confirm:
   - The browser lands on your `successUrl`.
   - The `checkout.completed` event fired (`console.log` from `eventCallback`).
   - In the Paddle dashboard (sandbox), the transaction shows under **Transactions**.
5. To test the webhook side, see `sandbox-testing` for the simulator.

## Related docs

- [Paddle.js overview](https://developer.paddle.com/paddle-js.md)
- [Build an overlay checkout](https://developer.paddle.com/build/checkout/build-overlay-checkout.md)
- [Build a branded inline checkout](https://developer.paddle.com/build/checkout/build-branded-inline-checkout.md)
- [Checkout events reference](https://developer.paddle.com/paddle-js/events.md)
- [`Paddle.Checkout.open` reference](https://developer.paddle.com/paddle-js/methods/paddle-checkout-open.md)
- [Default payment link & domain approval](https://developer.paddle.com/build/transactions/default-payment-link.md)
- Reference implementation: [paddle-nextjs-starter-kit](https://github.com/PaddleHQ/paddle-nextjs-starter-kit) — see `src/components/checkout/checkout-contents.tsx`.
