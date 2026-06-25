import { REFUND_RULES } from "@/lib/business";

export type RefundOutcome = {
  eligible: boolean;
  amountBrl: number;
  rationale: string;
};

/** 30-day money-back guarantee on glasses */
export function estimateGlassesRefund(daysSincePurchase: number): RefundOutcome {
  if (daysSincePurchase <= REFUND_RULES.refundWindowDays) {
    return {
      eligible: true,
      amountBrl: REFUND_RULES.glassesPriceBrl,
      rationale: `Devolução do óculos CareLens dentro do prazo de ${REFUND_RULES.refundWindowDays} dias. Reembolso integral de R$ ${REFUND_RULES.glassesPriceBrl},00.`,
    };
  }
  return {
    eligible: false,
    amountBrl: 0,
    rationale: `Prazo de devolução de ${REFUND_RULES.refundWindowDays} dias excedido para o óculos CareLens.`,
  };
}

/** Subscription can be canceled after 3 months from first payment */
export function canCancelSubscription(monthsPaid: number): boolean {
  return monthsPaid >= REFUND_RULES.subscriptionMinMonths;
}
