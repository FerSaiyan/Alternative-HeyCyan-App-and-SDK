import Link from "next/link";
import { SiteHeader } from "@/components/sections/site-header";
import { GlassCard } from "@/components/ui/glass-card";
import {
  GLASSES_PRICE_BRL,
  MONTHLY_SUBSCRIPTION_BRL,
  applyPercentDiscount,
  formatPriceBrl,
  getPromoCoupon,
  normalizeCouponCode,
} from "@/lib/business";

type SuccessPageProps = {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

export default async function SuccessPage({ searchParams }: SuccessPageProps) {
  const params = await searchParams;
  const isMock = String(params.mock ?? "") === "1";
  const isAsaas = String(params.asaas ?? "") === "1";
  const purchaseType = String(params.purchase ?? "") === "one_time" ? "one_time" : "subscription";
  const promoCoupon = getPromoCoupon(normalizeCouponCode(String(params.coupon ?? "")));

  const basePrice = purchaseType === "one_time" ? GLASSES_PRICE_BRL : MONTHLY_SUBSCRIPTION_BRL;
  const finalPrice = promoCoupon ? applyPercentDiscount(basePrice, promoCoupon.percent) : basePrice;

  /* Determine effective payment status (never trust query params alone) */
  // Asaas payments are always pending on the success page (confirmed only via webhook).
  // Stripe mock/draft payments are considered confirmed for flow testing.
  const isPending = isAsaas;
  const isConfirmed = !isPending;

  return (
    <div className="pb-10">
      <div className="carelens-ambient" />
      <SiteHeader />
      <main className="container-width pt-8 sm:pt-12">
        <section className="mx-auto grid max-w-4xl gap-5 lg:grid-cols-[1.1fr,0.9fr]">
          <GlassCard className="glass-card-strong p-6 sm:p-7">
            <p className="pill-eyebrow">{isConfirmed ? "Payment confirmed" : "Payment requested"}</p>
            <h1 className="mt-3 text-3xl font-semibold tracking-tight text-slate-900">
              {isConfirmed
                ? (purchaseType === "one_time" ? "One-time access activated" : "Subscription activated")
                : "Payment created successfully"}
            </h1>

            <div className={`mt-4 rounded-xl border p-3 ${isConfirmed ? "border-emerald-200 bg-emerald-50" : "border-amber-200 bg-amber-50"}`}>
              <p className={`text-xs uppercase tracking-[0.14em] ${isConfirmed ? "text-emerald-700" : "text-amber-700"}`}>Purchase summary</p>
              <p className={`mt-1 text-sm font-semibold ${isConfirmed ? "text-emerald-900" : "text-amber-900"}`}>
                {purchaseType === "one_time" ? "One-time software access" : "Monthly subscription"}
              </p>
                <p className="text-xs text-muted">
                  {isAsaas ? "Payment method:" : "Amount paid:"}{" "}
                  <span className="font-semibold">{isAsaas ? "PIX / Boleto / Card" : formatPriceBrl(finalPrice)}</span>
                 {promoCoupon && !isAsaas && <span> (coupon {promoCoupon.code} applied)</span>}
                </p>
              </div>
              <p className="mt-3 text-sm leading-relaxed text-muted">
                {isAsaas
                  ? "Your payment was created successfully. Confirmation happens automatically after settlement."
                  : "Payment confirmed. Your CyanBridge software access is now active."}
              </p>

            {isMock ? (
              <p className="mt-4 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-800">
                Demo environment: payment recorded in draft mode for flow validation.
              </p>
            ) : null}

            {/* Show pending banner for Asaas payments (PIX, boleto, card) */}
            {isPending && (
              <p className="mt-4 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-800">
                Payment is still processing. Confirmation happens automatically by
                webhook after settlement.
              </p>
            )}

            <div className="mt-6 flex flex-wrap gap-3">
              <Link href="/account" className="btn-primary">
                View my account
              </Link>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-lg font-semibold text-slate-900">What happens next</h2>
            <ul className="mt-4 space-y-2 text-sm text-slate-800">
              <li>- Your software access will remain available in your account</li>
              <li>- You can manage billing and subscription status from the dashboard</li>
              <li>- Use the companion app with supported smart-glasses workflows</li>
              <li>- For billing questions, contact: contato@fersaiyan.com</li>
            </ul>
          </GlassCard>
        </section>
      </main>
    </div>
  );
}
