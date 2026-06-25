import { NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import { getSessionUserById, extractCookieValue, parseUserIdCookie } from "@/lib/session-user";

export async function POST(request: Request) {
  const cookieHeader = request.headers.get("cookie") ?? "";
  const userId = parseUserIdCookie(extractCookieValue(cookieHeader, "carelens_user_id"));
  const user = userId ? await getSessionUserById(userId) : null;

  if (!user) {
    return NextResponse.redirect(new URL("/signin?auth=required&next=/account", request.url));
  }

  const affiliateCoupon = await prisma.affiliateCoupon.findFirst({
    where: { ownerId: user.id },
  });

  if (!affiliateCoupon) {
    return NextResponse.redirect(new URL("/account?error=no_affiliate_coupon", request.url));
  }

  const stats = await prisma.affiliatePurchase.aggregate({
    where: { couponId: affiliateCoupon.id, status: { not: "CANCELLED" } },
    _sum: { revenueAmount: true },
  });

  const totalRevenue = stats._sum.revenueAmount ?? 0;

  const pendingWithdrawals = await prisma.affiliateWithdrawal.aggregate({
    where: { ownerId: user.id, status: { in: ["PENDING", "APPROVED"] } },
    _sum: { amountBrl: true },
  });

  const pendingBalance = pendingWithdrawals._sum.amountBrl ?? 0;
  const availableBalance = Math.max(totalRevenue - pendingBalance, 0);

  if (availableBalance <= 0) {
    return NextResponse.redirect(new URL("/account?error=no_balance", request.url));
  }

  await prisma.affiliateWithdrawal.create({
    data: {
      ownerId: user.id,
      amountBrl: availableBalance,
      status: "APPROVED",
      notes: "Crédito para próximo tratamento de 6 meses",
    },
  });

  return NextResponse.redirect(new URL("/account?success=credit_requested", request.url));
}
