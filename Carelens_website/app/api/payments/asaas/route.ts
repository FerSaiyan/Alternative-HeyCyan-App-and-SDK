import { NextResponse } from "next/server";
import { AsaasPaymentStatus } from "@prisma/client";
import {
  isAsaasConfigured,
  createOrFindCustomer,
  createPayment,
  getPixQrCode,
  getBoletoDetails,
} from "@/lib/asaas";
import {
  GLASSES_PRICE_BRL,
  MONTHLY_SUBSCRIPTION_BRL,
  applyPercentDiscount,
  applyCouponDiscount,
  getPromoCoupon,
  normalizeCouponCode,
  formatPriceBrl,
} from "@/lib/business";
import { resolveCoupon, recordAffiliatePurchase } from "@/lib/coupon-resolver";
import { ensureUserFromEmail, extractCookieValue, getSessionUserById, parseUserIdCookie } from "@/lib/session-user";
import { prisma } from "@/lib/prisma";
import { logPaymentIntentCreated, logPaymentIntentFailed, logInfo, logWarn } from "@/lib/logger";
import { CheckoutPurchaseType } from "@/lib/stripe";

/* ------------------------------------------------------------------ */
/*  Supported billing types                                            */
/* ------------------------------------------------------------------ */

type BillingType = "PIX" | "BOLETO" | "CREDIT_CARD";
const VALID_BILLING_TYPES: BillingType[] = ["PIX", "BOLETO", "CREDIT_CARD"];

/* ------------------------------------------------------------------ */
/*  Parsing helpers (mirror /api/payments/intent)                      */
/* ------------------------------------------------------------------ */

function parseOptionalInt(value: string): number | null {
  const normalized = value.trim();
  if (!normalized) return null;
  const parsed = Number.parseInt(normalized, 10);
  return Number.isFinite(parsed) ? parsed : null;
}

function parseOptionalFloat(value: string): number | null {
  const normalized = value.trim().replace(",", ".");
  if (!normalized) return null;
  const parsed = Number.parseFloat(normalized);
  return Number.isFinite(parsed) ? parsed : null;
}

type SupportedSex = "MALE" | "FEMALE" | "PREFER_NOT_SAY";

function parseSex(value: string): SupportedSex | null {
  if (value === "MALE" || value === "FEMALE" || value === "PREFER_NOT_SAY") return value;
  return null;
}

function parsePurchaseType(value: string): CheckoutPurchaseType {
  return value === "one_time" ? "one_time" : "subscription";
}

function parseDateOfBirth(value: string): Date | undefined {
  const normalized = value.trim();
  if (!normalized) return undefined;
  const parsed = new Date(`${normalized}T00:00:00.000Z`);
  return Number.isNaN(parsed.getTime()) ? undefined : parsed;
}

function parseBillingType(value: string): BillingType {
  const upper = value.toUpperCase().trim();
  if (VALID_BILLING_TYPES.includes(upper as BillingType)) {
    return upper as BillingType;
  }
  return "PIX"; // default fallback
}

/** Return local date in "YYYY-MM-DD" for Asaas dueDate (D+2 ~ sandbox convenience). */
function dueDateFromToday(addDays = 2): string {
  const d = new Date();
  d.setHours(12, 0, 0, 0);
  d.setDate(d.getDate() + addDays);
  const year = d.getFullYear();
  const month = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

/* ------------------------------------------------------------------ */
/*  Types                                                              */
/* ------------------------------------------------------------------ */

type AsaasRequestPayload = {
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
  /** Payment method: PIX (default), BOLETO, or CREDIT_CARD */
  billingType?: string;
  /** Installment count (≥2 for parcelado). Omit or 1 for single charge. */
  installmentCount?: string;
  /** Value per installment (optional, derived from total if omitted). */
  installmentValue?: string;
  /** CPF or CNPJ (document) for Asaas customer identification. */
  cpfCnpj?: string;
};

/* ------------------------------------------------------------------ */
/*  Validation                                                         */
/* ------------------------------------------------------------------ */

function validatePaymentInput(payload: AsaasRequestPayload): string | null {
  const installmentCount = parseOptionalInt(payload.installmentCount ?? "");
  const cpfCnpj = String(payload.cpfCnpj ?? "").replace(/\D/g, "");

  if (!cpfCnpj) {
    return "CPF/CNPJ é obrigatório para gerar cobrança no Asaas.";
  }

  if (installmentCount !== null) {
    if (installmentCount < 2) {
      return "installmentCount deve ser ≥ 2 para parcelamento, ou omitido para pagamento à vista.";
    }
    if (installmentCount > 6) {
      return "installmentCount máximo é 6.";
    }
  }

  return null; // valid
}

function buildPublicAsaasError(message: string): string {
  const lower = message.toLowerCase();
  if (lower.includes("cpf ou cnpj do cliente")) {
    return "CPF/CNPJ obrigatório para gerar pagamento no Asaas.";
  }
  if (lower.includes("chave de api fornecida é inválida") || lower.includes("api 401")) {
    return "Falha de configuracao no Asaas: token invalido (ASAAS_API_KEY).";
  }
  if (lower.includes("api 403")) {
    return "Falha de autorizacao no Asaas: verifique permissao da conta/token.";
  }
  if (lower.includes("api 5")) {
    return "Servico de pagamento indisponivel no Asaas no momento. Tente novamente em instantes.";
  }
  return "Erro ao processar pagamento no Asaas. Tente novamente.";
}

function buildAmountLabel(params: {
  purchaseType: CheckoutPurchaseType;
  billingType: BillingType;
  finalPrice: number;
  installmentCount?: number;
}): string {
  if (params.billingType === "CREDIT_CARD" && params.installmentCount && params.installmentCount >= 2) {
    const installmentValue = Math.round((params.finalPrice / params.installmentCount) * 100) / 100;
    return `${formatPriceBrl(installmentValue)}/mês em ${params.installmentCount}x`;
  }

  if (params.purchaseType === "subscription") {
    return `${formatPriceBrl(params.finalPrice)}/mês (assinatura IA)`;
  }

  return formatPriceBrl(params.finalPrice);
}

/* ------------------------------------------------------------------ */
/*  POST                                                               */
/* ------------------------------------------------------------------ */

export async function POST(request: Request) {
  /* --- 1. Check Asaas is configured --- */
  if (!isAsaasConfigured()) {
    return NextResponse.json(
      {
        ok: false,
        available: false,
        message: "Asaas nao configurado. Defina ASAAS_API_KEY (ou ASAAS_SANDBOX_API_KEY).",
      },
      { status: 501 },
    );
  }

  /* --- 2. Parse payload --- */
  let payload: AsaasRequestPayload;
  try {
    payload = (await request.json()) as AsaasRequestPayload;
  } catch {
    return NextResponse.json({ ok: false, message: "Payload inválido." }, { status: 400 });
  }

  // Validate payment-specific input
  const validationError = validatePaymentInput(payload);
  if (validationError) {
    return NextResponse.json({ ok: false, message: validationError }, { status: 400 });
  }

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
  const promoCoupon = getPromoCoupon(couponCode);
  const resolvedAffiliate = promoCoupon ? null : await resolveCoupon(couponCode);
  const billingType = parseBillingType(payload.billingType ?? "PIX");
  const installmentCount = parseOptionalInt(payload.installmentCount ?? "") ?? undefined;
  const installmentValue = parseOptionalFloat(payload.installmentValue ?? "") ?? undefined;
  const cpfCnpj = String(payload.cpfCnpj ?? "").trim() || undefined;

  /* --- 3. Resolve user --- */
  const cookieHeader = request.headers.get("cookie") ?? "";
  const sessionUserId = parseUserIdCookie(extractCookieValue(cookieHeader, "carelens_user_id"));
  const sessionUser = sessionUserId ? await getSessionUserById(sessionUserId) : null;
  if (!email && !sessionUser) {
    return NextResponse.json({ ok: false, message: "E-mail é obrigatório." }, { status: 400 });
  }
  const user = sessionUser ?? (await ensureUserFromEmail(email));
  const resolvedEmail = sessionUser?.email ?? email;

  /* --- 4. Update user profile --- */
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
    // best-effort; keep checkout functional
  }

  /* --- 5. Calculate price --- */
  const basePrice = purchaseType === "one_time" ? GLASSES_PRICE_BRL : MONTHLY_SUBSCRIPTION_BRL;
  const finalPrice = promoCoupon
    ? applyPercentDiscount(basePrice, promoCoupon.percent)
    : resolvedAffiliate
      ? applyCouponDiscount(basePrice, resolvedAffiliate)
      : basePrice;
  const amountLabel = buildAmountLabel({
    purchaseType,
    billingType,
    finalPrice,
    installmentCount,
  });

  /* --- 6. Duplicate charge protection --- */
  {
    const pendingExisting = await prisma.asaasPayment.findFirst({
      where: {
        userId: user.id,
        purchaseType,
        status: { in: ["PENDING"] },
        billingType,
      },
      orderBy: { createdAt: "desc" },
    });

    if (pendingExisting) {
      // payments should always have asaasPaymentId set; skip if null (defensive)
      if (!pendingExisting.asaasPaymentId) {
        logInfo("asaas_duplicate_skip", "Pending payment missing asaasPaymentId — creating new", {
          localPaymentId: pendingExisting.id,
        });
      } else {
        logInfo("asaas_duplicate_prevented", "Reusing existing pending Asaas payment", {
          userId: user.id,
          asaasPaymentId: pendingExisting.asaasPaymentId,
          localPaymentId: pendingExisting.id,
        });

        try {
          const { getPayment } = await import("@/lib/asaas");
          const payment = await getPayment(pendingExisting.asaasPaymentId);

          // Fetch method-specific details
          let pixQr: { encodedImage: string; payload: string } | null = null;
          let boletoInfo: { bankSlipUrl: string; identificationField: string } | null = null;

          if (billingType === "PIX") {
            try {
              const qrData = await getPixQrCode(pendingExisting.asaasPaymentId);
              pixQr = qrData;
            } catch {
              // QR may expire; non-fatal
            }
          } else if (billingType === "BOLETO") {
            try {
              const details = await getBoletoDetails(pendingExisting.asaasPaymentId);
              if (details) {
                boletoInfo = details;
              }
            } catch {
              // non-fatal
            }
          }

        return NextResponse.json({
          ok: true,
          reused: true,
          provider: "asaas",
          paymentId: payment.id,
          localId: pendingExisting.id,
          status: payment.status,
          billingType,
          invoiceUrl: payment.invoiceUrl ?? null,
          amountLabel,
          pixEncodedImage: pixQr?.encodedImage ?? null,
          pixCopyPaste: pixQr?.payload ?? payment.pixCopyPaste ?? null,
          bankSlipUrl: boletoInfo?.bankSlipUrl ?? payment.bankSlipUrl ?? null,
          identificationField: boletoInfo?.identificationField ?? null,
          dueDate: payment.dueDate,
        });
      } catch {
        // If reuse fails, fall through to create new
        logInfo("asaas_reuse_failed", "Failed to reuse existing payment, creating new", {
          asaasPaymentId: pendingExisting.asaasPaymentId,
        });
      }
    }
  }

  }

  /* --- 7. Create Asaas customer + payment --- */
  try {
    const customer = await createOrFindCustomer({
      name: fullName || resolvedEmail,
      email: resolvedEmail,
      cpfCnpj,
      externalReference: user.id,
    });

    if (cpfCnpj && customer.cpfCnpj !== cpfCnpj) {
      logWarn("asaas_customer_document_mismatch", "Customer CPF/CNPJ mismatch after createOrFind", {
        customerId: customer.id,
        customerCpfCnpj: customer.cpfCnpj ?? null,
        expectedCpfCnpj: cpfCnpj,
      });
    }

    const description =
      purchaseType === "one_time"
        ? "CareLens - Óculos"
        : "CareLens - Assinatura IA";

    /* --- 7. Single charge (PIX, BOLETO, CREDIT_CARD, or parcelado) --- */

    // Determine installmentValue if installmentCount is set
    let resolvedInstallmentValue: number | undefined;
    if (installmentCount && installmentCount >= 2) {
      // If installmentValue not provided, divide total by installmentCount
      resolvedInstallmentValue = installmentValue ?? Math.round((finalPrice / installmentCount) * 100) / 100;
    }

    const payment = await createPayment({
      customerId: customer.id,
      billingType,
      value: finalPrice,
      dueDate: dueDateFromToday(),
      description,
      externalReference: user.id,
      installmentCount,
      installmentValue: resolvedInstallmentValue,
    });

    // Persist local payment record
    let localPaymentId: string | null = null;
    try {
      const localPayment = await prisma.asaasPayment.create({
        data: {
          userId: user.id,
          asaasPaymentId: payment.id,
          asaasCustomerId: customer.id,
          valueBrl: finalPrice,
          billingType,
          purchaseType,
          status: AsaasPaymentStatus.PENDING,
          installmentCount: installmentCount ?? null,
          installmentValue: resolvedInstallmentValue ?? null,
          bankSlipUrl: payment.bankSlipUrl ?? null,
          invoiceUrl: payment.invoiceUrl ?? null,
          couponCode: promoCoupon?.code ?? resolvedAffiliate?.code ?? null,
          externalReference: user.id,
        },
      });
      localPaymentId = localPayment.id;
    } catch (err) {
      logInfo("asaas_local_persist_failed", "Failed to persist local AsaasPayment record", {
        asaasPaymentId: payment.id,
        error: String(err),
      });
    }

    // Fetch method-specific details
    let pixQr: { encodedImage: string; payload: string } | null = null;
    let boletoInfo: { bankSlipUrl: string; identificationField: string } | null = null;

    if (billingType === "PIX") {
      try {
        pixQr = await getPixQrCode(payment.id);
      } catch {
        // QR code may not be immediately ready; non-fatal
      }
    } else if (billingType === "BOLETO") {
      try {
        const details = await getBoletoDetails(payment.id);
        if (details) {
          boletoInfo = details;
        }
      } catch {
        // non-fatal
      }
    }

    const responsePayload = {
      ok: true,
      provider: "asaas",
      paymentId: payment.id,
      localId: localPaymentId,
      status: payment.status,
      billingType,
      invoiceUrl: payment.invoiceUrl ?? null,
      amountLabel,
      pixEncodedImage: pixQr?.encodedImage ?? null,
      pixCopyPaste: pixQr?.payload ?? payment.pixCopyPaste ?? null,
      bankSlipUrl: boletoInfo?.bankSlipUrl ?? payment.bankSlipUrl ?? null,
      identificationField: boletoInfo?.identificationField ?? null,
      installmentCount: installmentCount ?? null,
      installmentValue: resolvedInstallmentValue ?? null,
      dueDate: payment.dueDate,
    };

    logPaymentIntentCreated({
      userId: user.id,
      purchaseType,
      amountBrl: finalPrice,
      couponApplied: Boolean(promoCoupon || resolvedAffiliate),
    });

    if (resolvedAffiliate?.affiliateCouponId) {
      try {
        await recordAffiliatePurchase({
          couponId: resolvedAffiliate.affiliateCouponId,
          buyerId: user.id,
          paymentId: payment.id,
          paymentProvider: "asaas",
          purchaseValue: finalPrice,
        });
      } catch (err) {
        logInfo("affiliate_purchase_record_failed", "Failed to record affiliate purchase", {
          couponId: resolvedAffiliate.affiliateCouponId,
          error: String(err),
        });
      }
    }

    return NextResponse.json(responsePayload);
  } catch (error) {
    const message = error instanceof Error ? error.message : "Erro desconhecido no Asaas.";
    const publicMessage = buildPublicAsaasError(message);

    logPaymentIntentFailed({
      userId: user.id,
      purchaseType,
      error: message,
    });

    return NextResponse.json(
      {
        ok: false,
        message: publicMessage,
        detail: message,
      },
      { status: 500 },
    );
  }
}
