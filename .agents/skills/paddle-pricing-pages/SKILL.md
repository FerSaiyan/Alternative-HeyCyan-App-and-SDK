---
name: paddle-pricing-pages
description: Render country-localized prices on a Next.js pricing page using Paddle.js PricePreview — country detection, billing frequency toggle, and currency formatting.
---

# Display localized pricing in Next.js

## When to use this skill

Use this skill when building a public pricing page (or in-app upgrade screen) that needs to show prices in the user's local currency, with the right tax behavior. Covers the `Paddle.PricePreview()` API, country detection, a billing frequency toggle (monthly/yearly), the zero-decimal currency formatting gotcha (JPY/KRW/CLP), and how to keep the displayed price in sync with what checkout will charge.

This is a client-side concern. For checkout itself, see `checkout-web` — the price IDs you display here are the same ones you pass to `Paddle.Checkout.open()`.

## Why use PricePreview at all?

You _could_ hardcode "$10/month" in your UI, but comes with very strong regional pricing built-in — the same plan might be $10 in the US, €9 in the EU (with VAT), £8 in the UK, and ¥1,200 in Japan. Across countries that share a currency you can set different prices for each country to account for purchasing power parity. Hardcoding works only for one market.

`PricePreview()` returns the correct price for a given country, with currency, formatting, and applicable tax already calculated. The string it gives you is what the user will be charged — no client-side math.

If a Paddle MCP server is available to you, call `client.pricingPreview.preview({ items: [{ price_id: "pri_...", quantity: 1 }], address: { country_code: "US" }, currency_code: "USD" })` inside an `execute` to get the same data server-side — useful for verifying what users will see in different countries before wiring up the client-side hook. Note `pricingPreview` is camelCase, but `country_code` and `currency_code` are snake_case.

> The Paddle MCP exposes three tools per server (`search`, `execute`, `report_missing_tool`). Workflow: call `search` to confirm the exact method name and parameter shapes, then call `execute` with an async function that calls `client.<resource>.<operation>(...)`. **Method paths are camelCase** (`client.clientTokens.create`, `client.pricingPreview.preview`). **Body params and response fields are snake_case** (`tax_category`, `product_id`, `unit_price`, `currency_code`). Pagination is `{ pagination: { hasMore }, data: [...] }` with `{ after: "<last_id>" }` — not `.next()` / `.hasMore`. Chain multi-step workflows inside one `execute`; variables don't persist between calls. Hard caps: 50 API calls per execute, 30s timeout, 32KB code.

## Prerequisites

- Same Paddle.js setup as `checkout-web`:

```bash
npm install @paddle/paddle-js
```

```bash
NEXT_PUBLIC_PADDLE_CLIENT_TOKEN=test_...
NEXT_PUBLIC_PADDLE_ENV=sandbox
```

- One or more **prices** in your Paddle catalog, ideally with price overrides for the markets you sell to.
  - A single base price (no overrides) will be auto-converted, but explicit overrides give you control over rounding and psychological pricing (e.g. €9 not €9.13).
  - Use `catalog-setup` if you don't have prices yet — that skill includes a section on regional overrides.

## Define your tiers

A typical setup keeps tier metadata (name, features) in a constants file, with the Paddle price IDs alongside:

```ts
// constants/pricing-tier.ts
export interface Tier {
  name: string;
  id: "starter" | "pro" | "advanced";
  description: string;
  features: string[];
  featured: boolean;
  priceId: { month: string; year: string };
}

export const PricingTier: Tier[] = [
  {
    name: "Starter",
    id: "starter",
    description: "Get going.",
    features: ["1 workspace", "Limited collaboration"],
    featured: false,
    priceId: {
      month: "pri_01h...",
      year: "pri_02h...",
    },
  },
  // ...
];
```

## The PricePreview hook

A small custom hook keeps the pricing logic in one place. It takes the Paddle instance and a country code, returns a map of `priceId → formatted total`:

```ts
// hooks/usePaddlePrices.ts
import {
  type Paddle,
  type PricePreviewParams,
  type PricePreviewResponse,
} from "@paddle/paddle-js";
import { useEffect, useState } from "react";
import { PricingTier } from "@/constants/pricing-tier";

export type PaddlePrices = Record<string, string>;

function getLineItems(): PricePreviewParams["items"] {
  return PricingTier.flatMap((tier) =>
    [tier.priceId.month, tier.priceId.year].map((priceId) => ({
      priceId,
      quantity: 1,
    })),
  );
}

function getPriceAmounts(prices: PricePreviewResponse): PaddlePrices {
  return prices.data.details.lineItems.reduce<PaddlePrices>((acc, item) => {
    acc[item.price.id] = item.formattedTotals.total;
    return acc;
  }, {});
}

export function usePaddlePrices(
  paddle: Paddle | undefined,
  country: string,
): { prices: PaddlePrices; loading: boolean } {
  const [prices, setPrices] = useState<PaddlePrices>({});
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!paddle) return;

    const params: Partial<PricePreviewParams> = {
      items: getLineItems(),
      // 'OTHERS' is a sentinel meaning "let Paddle infer from IP".
      ...(country !== "OTHERS" && { address: { countryCode: country } }),
    };

    setLoading(true);
    paddle.PricePreview(params as PricePreviewParams).then((response) => {
      setPrices((prev) => ({ ...prev, ...getPriceAmounts(response) }));
      setLoading(false);
    });
  }, [country, paddle]);

  return { prices, loading };
}
```

`item.formattedTotals.total` is a fully-formatted string like `"$9.99"` or `"¥1,200"` — including the currency symbol and locale-appropriate grouping. Use it directly; don't reformat.

If you want the raw amount (for math, comparisons, custom display), `item.totals.total` gives you the integer in lowest-currency-units (cents for USD, whole units for the zero-decimal currencies — see "The currency formatting gotcha" below).

## The pricing page component

Compose the hook with Paddle.js initialization and a billing frequency toggle:

```tsx
// components/pricing.tsx
"use client";

import {
  type Environments,
  initializePaddle,
  type Paddle,
} from "@paddle/paddle-js";
import { useEffect, useState } from "react";
import { usePaddlePrices } from "@/hooks/usePaddlePrices";
import { PricingTier } from "@/constants/pricing-tier";

interface Props {
  country: string;
}

export function Pricing({ country }: Props) {
  const [frequency, setFrequency] = useState<"month" | "year">("month");
  const [paddle, setPaddle] = useState<Paddle | undefined>();

  const { prices, loading } = usePaddlePrices(paddle, country);

  useEffect(() => {
    if (!process.env.NEXT_PUBLIC_PADDLE_CLIENT_TOKEN) return;
    initializePaddle({
      token: process.env.NEXT_PUBLIC_PADDLE_CLIENT_TOKEN,
      environment: process.env.NEXT_PUBLIC_PADDLE_ENV as Environments,
    }).then((p) => p && setPaddle(p));
  }, []);

  return (
    <div>
      <FrequencyToggle value={frequency} onChange={setFrequency} />
      <div className="grid grid-cols-3 gap-6">
        {PricingTier.map((tier) => {
          const priceId = tier.priceId[frequency];
          const formatted = prices[priceId];
          return (
            <div key={tier.id}>
              <h3>{tier.name}</h3>
              <p className="text-3xl">
                {loading || !formatted ? "..." : formatted}
                <span className="text-sm">/{frequency}</span>
              </p>
              {/* ... features, CTA button ... */}
            </div>
          );
        })}
      </div>
    </div>
  );
}
```

This pattern initializes Paddle.js **without** the checkout config — you only need the client token to call `PricePreview()`. When the user clicks "Subscribe" you re-initialize with checkout settings (or use a separate page that does), as covered in `checkout-web`.

## Country detection

You have three options, in increasing order of accuracy and cost:

**A. Default to a sensible market.** US, US visitors, JS-disabled, geo-blocking — just default to `OTHERS` (Paddle infers from IP at checkout time anyway):

```tsx
<Pricing country="OTHERS" />
```

**B. Read from the request headers.** In the App Router, `headers()` in a Server Component gives you headers including geo info from your CDN (e.g. Vercel sets `x-vercel-ip-country`):

```tsx
// app/pricing/page.tsx
import { headers } from "next/headers";
import { Pricing } from "@/components/pricing";

export default async function PricingPage() {
  const h = await headers();
  const country = h.get("x-vercel-ip-country") ?? "OTHERS";
  return <Pricing country={country} />;
}
```

**C. Let the user pick.** A `<select>` with a list of supported countries, defaulting to the IP-detected one. Useful for showing prices to a global audience and letting them switch (e.g. a buyer in the US researching for a colleague in Germany). **Not recommended as users may choose the country that gives them the lowest price.**

## Country selector

```tsx
const COUNTRIES = [
  { code: "OTHERS", label: "Default (auto)" },
  { code: "US", label: "United States" },
  { code: "GB", label: "United Kingdom" },
  { code: "DE", label: "Germany" },
  { code: "JP", label: "Japan" },
  // ...
];

export function CountrySelect({
  value,
  onChange,
}: {
  value: string;
  onChange: (v: string) => void;
}) {
  return (
    <select value={value} onChange={(e) => onChange(e.target.value)}>
      {COUNTRIES.map((c) => (
        <option key={c.code} value={c.code}>
          {c.label}
        </option>
      ))}
    </select>
  );
}
```

The `usePaddlePrices` hook re-fetches when the country changes — no extra wiring needed.

## Currency unit formatting

Currencies are stored in their **lowest unit** — `1099` means $10.99, `850` means £8.50, `2500` means €25.00.

**Three currencies don't use decimals**: **JPY**, **KRW**, and **CLP**. `1200` means ¥1,200 / ₩1,200 / CLP$1,200 (not ¥12.00 etc.), since they don't have minor units. CLP technically has historical centavos but Paddle treats it as zero-decimal for everyday transactions.

This matters when you take the raw `item.totals.total` integer and convert to a number for your own UI:

```ts
// utils/parse-money.ts
export function convertAmountFromLowestUnit(
  amount: string,
  currency: string,
): number {
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

export function parseMoney(amount = "0", currency = "USD"): string {
  return formatMoney(convertAmountFromLowestUnit(amount, currency), currency);
}
```

If you're using `formattedTotals.total` from the API, you don't need this — Paddle does it for you. You only need the parsing helper when you're computing your own amounts (e.g. showing "save 20%" between monthly and yearly).

Consider using `Intl.NumberFormat` to format the amount directly, rather than using the parsing helper. See: https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Intl/NumberFormat

## Combining with checkout

When the user clicks "Subscribe" on a tier, pass the same `priceId` that was used in `PricePreview` — they'll see the exact same price in checkout:

```tsx
function handleSubscribe(tier: Tier, frequency: "month" | "year") {
  paddle?.Checkout.open({
    items: [{ priceId: tier.priceId[frequency], quantity: 1 }],
  });
}
```

Paddle re-uses the country detection at checkout, so a user who saw EUR pricing in the preview will see EUR in checkout, with VAT applied if they're in the EU.

## Common pitfalls

- **Reformatting `formattedTotals.total`.** It's already a locale-formatted string. Wrapping it in `Intl.NumberFormat` produces nonsense (`"$$9.99"`) or throws.
- **Forgetting the zero-decimal currency exception.** Dividing JPY/KRW/CLP by 100 turns the raw value into a 100x-too-small number. Always branch on currency.
- **Doing your own math on raw amounts.** This is not recommended. Paddle automatically handles calculations for you, including global tax compliance.
- **Setting `address.countryCode` to `'OTHERS'` in the API call.** `'OTHERS'` is a sentinel _your code_ uses to mean "don't pass an address" — Paddle doesn't recognize it. Drop the `address` field instead (as in the hook above).
- **Showing prices before Paddle.js initializes.** `prices[priceId]` will be `undefined` for ~200ms on page load. Handle the loading state explicitly (`'...'` placeholder, skeleton).
- **Calling `PricePreview` on every render.** The hook above re-fetches on `country` change. If you also re-fetch on `frequency` change, you're doubling work — both prices come back in a single call when you include both `priceId.month` and `priceId.year` in `items`.
- **Mixing sandbox and production price IDs.** A `pri_01h...` from sandbox doesn't exist in production. Paddle.js will throw a "price not found" error.
- **Not creating price overrides.** Without overrides, Paddle auto-converts. This is a better buyer experience than presenting a price in USD to all users, but we recommend setting explicit overrides for your top markets (US, EU, UK, JP, etc.).

## Verify the integration

1. Open your pricing page with no country prop — confirm prices appear within ~500ms.
2. Open dev tools → Network → filter for `paddle.com`. You should see one `PricePreview` request, returning a JSON payload with `formattedTotals` for each price.
3. Switch the country selector to `JP`, `KR`, or `CL` — confirm the prices update to the corresponding zero-decimal currency (JPY/KRW/CLP) with no decimal places.
4. Switch to `DE` — confirm the price includes VAT (compare to `US` which does not).
5. Click "Subscribe" on a tier — confirm the checkout opens with the same currency and amount.
6. With `NEXT_PUBLIC_PADDLE_ENV=production` (against a production token), confirm production price IDs resolve and sandbox IDs throw.

## Related docs

- [Paddle.PricePreview reference](https://developer.paddle.com/paddle-js/methods/paddle-pricepreview.md)
- [Offer localized pricing](https://developer.paddle.com/build/products/offer-localized-pricing.md)
- [Create products and prices](https://developer.paddle.com/build/products/create-products-prices.md)
- [Currencies](https://developer.paddle.com/concepts/sell/supported-currencies.md)
- Reference implementation: [paddle-nextjs-starter-kit](https://github.com/PaddleHQ/paddle-nextjs-starter-kit) — see `src/hooks/usePaddlePrices.ts`, `src/utils/paddle/parse-money.ts`, `src/components/home/pricing/pricing.tsx`.
