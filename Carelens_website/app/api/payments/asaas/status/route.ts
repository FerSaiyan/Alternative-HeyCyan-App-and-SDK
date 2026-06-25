import { NextResponse } from "next/server";
import { Prisma } from "@prisma/client";
import { prisma } from "@/lib/prisma";
import { parseUserIdCookie, extractCookieValue } from "@/lib/session-user";

/* ------------------------------------------------------------------ */
/*  GET                                                                */
/* ------------------------------------------------------------------ */

/**
 * GET /api/payments/asaas/status?ref=<localRef>
 *
 * Returns the current payment status for a local reference (user ID or payment ID).
 * Never treats query params as proof of paid status — always checks DB.
 * Requires authenticated session – user can only query their own payments.
 */
export async function GET(request: Request) {
  const { searchParams } = new URL(request.url);
  const ref = searchParams.get("ref")?.trim();

  if (!ref) {
    return NextResponse.json({ ok: false, message: "Missing ref parameter" }, { status: 400 });
  }

  /* Require authenticated session */
  const cookieHeader = request.headers.get("cookie") ?? "";
  const sessionUserId = parseUserIdCookie(extractCookieValue(cookieHeader, "carelens_user_id"));
  if (!sessionUserId) {
    return NextResponse.json({ ok: false, message: "Unauthorized" }, { status: 401 });
  }

  /* Build query conditions — always scoped to this user */
  const where: Prisma.AsaasPaymentWhereInput = { userId: sessionUserId };

  // If ref looks like an Asaas payment ID (starts with "pay_"), look it up directly
  if (ref.startsWith("pay_")) {
    where.asaasPaymentId = ref;
  } else {
    // Otherwise treat ref as local payment ID (cuid)
    where.id = ref;
  }

  try {
    const payment = await prisma.asaasPayment.findFirst({
      where,
      orderBy: { createdAt: "desc" },
    });

    if (!payment) {
      // Return generic message — don't reveal whether a payment ID exists
      return NextResponse.json({
        ok: true,
        found: false,
        message: "Nenhum pagamento encontrado para esta referência.",
      });
    }

    return NextResponse.json({
      ok: true,
      found: true,
      paymentId: payment.asaasPaymentId,
      localId: payment.id,
      status: payment.status,
      valueBrl: payment.valueBrl,
      purchaseType: payment.purchaseType,
      createdAt: payment.createdAt.toISOString(),
      updatedAt: payment.updatedAt.toISOString(),
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Erro ao consultar pagamento.";
    return NextResponse.json({ ok: false, message }, { status: 500 });
  }
}
