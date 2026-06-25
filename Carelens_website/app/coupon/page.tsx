import { cookies } from "next/headers";
import { SiteHeader } from "@/components/sections/site-header";
import { SiteFooter } from "@/components/sections/site-footer";
import { CouponTicket } from "@/components/ui/coupon-ticket";
import {
  GLASSES_PRICE_BRL,
  MONTHLY_SUBSCRIPTION_BRL,
  WELCOME_COUPON_CODE,
  applyPercentDiscount,
  applyCouponDiscount,
  formatPriceBrl,
  getPromoCoupon,
  normalizeCouponCode,
} from "@/lib/business";
import { resolveCoupon } from "@/lib/coupon-resolver";

type CouponPageProps = {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

function firstNameFromFullName(fullName: string): string {
  return fullName.trim().split(/\s+/)[0] ?? "";
}

export default async function CouponPage({ searchParams }: CouponPageProps) {
  const params = await searchParams;
  const cookieStore = await cookies();
  const fullName = String(params.fullName ?? "").trim();
  const email = String(params.email ?? "").trim();
  const sex = String(params.sex ?? "").trim();
  const healthCondition = String(params.healthCondition ?? "").trim();
  const dateOfBirth = String(params.dateOfBirth ?? "").trim();
  const visionLevel = String(params["vision-level"] ?? "").trim();
  const dailyRoutine = String(params["daily-routine"] ?? "").trim();
  const techComfort = String(params["tech-comfort"] ?? "").trim();
  const livingSituation = String(params["living-situation"] ?? "").trim();
  const primaryConcern = String(params["primary-concern"] ?? "").trim();
  const medications = String(params.medications ?? "").trim();
  const allergies = String(params.allergies ?? "").trim();
  const emergencyName = String(params.emergencyName ?? "").trim();
  const emergencyPhone = String(params.emergencyPhone ?? "").trim();
  const glassesColor = String(params.glassesColor ?? "").trim();
  const purchaseType = String(params.purchaseType ?? "") === "one_time" ? "one_time" : "subscription";
  const cookieCouponCodeRaw = String(cookieStore.get("carelens_coupon_code")?.value ?? "").trim();
  const cookieCouponCode = normalizeCouponCode(cookieCouponCodeRaw);
  const appliedCouponCode = normalizeCouponCode(String(params.couponCode ?? ""));
  const hasExplicitCouponParam = String(params.couponCode ?? "").trim().length > 0;
  const eligibleCookieCoupon =
    cookieCouponCode && cookieCouponCode !== WELCOME_COUPON_CODE ? cookieCouponCode : "";
  const couponDraft = normalizeCouponCode(
    String(params.couponDraft ?? "") || appliedCouponCode || (hasExplicitCouponParam ? "" : eligibleCookieCoupon),
  );

  const basePrice = purchaseType === "one_time" ? GLASSES_PRICE_BRL : MONTHLY_SUBSCRIPTION_BRL;
  const appliedCoupon = getPromoCoupon(appliedCouponCode);
  const resolvedAffiliate = appliedCoupon ? null : await resolveCoupon(appliedCouponCode);
  const discountedPrice = appliedCoupon
    ? applyPercentDiscount(basePrice, appliedCoupon.percent)
    : resolvedAffiliate
      ? applyCouponDiscount(basePrice, resolvedAffiliate)
      : basePrice;
  const couponLabel = appliedCoupon
    ? `${appliedCoupon.code} · ${appliedCoupon.percent}% OFF`
    : resolvedAffiliate
      ? resolvedAffiliate.discountType === "FIXED"
        ? `${resolvedAffiliate.code} · ${formatPriceBrl(resolvedAffiliate.discountValue!)} OFF`
        : `${resolvedAffiliate.code} · ${resolvedAffiliate.discountValue}% OFF`
      : "";
  const installmentValue = purchaseType === "subscription"
    ? discountedPrice
    : Math.round((discountedPrice / 6) * 100) / 100;
  const installmentLabel =
    purchaseType === "subscription"
      ? `${formatPriceBrl(installmentValue)}/mês`
      : `Até 6x de ${formatPriceBrl(installmentValue)} no cartão`;
  const attemptedInvalidCoupon = Boolean(appliedCouponCode) && !appliedCoupon && !resolvedAffiliate;

  const firstName = firstNameFromFullName(fullName);
  const checkoutHref = purchaseType === "one_time" ? "/single_purchase_onboarding" : "/sub_onboarding";
  const editParams = new URLSearchParams({
    purchaseType,
    fullName,
    email,
    sex,
    healthCondition,
    dateOfBirth,
    "vision-level": visionLevel,
    "daily-routine": dailyRoutine,
    "tech-comfort": techComfort,
    "living-situation": livingSituation,
    "primary-concern": primaryConcern,
    medications,
    allergies,
    emergencyName,
    emergencyPhone,
    glassesColor,
  });
  const editHref = `${checkoutHref}?${editParams.toString()}`;
  const now = new Date();
  const journeyStartDate = `${String(now.getDate()).padStart(2, "0")}/${String(now.getMonth() + 1).padStart(2, "0")}/${String(now.getFullYear()).slice(-2)}`;
  const couponStatus: "none" | "draft" | "invalid" | "applied" = appliedCoupon || resolvedAffiliate
    ? "applied"
    : attemptedInvalidCoupon
      ? "invalid"
      : couponDraft
        ? "draft"
        : "none";

  return (
    <div className="pb-10">
      <div className="carelens-ambient" />
      <SiteHeader />
      <main className="container-width pt-8 sm:pt-12">
        <section className="mx-auto max-w-2xl">
          <CouponTicket
            formId="coupon-checkout-form"
            title={purchaseType === "one_time" ? "Óculos inteligente" : "Assinatura mensal"}
            firstName={firstName || undefined}
            fullName={fullName}
            email={email}
            journeyStartDate={journeyStartDate}
            basePriceLabel={formatPriceBrl(basePrice)}
            discountedPriceLabel={formatPriceBrl(discountedPrice)}
            installmentLabel={installmentLabel}
            couponLabel={couponLabel}
            hasDiscount={Boolean(appliedCoupon || resolvedAffiliate)}
            couponDraft={couponDraft}
            couponStatus={couponStatus}
            appliedCouponCode={appliedCoupon?.code ?? resolvedAffiliate?.code}
            editHref={editHref}
            couponFormAction="/coupon"
            isAffiliateCoupon={Boolean(resolvedAffiliate)}
            couponFormHiddenFields={[
              { name: "purchaseType", value: purchaseType },
              { name: "fullName", value: fullName },
              { name: "email", value: email },
              { name: "sex", value: sex },
              { name: "healthCondition", value: healthCondition },
              { name: "dateOfBirth", value: dateOfBirth },
              { name: "vision-level", value: visionLevel },
              { name: "daily-routine", value: dailyRoutine },
              { name: "tech-comfort", value: techComfort },
              { name: "living-situation", value: livingSituation },
              { name: "primary-concern", value: primaryConcern },
              { name: "medications", value: medications },
              { name: "allergies", value: allergies },
              { name: "emergencyName", value: emergencyName },
              { name: "emergencyPhone", value: emergencyPhone },
              { name: "glassesColor", value: glassesColor },
            ]}
          />

          <form id="coupon-checkout-form" action="/api/checkout/session" method="post" className="sr-only">
            <input type="hidden" name="purchaseType" value={purchaseType} />
            <input type="hidden" name="couponCode" value={appliedCoupon?.code ?? resolvedAffiliate?.code ?? ""} />
            <input type="hidden" name="fullName" value={fullName} />
            <input type="hidden" name="email" value={email} />
            <input type="hidden" name="sex" value={sex} />
            <input type="hidden" name="healthCondition" value={healthCondition} />
            <input type="hidden" name="dateOfBirth" value={dateOfBirth} />
            <input type="hidden" name="vision-level" value={visionLevel} />
            <input type="hidden" name="daily-routine" value={dailyRoutine} />
            <input type="hidden" name="tech-comfort" value={techComfort} />
            <input type="hidden" name="living-situation" value={livingSituation} />
            <input type="hidden" name="primary-concern" value={primaryConcern} />
            <input type="hidden" name="medications" value={medications} />
            <input type="hidden" name="allergies" value={allergies} />
            <input type="hidden" name="emergencyName" value={emergencyName} />
            <input type="hidden" name="emergencyPhone" value={emergencyPhone} />
            <input type="hidden" name="glassesColor" value={glassesColor} />
          </form>
        </section>
      </main>
      <SiteFooter />
    </div>
  );
}
