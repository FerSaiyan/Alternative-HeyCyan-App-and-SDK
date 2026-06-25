# Production Improvement Phase Plan

This document outlines the phased plan for production-hardening the Levya web application.

---

## Phase 1: Hardening Payment & Core Flow (Week 1)

### Goals
- Reliable payment experience under failure
- Clear step indicators
- Working progress bar across flows

### Items

- [x] Separate assinatura vs compra única flows with distinct buttons
- [x] Embedded Stripe payment step with Apple Pay / Google Pay
- [x] Graceful error handling when Stripe intent creation fails
- [x] Preserve form state on payment error (so user doesn't re-type)
- [x] Retry UI + support link on payment failure

### Implementation Notes
- `app/api/payments/intent/route.ts` returns clear error messages
- `components/payments/payment-step.tsx` shows error + allows retry

---

## Phase 2: User Continuity & Account UX (Week 2)

### Goals
- Show purchase type in account page
- Allow re-opening payment without repeating onboarding
- Role-aware menu behavior

### Items

- [x] Display active subscription / last purchase on `/account`
- [x] Prefill form data for returning users on onboarding (via query params)
- [x] Show pending payment CTA in menu if user started but not completed

### Implementation Notes
- `app/account/page.tsx` shows purchase type and status
- Onboarding prefills from query params
- Menu already has role-aware items

---

## Phase 3: Observability & Logging (Week 3)

### Goals
- Structured event logging for key flows
- Error tracking for production issues

### Items

- [x] Add event logging for: coupon_cut, payment_intent_created, webhook_received, payment_failed
- [x] Add lightweight error tracking (see `lib/logger.ts`)
- [x] Audit for PII/card-adjacent data in logs

### Implementation Notes
- `lib/logger.ts` provides structured JSON logging
- No raw card data logged

---

## Phase 4: Performance & Polish (Week 4)

### Goals
- Faster perceived load time
- Refined mobile experience
- Operational runbook for payment troubleshooting

### Items

- [x] Preload next-step routes (`/coupon`, `/payment`) - Next.js Link prefetch
- [x] Sticky mobile CTA on payment page
- [x] Production payment checklist (see `docs/PRODUCTION_PAYMENT_CHECKLIST.md`)

---

## Priority Order (Quick Wins First)

1. Error handling on payment step (prevents user frustration)
2. Add purchase info to account (shows progress)
3. Structured event logs (debug issues)
4. Operational checklist (runbook)

---

## Environment Variables Required for Live Payments

```bash
STRIPE_MODE=live
STRIPE_SECRET_KEY=sk_live_...
NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY=pk_live_...
STRIPE_PRICE_MONTHLY_BRL_1700=price_...
STRIPE_PRICE_ONE_TIME_BRL=price_...
STRIPE_PROMOTION_CODE_WELCOME10=promo_...
STRIPE_WEBHOOK_SECRET=whsec_...
```

---

## Not Covered (Scope Out)

- Refund flows (separate policy work)
- Doctor dashboard enhancements
- Pharmacy order management UI
- Newsletter / re-engagement email flows