import Link from "next/link";
import { SiteHeader } from "@/components/sections/site-header";
import { SiteFooter } from "@/components/sections/site-footer";
import { GlassCard } from "@/components/ui/glass-card";
import { PaymentStep } from "@/components/payments/payment-step";
import { normalizeCouponCode } from "@/lib/business";

const IS_ASAAS = process.env.NEXT_PUBLIC_PAYMENT_PROVIDER === "asaas";

type PaymentPageProps = {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

export default async function PaymentPage({ searchParams }: PaymentPageProps) {
  const params = await searchParams;
  const purchaseType = String(params.purchaseType ?? "") === "one_time" ? "one_time" : "subscription";

  const formData = {
    email: String(params.email ?? "").trim(),
    fullName: String(params.fullName ?? "").trim(),
    sex: String(params.sex ?? "").trim(),
    healthCondition: String(params.healthCondition ?? "").trim(),
    dateOfBirth: String(params.dateOfBirth ?? "").trim(),
    visionLevel: String(params["vision-level"] ?? "").trim(),
    dailyRoutine: String(params["daily-routine"] ?? "").trim(),
    techComfort: String(params["tech-comfort"] ?? "").trim(),
    livingSituation: String(params["living-situation"] ?? "").trim(),
    primaryConcern: String(params["primary-concern"] ?? "").trim(),
    medications: String(params.medications ?? "").trim(),
    allergies: String(params.allergies ?? "").trim(),
    emergencyName: String(params.emergencyName ?? "").trim(),
    emergencyPhone: String(params.emergencyPhone ?? "").trim(),
    glassesColor: String(params.glassesColor ?? "").trim(),
    cpfCnpj: String(params.cpfCnpj ?? "").trim(),
    purchaseType,
    couponCode: normalizeCouponCode(String(params.couponCode ?? "")),
  } as const;

  const couponBackParams = new URLSearchParams({
    purchaseType,
    email: formData.email,
    fullName: formData.fullName,
    sex: formData.sex,
    healthCondition: formData.healthCondition,
    dateOfBirth: formData.dateOfBirth,
    "vision-level": formData.visionLevel,
    "daily-routine": formData.dailyRoutine,
    "tech-comfort": formData.techComfort,
    "living-situation": formData.livingSituation,
    "primary-concern": formData.primaryConcern,
    medications: formData.medications,
    allergies: formData.allergies,
    emergencyName: formData.emergencyName,
    emergencyPhone: formData.emergencyPhone,
    glassesColor: formData.glassesColor,
    couponCode: formData.couponCode,
    cpfCnpj: formData.cpfCnpj,
  });

  return (
    <div className="pb-10">
      <div className="carelens-ambient" />
      <SiteHeader />
      <main className="container-width pt-8 sm:pt-12">
        <section className="mx-auto grid max-w-5xl gap-5 lg:grid-cols-[1.1fr,0.9fr]">
          <GlassCard className="glass-card-strong p-6 sm:p-7">
            <p className="pill-eyebrow">Secure payment</p>
            <h1 className="mt-3 text-3xl font-semibold tracking-tight text-slate-900">
              {purchaseType === "one_time" ? "Complete one-time software checkout" : "Complete your monthly subscription"}
            </h1>
            <p className="mt-2 text-sm text-muted">
              {IS_ASAAS
                ? purchaseType === "one_time"
                  ? "One-time CyanBridge software purchase via PIX, boleto, or card."
                  : "Monthly CyanBridge software subscription for AI access via credit card."
                : "You can pay with card or digital wallet when available."}
            </p>

            <div className="mt-5">
              <PaymentStep {...formData} />
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-lg font-semibold text-slate-900">Payment privacy</h2>
            <ul className="mt-4 space-y-2 text-sm text-slate-800">
              {IS_ASAAS ? (
                <>
                  <li>- Payments are processed by Asaas (PIX, boleto, or credit card)</li>
                  <li>- Card payments are handled in Asaas&apos;s secure environment</li>
                  <li>- We do not store full card data</li>
                </>
              ) : (
                <>
                  <li>- Card data is processed by Stripe</li>
                  <li>- Online or virtual cards are recommended when available</li>
                </>
              )}
            </ul>

            <Link href={`/coupon?${couponBackParams.toString()}`} className="mt-5 inline-block text-sm font-semibold text-[var(--brand-strong)] underline">
              Back to coupon
            </Link>
          </GlassCard>
        </section>
      </main>

      <div className="fixed inset-x-0 bottom-3 z-30 px-4 md:hidden">
        <div className="mx-auto flex max-w-4xl items-center justify-between gap-3 rounded-full border border-white/70 bg-white/92 px-4 py-3 shadow-lg backdrop-blur">
          <div className="min-w-0">
            <p className="text-[10px] uppercase tracking-[0.14em] text-slate-500">
              {purchaseType === "one_time" ? "One-time access" : "Monthly subscription"}
            </p>
            <p className="text-sm font-semibold text-slate-900">Secure payment</p>
          </div>
          <span className="text-xs text-slate-500">Final step</span>
        </div>
      </div>

      <SiteFooter />
    </div>
  );
}
