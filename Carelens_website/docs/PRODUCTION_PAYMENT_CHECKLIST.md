# Production Payment Checklist

Use this checklist before going live with payments.

---

## Provider Selection

Set `NEXT_PUBLIC_PAYMENT_PROVIDER` to choose the active payment provider:

| Value    | Provider | Payment methods | Default |
|----------|----------|-----------------|---------|
| `stripe` | Stripe (credit card / Google Pay / Apple Pay) | Credit card, digital wallets | ✅ |
| `asaas`  | Asaas | PIX, Boleto, Cartão de Crédito (parcelado até 12x), Assinatura recorrente | |

Depending on the provider, follow the relevant section below.

---

## A) Stripe

### 1. Stripe Account Setup

- [ ] Stripe account is in **Live Mode** (not Test Mode)
- [ ] Business information completed in Stripe Dashboard
- [ ] Bank account connected for payouts (if applicable)

### 2. Required Environment Variables

```bash
# Core Stripe
STRIPE_MODE=live
STRIPE_SECRET_KEY=sk_live_...
NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY=pk_live_...

# Price IDs (from Stripe Dashboard)
STRIPE_PRICE_MONTHLY_BRL_1700=price_...     # Monthly subscription price
STRIPE_PRICE_ONE_TIME_BRL=price_...         # One-time purchase price

# Promo codes
STRIPE_PROMOTION_CODE_WELCOME10=promo_...   # Welcome discount (optional)

# Webhook security
STRIPE_WEBHOOK_SECRET=whsec_...              # From Stripe Dashboard > Webhooks
```

### 3. Domain Verification (Apple Pay & Google Pay)

#### Apple Pay
- [ ] Register your domain in **Stripe Dashboard > Apple Pay > Domain**
- [ ] Domain must be publicly accessible with HTTPS

#### Google Pay
- [ ] Google Pay is enabled in Stripe Dashboard (usually automatic with Apple Pay)

### 4. Webhook Endpoint

- [ ] Webhook URL: `https://yourdomain.com/api/webhooks/stripe`
- [ ] Webhook signing secret (`STRIPE_WEBHOOK_SECRET`) is set
- [ ] Idempotency working (duplicate events ignored)

---

## B) Asaas

### 1. Asaas Account Setup

- [ ] Asaas account is created (sandbox for testing, production for live)
- [ ] API key generated in Asaas Dashboard > Integrações > API
- [ ] Webhook configured in Asaas Dashboard for payment and subscription status updates
- [ ] Webhook token set in Asaas Dashboard and as `ASAAS_WEBHOOK_TOKEN` env var

### 2. Required Environment Variables

```bash
NEXT_PUBLIC_PAYMENT_PROVIDER=asaas
ASAAS_API_KEY=$aak_live_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
# ASAAS_API_BASE_URL=https://api.asaas.com/api/v3  # production
ASAAS_WEBHOOK_TOKEN=your_asaas_webhook_token
```

If the key starts with `$` and is stored in `.env`, save it as `\$...` so Next.js does not strip it during env interpolation.

Sandbox defaults:
```bash
ASAAS_API_BASE_URL=https://sandbox.asaas.com/api/v3
```

### 3. Payment Flow Verification

#### PIX
- [ ] PIX QR code renders correctly on mobile and desktop
- [ ] Copy-paste PIX code is copyable
- [ ] "Já realizei o pagamento" button redirects to success page

#### Boleto
- [ ] "Visualizar Boleto" link opens the boleto PDF
- [ ] Linha Digitável is displayed and copyable
- [ ] "Já realizei o pagamento" button redirects to success page

#### Cartão de Crédito
- [ ] Installment selector (2-12x) is available for one-time purchases
- [ ] "Pagar com Cartão de Crédito" link redirects to Asaas hosted checkout
- [ ] Card data is entered in Asaas secure environment (no raw PAN/CVV in our system)

#### Assinatura (Recorrência)
- [ ] Subscription creation returns subscription ID and status
- [ ] Hosted checkout page remains open and can return to CyanBridge after card entry
- [ ] Subscription events from webhooks update local records correctly
- [ ] First payment is triggered on the due date

### 4. Webhook Configuration

Configure in Asaas Dashboard > Integrações > Webhook:
- [ ] URL: `https://yourdomain.com/api/webhooks/asaas`
- [ ] Events: All payment and subscription events selected
- [ ] Token authentication enabled
- [ ] Test webhook delivery in Asaas Dashboard

---

## 5. Payment Flow Testing (both providers)

### Test in Draft/Simulated Mode First
For Stripe: set `STRIPE_MODE=draft`
For Asaas: sandbox API key generates real PIX codes but payments are not actually charged

### Then Test in Live Mode
- [ ] Subscription flow works
- [ ] One-time purchase flow works
- [ ] Coupon/discount applied correctly
- [ ] Success page shows after payment confirmation
- [ ] Failure/error UI displays properly
- [ ] Loading states work on slow connections

---

## 6. Security Checklist

- [ ] **Never** commit `ASAAS_API_KEY` or `STRIPE_SECRET_KEY` to the repository
- [ ] **Never** log raw PIX copy-paste codes to console or logs
- [ ] **Never** log full card numbers or CVV
- [ ] **Never** persist raw PAN or CVV to database
- [ ] Use `.env` files locally; set env vars via secure deployment secrets in production
- [ ] No sensitive data in URL query strings (except public coupon codes)
- [ ] HTTPS enforced in production

---

## 7. Monitoring & Observability

- [ ] Check application logs for:
  - `payment_intent_created` events
  - `payment_intent_failed` events
  - `asaas_webhook_received` events
  - Provider-specific errors in structured logs
- [ ] Set up alerts for failed payments

---

## 8. Post-Launch

- [ ] Monitor Asaas Dashboard (or Stripe Dashboard) for payment failures
- [ ] Review payment success rate
- [ ] Monitor webhook delivery success rate in Asaas Dashboard

---

## Quick Rollback

If payments break:

1. Switch provider: set `NEXT_PUBLIC_PAYMENT_PROVIDER=stripe` (if Asaas was active)
2. If using Stripe, set `STRIPE_MODE=draft` as temporary fallback
3. Check logs for `payment_intent_failed` events
4. Verify API key validity in the provider dashboard
5. Re-test with small amounts

---

## Support

- Stripe Docs: https://stripe.com/docs
- Stripe Support: https://support.stripe.com
- Asaas Docs: https://docs.asaas.com/reference
- Asaas Support: https://suporte.asaas.com
