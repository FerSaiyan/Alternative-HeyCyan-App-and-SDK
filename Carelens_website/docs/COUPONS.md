# Coupon System

## How promo coupons work

- Coupon registry lives in `lib/business.ts` via `PROMO_COUPONS`.
- Each coupon can define:
  - `code` (e.g. `WELCOME10`)
  - `percent` (discount percentage)
  - `stripePromotionEnvVar` (optional env var holding Stripe promotion code id)

## Adding a new coupon

1. Add a new entry to `PROMO_COUPONS` in `lib/business.ts`.
2. If Stripe live mode must apply a Stripe promotion code, set `stripePromotionEnvVar` in that entry.
3. Add the matching env var to your deployment (example: `STRIPE_PROMOTION_CODE_BLACKFRIDAY`).
4. Redeploy.

## Sponsored links

- Supported URL params: `coupon`, `couponCode`, `promoCode`.
- Example:
  - `https://seu-dominio.com/?coupon=WELCOME10`
  - `https://seu-dominio.com/sub_onboarding?promoCode=EVENTO25`

## Persistence while user navigates

- `proxy.ts` captures coupon query params and stores cookie `levya_coupon_code`.
- On `/coupon`, the coupon field is prefilled from:
  1. explicit query coupon value,
  2. then cookie value.

## Apply flow behavior

- Prefill does **not** auto-apply discount.
- User confirms by pressing **Aplicar**.
- Invalid/unknown coupon falls back to full price.
