import { prisma } from "@/lib/prisma";
import { type PromoCoupon, PROMO_COUPONS, normalizeCouponCode } from "@/lib/business";

export type ResolvedCoupon = PromoCoupon & {
  isAffiliate: boolean;
  affiliateCouponId?: string;
  ownerId?: string;
};

export async function resolveCoupon(rawCode: string): Promise<ResolvedCoupon | null> {
  const code = normalizeCouponCode(rawCode);
  if (!code) return null;

  const staticCoupon = PROMO_COUPONS.find((c) => c.code === code);
  if (staticCoupon) {
    return { ...staticCoupon, isAffiliate: false };
  }

  const affiliateCoupon = await prisma.affiliateCoupon.findUnique({
    where: { code, isActive: true },
  });
  if (!affiliateCoupon) return null;

  return {
    code: affiliateCoupon.code,
    percent: affiliateCoupon.discountType === "PERCENT" ? affiliateCoupon.discountValue : 0,
    discountType: affiliateCoupon.discountType,
    discountValue: affiliateCoupon.discountValue,
    isAffiliate: true,
    affiliateCouponId: affiliateCoupon.id,
    ownerId: affiliateCoupon.ownerId,
  };
}

export async function isAffiliateCouponCode(rawCode: string): Promise<boolean> {
  const code = normalizeCouponCode(rawCode);
  if (!code) return false;
  const exists = await prisma.affiliateCoupon.findUnique({
    where: { code, isActive: true },
    select: { id: true },
  });
  return !!exists;
}

export async function recordAffiliatePurchase(params: {
  couponId: string;
  buyerId: string;
  paymentId?: string;
  paymentProvider: string;
  purchaseValue: number;
}): Promise<void> {
  const { AFFILIATE_REVENUE_PERCENT } = await import("@/lib/business");
  const revenueAmount = Math.round(params.purchaseValue * (AFFILIATE_REVENUE_PERCENT / 100) * 100) / 100;

  await prisma.affiliatePurchase.create({
    data: {
      couponId: params.couponId,
      buyerId: params.buyerId,
      paymentId: params.paymentId,
      paymentProvider: params.paymentProvider,
      purchaseValue: params.purchaseValue,
      revenueAmount,
    },
  });

  await prisma.affiliateCoupon.update({
    where: { id: params.couponId },
    data: { usageCount: { increment: 1 } },
  });
}
