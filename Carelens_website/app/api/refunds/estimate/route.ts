import { NextResponse } from "next/server";
import { estimateGlassesRefund, canCancelSubscription } from "@/lib/refund";

type RefundRequestBody = {
  daysSincePurchase?: number;
  monthsPaid?: number;
};

export async function POST(request: Request) {
  const body = (await request.json()) as RefundRequestBody;

  const glassesRefund = estimateGlassesRefund(body.daysSincePurchase ?? 999);
  const subscriptionCancelable = canCancelSubscription(body.monthsPaid ?? 0);

  return NextResponse.json({
    ok: true,
    glassesRefund,
    subscriptionCancelable,
  });
}
