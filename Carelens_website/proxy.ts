import { NextRequest, NextResponse } from "next/server";

const COUPON_COOKIE = "carelens_coupon_code";
const AFFILIATE_REF_COOKIE = "carelens_affiliate_ref";
const COUPON_QUERY_KEYS = ["coupon", "promoCode"] as const;

function normalizeCouponCode(raw: string): string {
  return raw.trim().toUpperCase().replace(/[^A-Z0-9_-]/g, "").slice(0, 32);
}

export function proxy(request: NextRequest) {
  const response = NextResponse.next();

  let incomingCoupon = "";
  for (const key of COUPON_QUERY_KEYS) {
    const value = request.nextUrl.searchParams.get(key);
    if (value) {
      incomingCoupon = value;
      break;
    }
  }

  if (!incomingCoupon) {
    return response;
  }

  const normalized = normalizeCouponCode(incomingCoupon);
  if (!normalized) {
    return response;
  }

  response.cookies.set(COUPON_COOKIE, normalized, {
    path: "/",
    httpOnly: false,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    maxAge: 60 * 60 * 24 * 30,
  });

  response.cookies.set(AFFILIATE_REF_COOKIE, normalized, {
    path: "/",
    httpOnly: false,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    maxAge: 60 * 60 * 24 * 30,
  });

  return response;
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
