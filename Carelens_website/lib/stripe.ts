import {
  GLASSES_PRICE_BRL,
  MONTHLY_SUBSCRIPTION_BRL,
  applyPercentDiscount,
  getPromoCoupon,
  normalizeCouponCode,
} from "@/lib/business";
import { missingServerVars } from "@/lib/env";
import Stripe from "stripe";

export type CheckoutPurchaseType = "subscription" | "one_time";

export type CheckoutRequestPayload = {
  userId: string;
  customerEmail: string;
  successUrl: string;
  cancelUrl: string;
  purchaseType: CheckoutPurchaseType;
  couponCode?: string;
};

export type CheckoutResponsePayload = {
  ok: boolean;
  checkoutUrl?: string;
  message?: string;
};

function getStripeMode(): "draft" | "live" {
  return (process.env.STRIPE_MODE ?? "live").toLowerCase() === "live" ? "live" : "draft";
}

export function getStripeClient(): Stripe | null {
  if (getStripeMode() !== "live") {
    return null;
  }

  const secretKey = process.env.STRIPE_SECRET_KEY;
  if (!secretKey) {
    return null;
  }

  return new Stripe(secretKey);
}

export async function createCheckoutSession(
  payload: CheckoutRequestPayload,
): Promise<CheckoutResponsePayload> {
  const mode = getStripeMode();
  const normalizedCoupon = normalizeCouponCode(payload.couponCode ?? "");
  const promoCoupon = getPromoCoupon(normalizedCoupon);

  if (mode !== "live") {
    const basePrice = payload.purchaseType === "one_time" ? GLASSES_PRICE_BRL : MONTHLY_SUBSCRIPTION_BRL;
    const mockPrice = promoCoupon
      ? applyPercentDiscount(basePrice, promoCoupon.percent)
      : basePrice;
    return {
      ok: true,
      checkoutUrl: `${payload.successUrl}?mock=1&price=${mockPrice}&purchase=${payload.purchaseType}${
        promoCoupon ? `&coupon=${promoCoupon.code}` : ""
      }`,
    };
  }

  const missing = missingServerVars();
  if (missing.length > 0) {
    return {
      ok: false,
      message: `Variáveis de ambiente pendentes: ${missing.join(", ")}`,
    };
  }

  const stripe = getStripeClient();
  if (!stripe) {
    return {
      ok: false,
      message: "Falha ao inicializar cliente Stripe em modo live.",
    };
  }

  const monthlyPriceId = process.env.STRIPE_PRICE_MONTHLY_BRL_50;
  const glassesPriceId = process.env.STRIPE_PRICE_GLASSES_BRL_250;
  const stripePromotionCodeId = promoCoupon?.stripePromotionEnvVar
    ? process.env[promoCoupon.stripePromotionEnvVar]
    : undefined;

  if (!monthlyPriceId) {
    return {
      ok: false,
      message: "Preço da assinatura mensal Stripe não configurado.",
    };
  }

  if (payload.purchaseType === "one_time" && !glassesPriceId) {
    return {
      ok: false,
      message: "Preço dos óculos CareLens Stripe não configurado.",
    };
  }

  try {
    const successUrl = `${payload.successUrl}?purchase=${payload.purchaseType}`;
    const selectedPriceId = payload.purchaseType === "one_time" ? glassesPriceId! : monthlyPriceId;

    const session = await stripe.checkout.sessions.create({
      mode: "payment",
      customer_email: payload.customerEmail,
      line_items: [
        {
          price: selectedPriceId,
          quantity: 1,
        },
      ],
      success_url: successUrl,
      cancel_url: payload.cancelUrl,
      client_reference_id: payload.userId,
        metadata: {
          userId: payload.userId,
          purchaseType: payload.purchaseType,
          couponCode: normalizedCoupon ?? "",
        },
        discounts: stripePromotionCodeId ? [{ promotion_code: stripePromotionCodeId }] : undefined,
      });

    if (!session.url) {
      return {
        ok: false,
        message: "Stripe não retornou URL de checkout.",
      };
    }

    return {
      ok: true,
      checkoutUrl: session.url,
    };
  } catch (error) {
    const message = error instanceof Error ? error.message : "Erro desconhecido ao criar checkout.";
    return {
      ok: false,
      message: `Erro Stripe: ${message}`,
    };
  }
}
