---
name: paddle-billing-history
description: Render the authenticated user's billing history in Next.js — listing transactions via the Paddle Node SDK, the mandatory customer-id filter, pagination via `.next()`/`.hasMore`, status filtering, and formatting raw transaction totals (lowest-unit conversion + Intl.NumberFormat, with the zero-decimal currency special case for JPY/KRW/CLP).
---

# Render the user's billing history in Next.js

## When to use this skill

Use this skill when adding a "Billing history" or "Invoices" section to the authenticated user's account page. The user expects to see their past transactions with dates, amounts, and the ability to download invoices. This skill covers a Next.js 15 (App Router) Server Action that lists transactions for the authenticated customer with proper pagination, status filtering, and currency display.

This is the _read_ side of transaction data. Pair it with:

- `subscription-sync` — provides the `customers` table you join on to find the user's Paddle `customer_id`.
- `webhooks` — populates the `customers` table from `customer.created` events.
- `customer-portal` — an alternative if you want Paddle to host the entire billing UI (see "Should you build this yourself?" below).

## Should you build this yourself?

The Paddle customer portal already provides a hosted billing-history view (see `customer-portal`). Build your own when:

- You want the billing surface to live inside your dashboard, with consistent navigation and styling.
- You want to combine billing data with app-specific context (e.g. usage details, custom invoice fields).
- You want to control what gets shown — the portal shows everything; a custom view can omit, group, or relabel transactions for your audience.

If none of those apply, send users to the portal and skip this skill.

## Prerequisites

- A Paddle account with at least one customer that has transactions (sandbox is fine).
- Server-side `PADDLE_API_KEY` available — this action runs in a Server Action, never in the browser.
- A `customers` table mirrored from webhooks (see `subscription-sync`). You'll look up the authenticated user's Paddle `customer_id` here via the email bridge.
- An auth system. Examples use Supabase.

```bash
NEXT_PUBLIC_PADDLE_ENV=sandbox             # or "production"
PADDLE_API_KEY=pdl_sdbx_apikey_...

NEXT_PUBLIC_SUPABASE_URL=...
NEXT_PUBLIC_SUPABASE_ANON_KEY=...
SUPABASE_SERVICE_ROLE_KEY=...              # or SUPABASE_SECRET_KEY (new opaque sb_secret_*)
```

## How `paddle.transactions.list` works

```ts
const collection = paddle.transactions.list({
  customerId: ["ctm_01h..."], // REQUIRED — without this, returns ALL transactions
  status: ["billed", "paid", "past_due", "completed", "canceled"],
  perPage: 10,
  after: undefined, // cursor for "load more"
});

const items = await collection.next(); // one page
const more = collection.hasMore; // boolean
const total = collection.estimatedTotal; // approximate count, useful for headers
```

A few things to internalize:

- **`paddle.transactions.list(...)` returns a `TransactionCollection`, not an array.** You call `.next()` to get one page. Treating the collection like an array (or assuming it's fully populated) gets you nothing or stale data.
- **The `customerId` filter is mandatory in practice.** The API doesn't _require_ it (you can list all transactions in your account without one — useful for admin views), but for a _customer-facing_ page, omitting it leaks every customer's history to whoever's logged in. Always pass it.
- **Status filter shape: `string[]`.** Pass an array of `TransactionStatus` enum values. The skill covers what to include below.
- **Pagination is cursor-based.** Each call to `.next()` advances the cursor. If you want to support "Load more," surface the cursor (`after` from the last item, or use the SDK's built-in handling) and re-issue.

## The full Server Action

```ts
// src/actions/billing-history.ts
"use server";

import { getPaddleInstance } from "@/utils/paddle/get-paddle-instance";
import { createServerInternalClient } from "@/utils/supabase/server-internal";
import { createServerClient } from "@/utils/supabase/server";

export type BillingHistoryItem = {
  id: string;
  billedAt: string | null;
  status: string;
  total: string;
  invoiceUrl?: string;
};

export async function getBillingHistory(after?: string) {
  // 1. Authenticate. Reject anonymous requests before any DB or SDK call.
  const supabase = await createServerClient();
  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user || !user.email) {
    return { error: "Not authenticated" };
  }

  // 2. Look up the authenticated user's Paddle customer_id via the email
  //    bridge. If they have no Paddle customer record yet, return an empty
  //    result — no error, just nothing to show.
  const internal = createServerInternalClient();
  const { data: customerRow } = await internal
    .from("customers")
    .select("customer_id")
    .eq("email", user.email)
    .single();

  if (!customerRow?.customer_id) {
    return { items: [], hasMore: false, total: 0 };
  }

  // 3. List transactions, scoped to the authenticated customer. The customerId
  //    filter is the security guarantee — without it, the SDK returns every
  //    customer's transactions in your account.
  const paddle = getPaddleInstance();
  const collection = paddle.transactions.list({
    customerId: [customerRow.customer_id],
    status: ["billed", "paid", "past_due", "completed", "canceled"],
    perPage: 10,
    after,
  });

  // 4. Fetch one page. Don't loop over hasMore — the UI will call this again
  //    with a cursor for "Load more."
  const transactions = (await collection.next()) ?? [];

  // 5. Slim each transaction down to what the UI actually renders. The
  //    Transaction entity exposes `details.totals.total` as a string in
  //    lowest currency units (cents for USD, whole units for the
  //    zero-decimal currencies JPY/KRW/CLP) plus
  //    a `currencyCode` — there is no pre-formatted string, so format here
  //    via the parseMoney helper (see "Display: format the raw amount" below).
  const items: BillingHistoryItem[] = transactions.map((t) => ({
    id: t.id,
    billedAt: t.billedAt ?? null,
    status: t.status,
    total: parseMoney(t.details?.totals?.total, t.currencyCode),
  }));

  return {
    items,
    hasMore: collection.hasMore,
    total: collection.estimatedTotal,
  };
}
```

## Status filter — what to include

`paddle.transactions.list` accepts a `status: TransactionStatus[]` filter. The values you'll typically pass for a billing-history view:

| Status      | Include in billing history? | Why                                                                                                                          |
| ----------- | --------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| `billed`    | Yes                         | Customer has been charged; they can see this.                                                                                |
| `paid`      | Yes                         | Successfully charged.                                                                                                        |
| `past_due`  | Yes                         | Failed charge that's being retried — the user should see "we tried to bill you and it failed" so they can update their card. |
| `completed` | Yes                         | Closed transaction (delivered + paid).                                                                                       |
| `canceled`  | Yes                         | Customer-relevant: a transaction that was created but won't be billed.                                                       |
| `draft`     | **No**                      | In-flight, not yet attempted. Internal state.                                                                                |
| `ready`     | **No**                      | Ready-to-charge but not yet sent. Internal state.                                                                            |

Excluding `draft` and `ready` is the important rule — they represent partial state that the customer shouldn't see.

If you omit the `status` filter entirely, the API returns all statuses including `draft` / `ready`. Don't.

## Display: format the raw amount yourself

Unlike Paddle.js's `PricePreview` (which exposes a pre-formatted string in `formattedTotals.total`), the **`Transaction` entity from the Node SDK does not** — only the raw amount in lowest currency units plus a currency code:

```ts
transaction.details?.totals?.total; // "3000"  (string, lowest unit)
transaction.currencyCode; // "USD"
```

You need to (a) convert from lowest unit to a number and (b) format it as currency. Two steps because step (a) has a zero-decimal-currency special case (JPY/KRW/CLP) the formatter doesn't know about.

```ts
// utils/parse-money.ts — match the canonical paddle-nextjs-starter-kit shape

export function convertAmountFromLowestUnit(
  amount: string,
  currency: string,
): number {
  // JPY, KRW, and CLP have no minor units — "1200" means ¥1,200 / ₩1,200 /
  // CLP$1,200, not ¥12.00 etc. For every other currency, divide by 100 to
  // convert cents to the base unit.
  switch (currency) {
    case "JPY":
    case "KRW":
    case "CLP":
      return parseFloat(amount);
    default:
      return parseFloat(amount) / 100;
  }
}

export function formatMoney(amount: number, currency: string): string {
  const language =
    typeof navigator !== "undefined" ? navigator.language : "en-US";
  return new Intl.NumberFormat(language, {
    style: "currency",
    currency,
  }).format(amount);
}

export function parseMoney(
  amount: string = "0",
  currency: string = "USD",
): string {
  return formatMoney(convertAmountFromLowestUnit(amount, currency), currency);
}
```

Then in your DTO mapping:

```ts
total: parseMoney(t.details?.totals?.total, t.currencyCode);
```

The output is a locale-aware string like `"$30.00"`, `"€29,99"`, or `"¥1,200"` — currency symbol, separators, and decimal handling all sorted by `Intl.NumberFormat`. Tax is already included in `details.totals.total` (Paddle calculates the totals — you don't need to add tax yourself; just convert + format).

### Common formatting traps

- **Skipping the zero-decimal currency branch.** Dividing JPY/KRW/CLP by 100 turns ¥1,200 into ¥12. The three currencies Paddle marks as zero-decimal are JPY, KRW, and CLP — always branch on currency before dividing.
- **Summing line items manually instead of using the precomputed total.** Paddle has already calculated `details.totals.total` including discounts, credits, tax, and currency conversion. Re-summing `lineItems` with your own tax math will diverge from the actual charge.
- **Hardcoding the locale to `'en-US'` everywhere.** Reading `navigator.language` (in the browser) or accepting a locale prop gives users their native formatting. The starter kit falls back to `'en-US'` when `navigator` is undefined (server side) — fine as a default, but better if you can pass the user's locale through.
- **Confusing the Transaction shape with the PricePreview shape.** `Paddle.PricePreview()` (client-side, for pricing pages) returns `formattedTotals.total` already formatted — see `pricing-pages`. `Transaction` does not. Use the right pattern for each.

## Pagination — cursor pattern

The `.list(...)` collection uses cursor-based pagination. `after` is a transaction ID to start _after_. To support "Load more":

```tsx
// Client component
"use client";

import { useState } from "react";
import { getBillingHistory } from "@/actions/billing-history";

export function BillingHistory({ initialItems, initialHasMore }: Props) {
  const [items, setItems] = useState(initialItems);
  const [hasMore, setHasMore] = useState(initialHasMore);

  async function loadMore() {
    const lastId = items[items.length - 1]?.id;
    const result = await getBillingHistory(lastId);
    if ("error" in result) return;
    setItems((prev) => [...prev, ...result.items]);
    setHasMore(result.hasMore);
  }

  return (
    <>
      <ul>{items.map(/* render */)}</ul>
      {hasMore && <button onClick={loadMore}>Load more</button>}
    </>
  );
}
```

Don't loop `while (collection.hasMore)` server-side — it'll fetch the customer's entire transaction history into memory and might rate-limit you. Pagination is a _user-driven_ operation.

## Common pitfalls

- **Calling `paddle.transactions.list({})` without a `customerId` filter** in a customer-facing action. Returns transactions for every customer in your account. The filter is the security guarantee.
- **Trusting a `customerId` from the action input.** As with the cancel/update/portal actions, the `customerId` must be resolved server-side from the authenticated user's record. Never accept it as a parameter.
- **Treating the `TransactionCollection` as an array.** It's an iterator that fetches lazily via `.next()`. `collection.length` is undefined; `collection.map(...)` doesn't exist.
- **Eager-loading all pages.** A `while (collection.hasMore) { items.push(...await collection.next()) }` loop sounds reasonable but produces unbounded responses. For a customer with hundreds of transactions, this hits memory limits and Paddle rate limits.
- **Forgetting the zero-decimal currency branch when converting amounts.** `parseFloat(amount) / 100` is correct for USD/EUR/GBP/etc. but turns ¥1,200 / ₩1,200 / CLP$1,200 into a 100x-too-small number (because those three currencies have no minor units; the raw value is already the whole-unit amount). The currencies Paddle currently treats as zero-decimal are **JPY**, **KRW**, and **CLP**. Always switch on currency before dividing — see the `convertAmountFromLowestUnit` helper above.
- **Summing line items manually instead of using `details.totals.total`.** Paddle's totals already account for line-item subtotals, discounts, credits, and tax. Re-summing yourself with your own tax math will diverge from the actual charge amount and confuse customers comparing the UI to their bank statements.
- **Reaching for `formattedTotals` on a Transaction.** That field exists on Paddle.js `PricePreview` results (the pricing-page world), but not on the Node SDK's `Transaction` entity. Reading `t.details.totals.formattedTotals.total` returns `undefined`. Use `details.totals.total` + `currencyCode` and format yourself.
- **Including `draft` or `ready` in the status filter.** Those are internal in-flight states, not customer-facing data. Exclude them.
- **Returning the raw `Transaction` SDK object.** It includes line items, customer addresses, business records, and a lot of internal Paddle metadata. Slim to a DTO with what your UI actually needs (id, date, status, formatted total, optionally an invoice link).
- **Going beyond `parseFloat` + `Intl.NumberFormat` with custom math on amounts.** A `parseFloat(amount) / 100` is the canonical lowest-unit conversion (with the JPY/KRW/CLP branch above). What you should NOT do is layer additional rounding (`Math.round`, `Math.floor`, `Math.ceil`, `toFixed`) or recalculate the total — those introduce off-by-one and currency-rounding bugs. `Intl.NumberFormat` already handles locale-appropriate decimal places. Compute as little as possible; let Paddle's totals and Intl's formatter do the work.

## Verify the integration

1. Sign in as a user with at least one completed sandbox transaction.
2. Call `getBillingHistory()`. You should get `{ items: [...], hasMore: false (or true), total: <n> }`.
3. Verify the formatted total in the response matches what's shown in the Paddle dashboard for that transaction (currency, amount, tax handling).
4. Sign in as a user who has no Paddle customer record yet. The action should return `{ items: [], hasMore: false, total: 0 }` without error.
5. Try the action while logged out. Should return `{ error: 'Not authenticated' }`.
6. **Critical:** create a sandbox transaction belonging to a _different_ customer (or use the dashboard to inspect another customer's transactions). Confirm those transactions do NOT appear in the response — the `customerId` filter must scope correctly.
7. If your account has more than 10 transactions, confirm `hasMore: true` and that calling `getBillingHistory(lastId)` advances the cursor.

## Related docs

- [List transactions - API reference](https://developer.paddle.com/api-reference/transactions/list-transactions.md)
- [Get an invoice PDF](https://developer.paddle.com/api-reference/transactions/get-invoice-pdf.md) — for adding download links to your DTO
- Reference implementation: [paddle-nextjs-starter-kit](https://github.com/PaddleHQ/paddle-nextjs-starter-kit) — see `src/utils/paddle/get-transactions.ts` and `src/app/dashboard/payments/[subscriptionId]/`.
