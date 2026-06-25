# Security Draft

## Secrets management
- Keep secrets only in deployment secret manager and local `.env`.
- Never commit real credentials.

## Stripe handling
- Verify webhook signature using `STRIPE_WEBHOOK_SECRET`.
- Make event processing idempotent using event IDs.
- Persist payment and refund audit trails.
- In `STRIPE_MODE=live`, reject unsigned or invalid webhook payloads.
- Keep `STRIPE_MODE=draft` as local default to avoid live billing calls.
- Never store raw card PAN/CVC/expiry in app database or logs.
- Use Stripe-hosted tokenization via `PaymentElement` and `ExpressCheckoutElement`.
- Store only safe references for future purchases (`stripeCustomerId`, `stripeDefaultPaymentMethodId`).

## Access control
- Consumer access limited to own account.
- Doctor and pharmacy roles segmented.
- Admin actions logged with actor and timestamp.

## Data handling
- Minimize sensitive health data exposure in UI.
- Redact personal data in logs and error traces.
- Review LGPD requirements before production launch.
