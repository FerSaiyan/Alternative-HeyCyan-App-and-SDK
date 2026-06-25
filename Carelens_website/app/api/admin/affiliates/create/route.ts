import { NextResponse } from "next/server";
import { DiscountType, Role } from "@prisma/client";
import { prisma } from "@/lib/prisma";
import { getSessionUserById, extractCookieValue, parseUserIdCookie } from "@/lib/session-user";
import { normalizeCouponCode } from "@/lib/business";

export async function POST(request: Request) {
  const cookieHeader = request.headers.get("cookie") ?? "";
  const userId = parseUserIdCookie(extractCookieValue(cookieHeader, "carelens_user_id"));
  const adminUser = userId ? await getSessionUserById(userId) : null;

  if (!adminUser || adminUser.role !== Role.ADMIN) {
    return NextResponse.redirect(new URL("/admin", request.url));
  }

  const formData = await request.formData();
  const ownerId = String(formData.get("ownerId") ?? "").trim();
  const rawCode = String(formData.get("code") ?? "").trim();
  const discountType = String(formData.get("discountType") ?? "").trim() as DiscountType;
  const discountValueRaw = String(formData.get("discountValue") ?? "").trim();

  const baseUrl = new URL("/admin/affiliates", request.url);

  if (!ownerId || !rawCode || !discountType || !discountValueRaw) {
    baseUrl.searchParams.set("error", "missing_fields");
    return NextResponse.redirect(baseUrl);
  }

  const code = normalizeCouponCode(rawCode);
  const discountValue = Number.parseFloat(discountValueRaw);

  if (discountType !== "PERCENT" && discountType !== "FIXED") {
    baseUrl.searchParams.set("error", "invalid_discount");
    return NextResponse.redirect(baseUrl);
  }

  if (!Number.isFinite(discountValue) || discountValue <= 0) {
    baseUrl.searchParams.set("error", "invalid_discount");
    return NextResponse.redirect(baseUrl);
  }

  const existingUser = await prisma.user.findUnique({ where: { id: ownerId } });
  if (!existingUser) {
    baseUrl.searchParams.set("error", "user_not_found");
    return NextResponse.redirect(baseUrl);
  }

  const existingCoupon = await prisma.affiliateCoupon.findUnique({ where: { code } });
  if (existingCoupon) {
    baseUrl.searchParams.set("error", "code_taken");
    return NextResponse.redirect(baseUrl);
  }

  await prisma.affiliateCoupon.create({
    data: {
      code,
      ownerId,
      discountType,
      discountValue,
    },
  });

  baseUrl.searchParams.set("success", "created");
  return NextResponse.redirect(baseUrl);
}
