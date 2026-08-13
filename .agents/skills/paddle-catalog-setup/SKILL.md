---
name: paddle-catalog-setup
description: Create the Paddle products and prices that other Paddle skills depend on — try MCP tools first, fall back to a Node SDK seed script, or dictate dashboard steps as a last resort.
---

# Create Paddle products and prices

## When to use this skill

The user is starting a Paddle integration and their account doesn't yet have the products and prices that other Paddle skills (`checkout-web`, `pricing-pages`, `sandbox-testing`, `webhooks`) depend on. Use this skill to create them.

This is typically a one-time setup per project. Once the catalog exists, you don't revisit this skill — only when the user wants to add new products or prices.

## What you're modeling — confirm with the user first

Before creating anything, agree on these with the user. **Do not guess.**

- **Products vs prices.**
  - A product is what's sold ("Pro plan", "Lifetime license").
  - A price is how it's charged ($10/month, $96/year, $999 one-off).
  - One product can have many prices — typically one per billing interval.
  - Prices can have price overrides for different markets.
- **Tax category** (per product). Pick from the table below. Default to `saas` for software-as-a-service or `standard` for everything else, and surface the choice after creating for the user to confirm. Users must get approval for other tax categories in the dashboard.
- **Billing model** (per price). Recurring (subscription) requires a billing cycle (e.g. `month` / `year`) — `billing_cycle` in MCP body params, `billingCycle` in the SDK; one-time prices omit it. Trial periods are subscription-only — the API rejects them on one-time prices.
- **Currency and amounts.** The unit price amount (`unit_price.amount` in MCP, `unitPrice.amount` in the SDK) is a string in lowest currency units. Cents for USD/EUR/GBP (`"1000"` = $10.00). **Whole units for the zero-decimal currencies — JPY, KRW, and CLP** (`"1200"` = ¥1,200 / ₩1,200 / CLP$1,200, not ¥12 etc.). CLP has historical centavos but Paddle treats it as zero-decimal for everyday transactions.
- **Environment.** Default to **sandbox** during development. Sandbox and production are completely separate — `pro_...` and `pri_...` IDs from one don't exist in the other. Ask the user before targeting production.

IDs look like `pro_01h...` (products) and `pri_01h...` (prices). You'll need the price IDs to wire into the user's app, so capture them after creation.

### Tax categories

| Category                  | Use for                                                   |
| ------------------------- | --------------------------------------------------------- |
| `saas`                    | SaaS subscriptions, web apps, platform-as-a-service       |
| `digital-goods`           | Downloadable software, apps, themes, plugins, asset packs |
| `ebooks`                  | Ebooks, magazines, paid newsletters                       |
| `standard`                | Anything else (default tax treatment per jurisdiction)    |
| `website-hosting`         | Hosting plans, domain registration                        |
| `professional-services`   | Consulting, custom dev, advisory work                     |
| `training-services`       | Online courses, workshops, certification programs         |
| `implementation-services` | Setup, integration, migration services                    |

## Choose your method

Pick the highest-ranked method that's available to you:

1. **Paddle MCP server** — if `search` and `execute` tools from a `paddle-sandbox` (or `paddle-live`) MCP server are in your toolset, use them. Fastest, no code for the user to run, results immediate. **Always prefer this.**
2. **Node SDK seed script** — if MCP isn't available but the user has a `PADDLE_API_KEY` (or can generate one), generate a seed script for the user to run. Repeatable, version-controlled.
3. **Dashboard (manual)** — if neither is workable, dictate click-by-click steps for the user to follow.

If a method fails midway (e.g. SDK call returns `forbidden` because the API key lacks permissions), fall back to the next method rather than retrying.

If the user doesn't have a Paddle MCP server installed, surface it as a suggestion — point them at the [Paddle MCP server install guide](https://developer.paddle.com/sdks/ai/paddle-mcp.md).

## Method 1: Paddle MCP server

Check whether `search` and `execute` tools from `paddle-sandbox` (or `paddle-live`) are available to you. If yes, use them.

> The Paddle MCP exposes three tools per server (`search`, `execute`, `report_missing_tool`). Workflow: call `search` to confirm the exact method name and parameter shapes, then call `execute` with an async function that calls `client.<resource>.<operation>(...)`. **Method paths are camelCase** (`client.clientTokens.create`, `client.pricingPreview.preview`). **Body params and response fields are snake_case** (`tax_category`, `product_id`, `unit_price`, `currency_code`). Pagination is `{ pagination: { hasMore }, data: [...] }` with `{ after: "<last_id>" }` — not `.next()` / `.hasMore`. Chain multi-step workflows inside one `execute`; variables don't persist between calls. Hard caps: 50 API calls per execute, 30s timeout, 32KB code.

Default to `paddle-sandbox` unless the user has explicitly asked to target live.

1. Confirm the product details with the user (name, tax category, prices to create).
2. Call `search` with a query like `"products create"` and `"prices create"` to confirm the current method names and parameter shapes.
3. Call `execute` with one async function that creates the product and all its prices in a single call. Chaining inside one `execute` avoids round-trips and keeps related operations atomic.
4. Report all created IDs back to the user — they'll need to paste them into their app.

Example `execute` payload — one product, monthly + yearly prices, monthly with a trial:

```js
async (client) => {
  const product = await client.products.create({
    name: "Pro",
    tax_category: "saas",
    description: "For scaling teams.",
  });
  const monthly = await client.prices.create({
    product_id: product.id,
    description: "Pro monthly USD",
    unit_price: { amount: "1000", currency_code: "USD" },
    billing_cycle: { interval: "month", frequency: 1 },
    trial_period: { interval: "day", frequency: 14 },
  });
  const yearly = await client.prices.create({
    product_id: product.id,
    description: "Pro yearly USD",
    unit_price: { amount: "9600", currency_code: "USD" },
    billing_cycle: { interval: "year", frequency: 1 },
  });
  return {
    product_id: product.id,
    monthly_id: monthly.id,
    yearly_id: yearly.id,
  };
};
```

For a one-time price (lifetime license, etc.), drop `billing_cycle` and `trial_period`:

```js
const lifetime = await client.prices.create({
  product_id: product.id,
  description: "Pro lifetime",
  unit_price: { amount: "29900", currency_code: "USD" },
});
```

For regional pricing, add `unit_price_overrides` — all snake_case, including the inner field names:

```js
unit_price_overrides: [
  {
    country_codes: ["DE", "FR", "IT", "ES", "NL"],
    unit_price: { amount: "900", currency_code: "EUR" },
  },
  {
    country_codes: ["GB"],
    unit_price: { amount: "800", currency_code: "GBP" },
  },
  {
    country_codes: ["JP"],
    unit_price: { amount: "1200", currency_code: "JPY" },
  },
];
```

If the MCP `search` / `execute` tools aren't available, fall back to Method 2.

## Method 2: Node SDK seed script

Use this when the user has (or can install) `@paddle/paddle-node-sdk` and a `PADDLE_API_KEY`. You write the script; the user runs it.

1. Confirm the user has `PADDLE_API_KEY` set in their environment. If not, point them to **Paddle > Developer tools > Authentication** in the [sandbox dashboard](https://sandbox-vendors.paddle.com/authentication-v2) and ask them to create one with at minimum the `product.write` and `price.write` permission scopes.
2. Add `@paddle/paddle-node-sdk` to the project (`npm install @paddle/paddle-node-sdk`), if not present.
3. Write a seed script and ask the user to run it once.
4. Capture the printed IDs from the output and use them to update the user's app config.

Template:

```ts
// scripts/seed-paddle-catalog.ts
import { Environment, Paddle } from "@paddle/paddle-node-sdk";

const paddle = new Paddle(process.env.PADDLE_API_KEY!, {
  environment: Environment.sandbox, // change to .production for live
});

async function seed() {
  const pro = await paddle.products.create({
    name: "Pro",
    taxCategory: "saas",
    description: "For scaling teams.",
  });

  const monthly = await paddle.prices.create({
    productId: pro.id,
    description: "Pro monthly USD",
    unitPrice: { amount: "1000", currencyCode: "USD" }, // 1000 cents = $10.00
    billingCycle: { interval: "month", frequency: 1 },
    trialPeriod: { interval: "day", frequency: 14 },
  });

  const yearly = await paddle.prices.create({
    productId: pro.id,
    description: "Pro yearly USD",
    unitPrice: { amount: "9600", currencyCode: "USD" },
    billingCycle: { interval: "year", frequency: 1 },
  });

  console.log(
    JSON.stringify(
      { productId: pro.id, monthlyId: monthly.id, yearlyId: yearly.id },
      null,
      2,
    ),
  );
}

seed().catch((e) => {
  console.error(e);
  process.exit(1);
});
```

Run it: `npx tsx scripts/seed-paddle-catalog.ts`.

**One-time price** (lifetime license, etc.) — drop `billingCycle` and `trialPeriod`:

```ts
await paddle.prices.create({
  productId: pro.id,
  description: "Pro lifetime",
  unitPrice: { amount: "29900", currencyCode: "USD" },
});
```

**Regional overrides** — add `unitPriceOverrides`:

```ts
unitPriceOverrides: [
  { countryCodes: ["DE", "FR", "IT", "ES", "NL"], unitPrice: { amount: "900", currencyCode: "EUR" } },
  { countryCodes: ["GB"], unitPrice: { amount: "800", currencyCode: "GBP" } },
  { countryCodes: ["JP"], unitPrice: { amount: "1200", currencyCode: "JPY" } },
],
```

If the script fails with `forbidden` or `unauthorized`, the API key lacks the required permission scopes — ask the user to regenerate it with `product.write` and `price.write`. If it fails because the user can't or won't install the SDK, fall back to Method 3.

## Method 3: Dashboard (manual)

Last resort. Use when MCP and SDK paths are both blocked. Dictate the steps for the user to follow, then ask them to share the IDs.

Tell the user:

1. Sign in to the [sandbox dashboard](https://sandbox-vendors.paddle.com/products) (or [live](https://vendors.paddle.com/products) if going to production).
2. Go to **Paddle > Catalog > Products** and click **New product**. Fill in:
   - **Name** — e.g. "Pro" (this is what users see in checkout).
   - **Tax category** — `<the value you agreed on above>`. **Cannot be changed after a sale.**
   - Description, icon — optional.
3. Save the product.
4. Inside the new product, click **New price**. Fill in:
   - **Description** — internal label (e.g. "Pro monthly USD").
   - **Type** — Recurring (subscription) or One-time.
   - **Billing cycle** (recurring only) — Monthly, Yearly, Weekly, Daily, or Custom.
   - **Trial period** (recurring only) — optional.
   - **Amount** — amount + currency (e.g. `10.00 USD`).
   - **Regional prices** — optional overrides per market.
5. Save the price. Repeat for each price needed.
6. After all prices are created, copy the `pri_...` IDs from the price details panel and paste them back to me.

After the user shares the IDs, update their app config (constants file, env vars, or wherever you've decided price IDs live).

## Verify

Whichever method you used, before declaring this skill complete:

1. **Confirm the products and prices exist.** Via MCP — one `execute` call: `client.products.list({ include: ["prices"] })`. Via SDK: `paddle.products.list({ include: "prices" })`. Or ask the user to check **Paddle > Catalog > Products** in the dashboard.
2. **Confirm the IDs you reported match.** Via MCP: `client.products.get(productId, { include: ["prices"] })` for each new product (note `productId` is a positional path param). Via SDK: `paddle.products.get(id)`. Check name + tax category.
3. **Suggest the next step.** Recommend the user complete `checkout-web` next, using one of the new price IDs, to confirm the catalog actually works in checkout.

## Common pitfalls

- **Creating in the wrong environment.** Sandbox and production have completely separate catalogs. With the remote MCP, the server name determines the environment — `paddle-sandbox` for sandbox, `paddle-live` for production. `paddle-sandbox` authenticates with a `pdl_sdbx_...` API key; `paddle-live` authorizes with OAuth in the browser (or a live API key). With the SDK, `Environment.sandbox` vs `Environment.production`. Always confirm before creating.
- **Confusing amount units.** `unit_price.amount` (MCP) / `unitPrice.amount` (SDK) is a string in lowest currency units. `"1000"` is $10.00, **not** $1000. The zero-decimal currencies (JPY, KRW, CLP) are whole units — `"1200"` is ¥1,200 / ₩1,200 / CLP$1,200, not ¥12 etc.
- **Forgetting the product ID on a price.** `client.prices.create()` (MCP) and `paddle.prices.create()` (SDK) both require the parent product's ID — `product_id` in MCP body params, `productId` in SDK. Create the product first, capture its `id`, then pass to each price.
- **Trial period on a one-time price.** Not allowed — trials are subscription-only. The API rejects it. Drop `trial_period` (MCP) / `trialPeriod` (SDK) if there's no billing cycle.
- **Recurring price with no billing cycle.** Without `billing_cycle` (MCP) / `billingCycle` (SDK), the price is created as one-time. Always set `{ interval: "month", frequency: 1 }` (or similar) for subscriptions.
- **Mixing camelCase and snake_case in MCP `execute` code.** Method paths are camelCase (`client.clientTokens.create`, `client.pricingPreview.preview`), but body params and response fields are snake_case (`tax_category`, `product_id`, `unit_price`, `currency_code`). The SDK is camelCase end-to-end — don't carry that convention into `execute` code, and don't carry the snake_case convention back into SDK code.
- **Splitting dependent calls across multiple `execute` invocations.** Variables don't persist between `execute` calls, so passing IDs requires returning them and re-passing on the next call. Chain dependent operations (product → prices → overrides) inside one async function and return the IDs at the end.
- **Using regional overrides without a sensible base price.** Customers in countries you didn't override see the base price auto-converted. Pick a base currency that's a reasonable fallback.
- **Missing write permissions.** The remote MCP inherits permissions from whatever it authenticated with. If `client.products.create()` returns `forbidden` on `paddle-sandbox`, the API key lacks `product.write` / `price.write` — ask the user to regenerate it from **Paddle > Developer tools > Authentication** with those scopes added. On `paddle-live` connected with OAuth, the connection starts read-only, so a write returning `forbidden` is expected until the user grants write permissions under **Paddle > Connectors > MCP**. Permissions there are capped by their Paddle user's role.
- **Pasting IDs into the user's code without telling them.** Always report new IDs back; let the user (or you, with their approval) commit the change explicitly.

## Related docs

- [Create a product](https://developer.paddle.com/build/products/create-update-products.md)
- [Set localized prices](https://developer.paddle.com/build/products/offer-localized-pricing.md)
- [Products API reference](https://developer.paddle.com/api-reference/products/overview.md)
- [Prices API reference](https://developer.paddle.com/api-reference/prices/overview.md)
- [Paddle MCP server install guide](https://developer.paddle.com/sdks/ai/paddle-mcp.md)
