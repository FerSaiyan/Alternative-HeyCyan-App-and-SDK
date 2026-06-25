# Embedded Payments Flow

## Goal
Use a two-step purchase flow (dados -> cupom -> pagamento) so users can pay.

Two providers are supported, selected via `NEXT_PUBLIC_PAYMENT_PROVIDER`:

| Provider | `NEXT_PUBLIC_PAYMENT_PROVIDER` | Payment methods |
|----------|--------------------------------|-----------------|
| Stripe   | `stripe` (default)             | Credit card, Google Pay, Apple Pay |
| Asaas    | `asaas`                        | PIX, Boleto Bancário, Cartão de Crédito (parcelado até 12x), Assinatura recorrente |

---

## Stripe Flow

### Routes
- `POST /api/checkout/session`
  - Validates user data from onboarding/coupon forms.
  - Stores user profile fields.
  - Redirects to `/payment` with purchase context.
- `GET /payment`
  - Loads the embedded payment step UI.
- `POST /api/payments/intent`
  - Draft mode: returns mock success URL.
  - Live mode:
    - `one_time`: creates `PaymentIntent`.
    - `subscription`: creates incomplete `Subscription` and returns invoice payment `clientSecret`.
- `POST /api/webhooks/stripe`
  - Verifies signatures.
  - Handles idempotency.
  - Reconciles one-time and subscription states.

### Stripe Elements used
- `ExpressCheckoutElement`: wallet buttons (Apple Pay / Google Pay when supported).
- `PaymentElement`: card + payment method tabs.

### Safe card storage policy
- We do **not** store raw card numbers, CVC, or expiry.
- Card entry/tokenization happens in Stripe-controlled Elements.
- App DB stores only safe identifiers:
  - `User.stripeCustomerId`
  - `User.stripeDefaultPaymentMethodId`

These references can be used for future off-session or one-click flows under Stripe compliance controls.

---

## Asaas Flow

### Routes
- `POST /api/checkout/session` (shared – unchanged)
- `GET /payment` (shared – renders provider-specific UI)
- `POST /api/payments/asaas`
  - Requires `ASAAS_API_KEY` env var.
  - Accepts:
    - `billingType`: `"PIX"` (default), `"BOLETO"`, or `"CREDIT_CARD"`
    - `installmentCount`: ≥2 for parcelado (credit card only, max 12x)
    - `installmentValue`: optional per-installment value (derived from total if omitted)
    - Existing fields: `fullName`, `email`, `purchaseType`, `couponCode`, etc.
  - Creates or finds an Asaas customer (by email or externalReference).
  - For `purchaseType === "subscription"`: creates an Asaas subscription (recurring).
  - For `purchaseType === "one_time"`: creates a single payment with the specified billing type.
  - Returns method-specific payloads.

### Response Payloads

**PIX:**
```json
{
  "ok": true,
  "provider": "asaas",
  "paymentId": "pay_...",
  "status": "PENDING",
  "billingType": "PIX",
  "invoiceUrl": "https://...",
  "amountLabel": "R$ 1.700",
  "pixEncodedImage": "base64...",
  "pixCopyPaste": "000201...",
  "dueDate": "2026-05-18"
}
```

**Boleto:**
```json
{
  "ok": true,
  "provider": "asaas",
  "paymentId": "pay_...",
  "status": "PENDING",
  "billingType": "BOLETO",
  "amountLabel": "R$ 1.700",
  "bankSlipUrl": "https://...",
  "identificationField": "34191...",
  "dueDate": "2026-05-18"
}
```

**Credit Card (single):**
```json
{
  "ok": true,
  "provider": "asaas",
  "paymentId": "pay_...",
  "status": "PENDING",
  "billingType": "CREDIT_CARD",
  "invoiceUrl": "https://...",
  "amountLabel": "R$ 1.700",
  "installmentCount": 6,
  "installmentValue": 283.33,
  "dueDate": "2026-05-18"
}
```

**Subscription (recurring):**
```json
{
  "ok": true,
  "provider": "asaas",
  "subscriptionId": "sub_...",
  "paymentId": "sub_...",
  "status": "ACTIVE",
  "billingType": "PIX",
  "amountLabel": "R$ 399/mês",
  "isRecurring": true,
  "dueDate": "2026-05-18"
}
```

### PIX payment instructions
1. User selects PIX and clicks "Pagar com PIX".
2. Backend creates Asaas customer + payment (PIX).
3. UI displays QR code + copy-paste PIX code.
4. User pays via banking app.
5. User clicks "Já realizei o pagamento" → `/success?asaas=1`.

### Boleto payment instructions
1. User selects Boleto Bancário and clicks "Pagar com Boleto".
2. Backend creates Asaas customer + payment (BOLETO).
3. UI displays "Visualizar Boleto" link and Linha Digitável.
4. User pays via internet banking or lottery.
5. User clicks "Já realizei o pagamento" → `/success?asaas=1`.

### Credit Card payment instructions
1. User selects Cartão de Crédito and optionally chooses installment count (2-12x).
2. User clicks "Pagar com Cartão de Crédito".
3. Backend creates Asaas payment with `billingType=CREDIT_CARD`.
4. UI shows "Pagar com Cartão de Crédito" link → redirects to Asaas hosted checkout.
5. User enters card data in Asaas secure environment.
6. User clicks "Já finalizei o pagamento" → `/success?asaas=1`.

### Subscription (recurring) payment
1. User selects PIX, Boleto, or Cartão de Crédito for subscription purchase.
2. Backend creates Asaas subscription via `/v3/subscriptions`.
3. For `CREDIT_CARD`, backend retrieves the first generated subscription charge and returns `invoiceUrl`.
4. UI keeps the user on a CyanBridge page, opens the Asaas hosted checkout in a separate secure page, and polls subscription status before returning to the app.
5. The app callback only fires after Asaas reports the recurring subscription as active.

> Note: installment (`parcelado`) applies to one-time `CREDIT_CARD` payments only. Subscriptions are recurring monthly charges.

### Webhook reconciliation
- The `/api/webhooks/asaas` endpoint handles both `PAYMENT_*` and `SUBSCRIPTION_*` events.
- Events are idempotent (unique event IDs).
- Payment events update `AsaasPayment.status`.
- Subscription events update `AsaasSubscription.status` and linked payment records.

### Required env vars (Asaas mode)
```bash
NEXT_PUBLIC_PAYMENT_PROVIDER=asaas
ASAAS_API_KEY=$aak_sandbox_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
# ASAAS_API_BASE_URL=https://sandbox.asaas.com/api/v3    # default
```

If your token starts with `$` in `.env`, escape it as `\$...` to prevent Next.js env expansion from stripping the value.

### Webhook configuration
Configure in Asaas Dashboard > Integrações > Webhook:
- URL: `https://yourdomain.com/api/webhooks/asaas`
- Events: All payment and subscription events
- Token: Set `ASAAS_WEBHOOK_TOKEN` to a secret value; Asaas sends it as `asaas-access-token` header

```bash
ASAAS_WEBHOOK_TOKEN=your_secret_token_here
```
