import { NextResponse } from "next/server";
import Stripe from "stripe";
import { ensureUserFromEmail, extractCookieValue, getSessionUserById, parseUserIdCookie } from "@/lib/session-user";
import {
  CheckoutPurchaseType,
  getStripeClient,
} from "@/lib/stripe";
import {
  GLASSES_PRICE_BRL,
  MONTHLY_SUBSCRIPTION_BRL,
  applyPercentDiscount,
  applyCouponDiscount,
  formatPriceBrl,
  getPromoCoupon,
  normalizeCouponCode,
} from "@/lib/business";
import { resolveCoupon, recordAffiliatePurchase } from "@/lib/coupon-resolver";
import { getRequestOrigin } from "@/lib/request-origin";
import { prisma } from "@/lib/prisma";
import {
  logPaymentIntentCreated,
  logPaymentIntentFailed,
} from "@/lib/logger";

type SupportedSex = "MALE" | "FEMALE" | "PREFER_NOT_SAY";

function parseSex(value: string): SupportedSex | null {
  if (value === "MALE" || value === "FEMALE" || value === "PREFER_NOT_SAY") {
    return value;
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

/**
 * Determine whether Stripe is in live (real payment) mode.
 *
 * Defaults to "live" when the env var is absent — this is a safety
 * measure for production deployments that forget to set STRIPE_MODE.
 * Local dev explicitly sets STRIPE_MODE=draft in .env so the draft
 * path remains available for development.
 */
function isLiveStripeMode(): boolean {
  return (process.env.STRIPE_MODE ?? "live").toLowerCase() === "live";
}

async function ensureStripeCustomer(params: {
  stripe: Stripe;
  userId: string;
  email: string;
  fullName: string;
}): Promise<string> {
  const existing = await prisma.user.findUnique({
    where: { id: params.userId },
    select: { stripeCustomerId: true },
  });

  if (existing?.stripeCustomerId) {
    return existing.stripeCustomerId;
  }

  const customer = await params.stripe.customers.create({
    email: params.email,
    name: params.fullName || undefined,
    metadata: {
      userId: params.userId,
    },
  });

  try {
    await prisma.user.update({
      where: { id: params.userId },
      data: { stripeCustomerId: customer.id },
    });
  } catch {
    // Keeps checkout functional if runtime Prisma client is stale.
  }

  return customer.id;
}

type IntentRequestPayload = {
  email?: string;
  fullName?: string;
  dateOfBirth?: string;
  sex?: string;
  healthCondition?: string;
  visionLevel?: string;
  dailyRoutine?: string;
  techComfort?: string;
  livingSituation?: string;
  primaryConcern?: string;
  medications?: string;
  allergies?: string;
  emergencyName?: string;
  emergencyPhone?: string;
  glassesColor?: string;
  purchaseType?: string;
  couponCode?: string;
};

export async function POST(request: Request) {
  const payload = (await request.json()) as IntentRequestPayload;
  const email = String(payload.email ?? "").trim();
  const fullName = String(payload.fullName ?? "").trim();
  const dateOfBirth = parseDateOfBirth(String(payload.dateOfBirth ?? ""));
  const sex = parseSex(String(payload.sex ?? "").trim());
  const healthCondition = String(payload.healthCondition ?? "").trim();
  const visionLevel = String(payload.visionLevel ?? "").trim();
  const dailyRoutine = String(payload.dailyRoutine ?? "").trim();
  const techComfort = String(payload.techComfort ?? "").trim();
  const livingSituation = String(payload.livingSituation ?? "").trim();
  const primaryConcern = String(payload.primaryConcern ?? "").trim();
  const medications = String(payload.medications ?? "").trim();
  const allergies = String(payload.allergies ?? "").trim();
  const emergencyName = String(payload.emergencyName ?? "").trim();
  const emergencyPhone = String(payload.emergencyPhone ?? "").trim();
  const glassesColor = String(payload.glassesColor ?? "").trim();
  const purchaseType = parsePurchaseType(String(payload.purchaseType ?? ""));
  const couponCode = normalizeCouponCode(String(payload.couponCode ?? ""));

  const cookieHeader = request.headers.get("cookie") ?? "";
  const sessionUserId = parseUserIdCookie(extractCookieValue(cookieHeader, "carelens_user_id"));
  const sessionUser = sessionUserId ? await getSessionUserById(sessionUserId) : null;
  if (!email && !sessionUser) {
    return NextResponse.json({ ok: false, message: "E-mail é obrigatório." }, { status: 400 });
  }
  const user = sessionUser ?? (await ensureUserFromEmail(email));
  const resolvedEmail = sessionUser?.email ?? email;
  const promoCoupon = getPromoCoupon(couponCode);
  const resolvedAffiliate = promoCoupon ? null : await resolveCoupon(couponCode);

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
  } catch {
    // Keeps checkout functional if runtime Prisma client is stale.
  }

  const appUrl = getRequestOrigin(request);
  const successUrl = `${appUrl}/success?purchase=${purchaseType}`;

  if (!isLiveStripeMode()) {
    const basePrice = purchaseType === "one_time" ? GLASSES_PRICE_BRL : MONTHLY_SUBSCRIPTION_BRL;
    const mockPrice = promoCoupon
      ? applyPercentDiscount(basePrice, promoCoupon.percent)
      : resolvedAffiliate
        ? applyCouponDiscount(basePrice, resolvedAffiliate)
        : basePrice;

    logPaymentIntentCreated({
        userId: user.id,
        purchaseType,
        amountBrl: mockPrice,
        couponApplied: Boolean(promoCoupon || resolvedAffiliate),
      });

    return NextResponse.json({
      ok: true,
      draft: true,
      successUrl: `${successUrl}&mock=1&price=${mockPrice}${
        promoCoupon ? `&coupon=${promoCoupon.code}` : resolvedAffiliate ? `&coupon=${resolvedAffiliate.code}` : ""
      }`,
      amountLabel: `R$ ${mockPrice}`,
    });
  }

  const stripe = getStripeClient();
  if (!stripe) {
    return NextResponse.json(
      { ok: false, message: "Falha ao inicializar Stripe em modo live." },
      { status: 500 },
    );
  }

  const stripeCustomerId = await ensureStripeCustomer({
    stripe,
    userId: user.id,
    email: resolvedEmail,
    fullName,
  });

  try {
    const baseAmount = purchaseType === "one_time" ? GLASSES_PRICE_BRL : MONTHLY_SUBSCRIPTION_BRL;
    const amountBrl = promoCoupon
      ? applyPercentDiscount(baseAmount, promoCoupon.percent)
      : resolvedAffiliate
        ? applyCouponDiscount(baseAmount, resolvedAffiliate)
        : baseAmount;

    const intent = await stripe.paymentIntents.create({
      amount: Math.round(amountBrl * 100),
      currency: "brl",
      customer: stripeCustomerId,
      receipt_email: resolvedEmail,
      setup_future_usage: "off_session",
      automatic_payment_methods: { enabled: true },
      metadata: {
        userId: user.id,
        purchaseType,
        couponCode: promoCoupon?.code ?? resolvedAffiliate?.code ?? "",
      },
    });

    logPaymentIntentCreated({
      userId: user.id,
      purchaseType,
      amountBrl,
      couponApplied: Boolean(promoCoupon || resolvedAffiliate),
    });

    if (resolvedAffiliate?.affiliateCouponId) {
      try {
        await recordAffiliatePurchase({
          couponId: resolvedAffiliate.affiliateCouponId,
          buyerId: user.id,
          paymentId: intent.id,
          paymentProvider: "stripe",
          purchaseValue: amountBrl,
        });
      } catch {
        // non-fatal
      }
    }

    return NextResponse.json({
      ok: true,
      draft: false,
      clientSecret: intent.client_secret,
      amountLabel: formatPriceBrl(amountBrl),
      successUrl,
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Erro desconhecido ao iniciar pagamento.";

    logPaymentIntentFailed({
      userId: user.id,
      purchaseType,
      error: message,
    });

    return NextResponse.json({ ok: false, message: `Erro Stripe: ${message}` }, { status: 500 });
  }
}
