import { DiscountType } from "@prisma/client";

export const COMPANY_NAME = "CareLens";
export const GLASSES_PRICE_BRL = 250;
export const MONTHLY_SUBSCRIPTION_BRL = 50;
export const WELCOME_COUPON_CODE = "WELCOME10";
export const WELCOME_COUPON_PERCENT = 10;
export const INSTAGRAM_COUPON_CODE = "INSTAGRAM10";
export const INSTAGRAM_COUPON_PERCENT = 10;
export const AFFILIATE_REVENUE_PERCENT = 5;

export type PromoCoupon = {
  code: string;
  percent: number;
  stripePromotionEnvVar?: string;
  discountType?: DiscountType;
  discountValue?: number;
  isAffiliate?: boolean;
};

export const PROMO_COUPONS: PromoCoupon[] = [
  { code: WELCOME_COUPON_CODE, percent: WELCOME_COUPON_PERCENT, stripePromotionEnvVar: "STRIPE_PROMOTION_CODE_WELCOME10" },
  { code: INSTAGRAM_COUPON_CODE, percent: INSTAGRAM_COUPON_PERCENT },
];

/** Format BRL price consistently: R$ 250,00 */
export function formatPriceBrl(value: number): string {
  const fixed = Number(value).toFixed(2);
  const [intPart, decimalPart] = fixed.split(".");
  const withThousands = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ".");
  return `R$ ${withThousands},${decimalPart}`;
}

export function applyPercentDiscount(valueBrl: number, percent: number): number {
  const discounted = Math.round(valueBrl * (1 - percent / 100) * 100) / 100;
  return Math.max(discounted, 0);
}

export function applyFixedDiscount(valueBrl: number, amountBrl: number): number {
  return Math.max(Math.round((valueBrl - amountBrl) * 100) / 100, 0);
}

export function applyCouponDiscount(valueBrl: number, coupon: PromoCoupon): number {
  if (coupon.discountType === "FIXED" && coupon.discountValue) {
    return applyFixedDiscount(valueBrl, coupon.discountValue);
  }
  return applyPercentDiscount(valueBrl, coupon.percent);
}

export function calculateAffiliateRevenue(purchaseValue: number): number {
  return Math.round(purchaseValue * (AFFILIATE_REVENUE_PERCENT / 100) * 100) / 100;
}

export function normalizeCouponCode(raw: string): string {
  return raw.trim().toUpperCase().replace(/[^A-Z0-9_-]/g, "").slice(0, 32);
}

export function getPromoCoupon(rawCode: string): PromoCoupon | null {
  const normalized = normalizeCouponCode(rawCode);
  if (!normalized) {
    return null;
  }
  return PROMO_COUPONS.find((coupon) => coupon.code === normalized) ?? null;
}

export const CARELENS_PROCESS_STEPS = [
  {
    title: "1. Escolha seu óculos",
    description:
      "O idoso ou familiar escolhe a cor do óculos HeyCyan (preto ou branco) e finaliza o pagamento.",
  },
  {
    title: "2. Configuração da IA",
    description:
      "A IA é configurada com o perfil de saúde e rotina do idoso durante o onboarding.",
  },
  {
    title: "3. Monitoramento familiar",
    description:
      "Acompanhe a atividade, lembretes e interações da IA pelo painel da família.",
  },
  {
    title: "4. Suporte contínuo",
    description:
      "Nossa equipe oferece suporte técnico e atualizações da IA.",
  },
];

export const CHECKOUT_DISCLOSURES = [
  "O óculos CareLens custa R$ 250,00 com assinatura mensal de R$ 50,00 para serviços de IA.",
  "A assinatura pode ser cancelada após 3 meses do primeiro pagamento.",
  "Garantia de 30 dias de devolução do valor dos óculos.",
];

export const REFUND_RULES = {
  glassesPriceBrl: GLASSES_PRICE_BRL,
  refundWindowDays: 30,
  subscriptionMinMonths: 3,
};
