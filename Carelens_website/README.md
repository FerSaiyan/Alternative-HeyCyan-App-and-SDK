# Levya Web

Website and product draft for Levya, connecting patients, partner doctors, and partner compounding pharmacies.

## Stack
- Next.js 16 (App Router)
- TypeScript
- Tailwind CSS v4

## Local setup
1. Install dependencies:

```bash
npm install
```

2. Copy env example and set values:

```bash
cp .env.example .env
```

3. Run app:

```bash
npm run dev
```

## Important paths
- `app/(marketing)/page.tsx` landing page
- `app/(marketing)/sub_onboarding/page.tsx` short onboarding
- `app/single_purchase_onboarding/page.tsx` single purchase onboarding draft
- `app/payment/page.tsx` embedded payment step (card + wallets)
- `app/signin/page.tsx` sign-in entry
- `app/signin/check-email/page.tsx` magic-link confirmation
- `app/logout/page.tsx` sign-out confirmation
- `app/success/page.tsx` post-payment confirmation
- `app/schedule/page.tsx` scheduling draft
- `app/support/page.tsx` support center draft
- `app/api/` backend draft endpoints
- `docs/` product, policy, and operations drafts
- `docs/PAYMENTS_EMBEDDED.md` embedded Stripe payments architecture
- `docs/COUPONS.md` promo coupon registry and sponsored links

## Scheduling draft behavior
- Current provider target: Google Calendar.
- Set `GOOGLE_CALENDAR_ID` and `GOOGLE_CALENDAR_API_KEY` in `.env`.
- To create slots automatically from admin, also set `GOOGLE_CALENDAR_REFRESH_TOKEN`.
- Optional override vars for calendar write flow: `GOOGLE_CALENDAR_OAUTH_CLIENT_ID` and `GOOGLE_CALENDAR_OAUTH_CLIENT_SECRET`.
- Add availability events in that calendar with summary starting with `LEVYA_SLOT`.
- Slot booking endpoint: `POST /api/schedule/book` (draft persistence via Prisma + `levya_user_id` cookie).
- Booking/cancel endpoints redirect back to `/schedule` with status query flags (`booked`, `canceled`, `error`) for in-page feedback.
- Slot lock TTL can be tuned with `BOOKING_SLOT_LOCK_MINUTES`.
- Admin slot generation endpoint: `POST /api/admin/schedule/generate` (requires `ADMIN` session).
- Magic-link endpoints: `POST /api/auth/request-link` and `GET /api/auth/verify`.
- Password auth endpoints: `POST /api/auth/password/signup` and `POST /api/auth/password/signin`.
- Sign-out endpoint: `POST /api/auth/signout`.

## Payments and auth delivery
- `STRIPE_MODE=draft` keeps payment in mock mode and redirects to `/success?mock=1`.
- `STRIPE_MODE=live` enables embedded Stripe Elements flow:
  - `POST /api/checkout/session` stores form data and redirects to `/payment`
  - `POST /api/payments/intent` creates `PaymentIntent` (compra única) or incomplete `Subscription` invoice `PaymentIntent` (assinatura)
  - `app/payment/page.tsx` renders `ExpressCheckoutElement` (Apple Pay / Google Pay when available) and `PaymentElement` (card)
  - `app/api/webhooks/stripe/route.ts` reconciles payment status and subscription state
- For recurring billing and future one-click charges, we store only Stripe identifiers (`stripeCustomerId`, `stripeDefaultPaymentMethodId`), never raw card data.
- `MAGIC_LINK_MODE=draft` keeps auth link delivery in preview mode for local/CI.
- `MAGIC_LINK_MODE=live` enables SMTP delivery using `SMTP_HOST`, `SMTP_USER`, and `SMTP_PASS`.
- Google OAuth endpoints: `GET /api/auth/google/start` and `GET /api/auth/google/callback`.
- To enable Google sign-in, set `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` and register callback URL:
  - `https://<your-domain>/api/auth/google/callback`
- Consumer flow: user signs in first (`/signin`) using Google or e-mail/senha, then continues to onboarding routes.

## Commands
- `npm run lint`
- `npm run build`
- `npm run db:push`
- `npm run seed:roles`
- `npm run test:e2e:smoke`
- `npm run test:e2e`

## Partner/admin test accounts
- Run `npm run seed:roles` to create local role users:
  - `admin@levya.local` (`ADMIN`)
  - `doctor@levya.local` (`DOCTOR`)
  - `pharmacy@levya.local` (`PHARMACY`)
- Then request a magic link on `/signin` with one of these emails.
- In draft auth mode (`MAGIC_LINK_MODE=draft`), use the preview link on `/signin/check-email`.

## Playwright agent checks
- Install browser once: `npx playwright install chromium`
- Playwright config: `playwright.config.ts`
- Smoke spec: `tests/e2e/smoke.spec.ts`
- Full protocol for AI agents: `docs/AGENT_PLAYWRIGHT_PROTOCOL.md`
- Server + Funnel + Playwright troubleshooting: `docs/DEV_SERVER_FUNNEL_PLAYWRIGHT.md`

## Stable Funnel serving (quick reference)
- Prefer production server for Funnel checks:

```bash
npm run build
nohup npm run start -- --port 3010 --hostname 127.0.0.1 > /tmp/levya-start.log 2>&1 &
```

- Verify server and Funnel mapping:

```bash
pgrep -af "next start --port 3010|next-server"
tailscale funnel status
curl -I http://127.0.0.1:3010
curl -I https://<machine>.tail*.ts.net/coupon?purchaseType=subscription
```

## Governance
- Agent contract: `AGENTS.md`
- Repository process: `REPO_RULES.md`
- Draft business rules: `docs/BUSINESS_RULES.md`
