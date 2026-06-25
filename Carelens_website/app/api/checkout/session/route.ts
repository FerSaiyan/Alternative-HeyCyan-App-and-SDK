import { NextResponse } from "next/server";
import { ensureUserFromEmail, extractCookieValue, getSessionUserById, parseUserIdCookie } from "@/lib/session-user";
import { CheckoutPurchaseType } from "@/lib/stripe";
import { prisma } from "@/lib/prisma";
import { logCouponCut, logUserCreated } from "@/lib/logger";
import { normalizeCouponCode } from "@/lib/business";
import { resolveCoupon, recordAffiliatePurchase } from "@/lib/coupon-resolver";

type SupportedSex = "MALE" | "FEMALE" | "PREFER_NOT_SAY";

function parseSex(value: string): SupportedSex | null {
  if (value === "MALE") {
    return "MALE";
  }
  if (value === "FEMALE") {
    return "FEMALE";
  }
  if (value === "PREFER_NOT_SAY") {
    return "PREFER_NOT_SAY";
  }
  return null;
}

function parsePurchaseType(value: string): CheckoutPurchaseType {
  return value === "one_time" ? "one_time" : "subscription";
}

function parseDateOfBirth(value: string): Date | undefined {
  const normalized = value.trim();
  if (!normalized) {
    return undefined;
  }

  const parsed = new Date(`${normalized}T00:00:00.000Z`);
  if (Number.isNaN(parsed.getTime())) {
    return undefined;
  }

  return parsed;
}

export async function POST(request: Request) {
  const formData = await request.formData();
  const email = String(formData.get("email") ?? "").trim();
  const fullName = String(formData.get("fullName") ?? "").trim();
  const sex = parseSex(String(formData.get("sex") ?? "").trim());
  const dateOfBirth = parseDateOfBirth(String(formData.get("dateOfBirth") ?? ""));
  const healthCondition = String(formData.get("healthCondition") ?? "").trim();
  const visionLevel = String(formData.get("vision-level") ?? "").trim();
  const dailyRoutine = String(formData.get("daily-routine") ?? "").trim();
  const techComfort = String(formData.get("tech-comfort") ?? "").trim();
  const livingSituation = String(formData.get("living-situation") ?? "").trim();
  const primaryConcern = String(formData.get("primary-concern") ?? "").trim();
  const medications = String(formData.get("medications") ?? "").trim();
  const allergies = String(formData.get("allergies") ?? "").trim();
  const emergencyName = String(formData.get("emergencyName") ?? "").trim();
  const emergencyPhone = String(formData.get("emergencyPhone") ?? "").trim();
  const glassesColor = String(formData.get("glassesColor") ?? "").trim();
  const cpfCnpj = String(formData.get("cpfCnpj") ?? "").trim();
  const purchaseType = parsePurchaseType(String(formData.get("purchaseType") ?? ""));
  const couponCode = normalizeCouponCode(String(formData.get("couponCode") ?? ""));

  const cookieHeader = request.headers.get("cookie") ?? "";
  const sessionUserId = parseUserIdCookie(extractCookieValue(cookieHeader, "carelens_user_id"));
  const sessionUser = sessionUserId ? await getSessionUserById(sessionUserId) : null;
  if (!email && !sessionUser) {
    return NextResponse.json({ ok: false, message: "E-mail é obrigatório." }, { status: 400 });
  }
  const user = sessionUser ?? (await ensureUserFromEmail(email));
  const resolvedEmail = sessionUser?.email ?? email;

  try {
    await prisma.user.update({
      where: { id: user.id },
      data: {
        fullName: fullName || null,
        dateOfBirth,
        sex,
        healthCondition: healthCondition || null,
        visionLevel: visionLevel || null,
        dailyRoutine: dailyRoutine || null,
        techComfortLevel: techComfort || null,
        livingSituation: livingSituation || null,
        primaryConcern: primaryConcern || null,
        medications: medications || null,
        allergies: allergies || null,
        emergencyName: emergencyName || null,
        emergencyPhone: emergencyPhone || null,
        glassesColor: glassesColor || null,
        onboardingCompletedAt: new Date(),
      },
    });
    logUserCreated({ userId: user.id, email: resolvedEmail });
  } catch {
    // Keeps checkout functional if runtime Prisma client is stale.
  }

  if (couponCode) {
    logCouponCut({ userId: user.id, purchaseType, couponCode });

    const resolvedAffiliate = await resolveCoupon(couponCode);
    if (resolvedAffiliate?.affiliateCouponId) {
      try {
        await recordAffiliatePurchase({
          couponId: resolvedAffiliate.affiliateCouponId,
          buyerId: user.id,
          paymentProvider: "checkout_session",
          purchaseValue: 0,
        });
      } catch {
        // non-fatal — payment routes will also record
      }
    }
  }

  const paymentParams = new URLSearchParams();
  paymentParams.set("email", resolvedEmail);
  paymentParams.set("purchaseType", purchaseType);
  if (fullName) {
    paymentParams.set("fullName", fullName);
  }
  if (sex) {
    paymentParams.set("sex", sex);
  }
  if (healthCondition) {
    paymentParams.set("healthCondition", healthCondition);
  }
  if (dateOfBirth) {
    paymentParams.set("dateOfBirth", dateOfBirth.toISOString().slice(0, 10));
  }
  if (visionLevel) {
    paymentParams.set("vision-level", visionLevel);
  }
  if (dailyRoutine) {
    paymentParams.set("daily-routine", dailyRoutine);
  }
  if (techComfort) {
    paymentParams.set("tech-comfort", techComfort);
  }
  if (livingSituation) {
    paymentParams.set("living-situation", livingSituation);
  }
  if (primaryConcern) {
    paymentParams.set("primary-concern", primaryConcern);
  }
  if (medications) {
    paymentParams.set("medications", medications);
  }
  if (allergies) {
    paymentParams.set("allergies", allergies);
  }
  if (emergencyName) {
    paymentParams.set("emergencyName", emergencyName);
  }
  if (emergencyPhone) {
    paymentParams.set("emergencyPhone", emergencyPhone);
  }
  if (glassesColor) {
    paymentParams.set("glassesColor", glassesColor);
  }
  if (couponCode) {
    paymentParams.set("couponCode", couponCode);
  }
  if (cpfCnpj) {
    paymentParams.set("cpfCnpj", cpfCnpj);
  }

  const response = new NextResponse(null, {
    status: 303,
    headers: {
      Location: `/payment?${paymentParams.toString()}`,
    },
  });
  response.cookies.set("carelens_user_id", user.id, {
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge: 60 * 60 * 24 * 60,
  });

  return response;
}
