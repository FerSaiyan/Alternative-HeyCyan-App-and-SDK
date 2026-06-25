import { NextResponse } from "next/server";
import { AsaasPaymentStatus, Prisma } from "@prisma/client";
import { prisma } from "@/lib/prisma";
import { logInfo, logWarn, logError } from "@/lib/logger";

/* ------------------------------------------------------------------ */
/*  Asaas event type → local status mapping                            */
/* ------------------------------------------------------------------ */

const EVENT_STATUS_MAP: Record<string, string> = {
  PAYMENT_CREATED: "PENDING",
  PAYMENT_AWAITING_RISK_ANALYSIS: "PENDING",
  PAYMENT_PENDING_CONFIRMATION: "PENDING",
  PAYMENT_CONFIRMED: "CONFIRMED",
  PAYMENT_RECEIVED: "RECEIVED",
  PAYMENT_OVERDUE: "OVERDUE",
  PAYMENT_REFUNDED: "REFUNDED",
  PAYMENT_PARTIALLY_REFUNDED: "PARTIAL",
  PAYMENT_CANCELED: "CANCELED",
  PAYMENT_CHARGEBACK_REQUESTED: "REFUNDED",
  PAYMENT_CHARGEBACK_DISPUTE: "REFUNDED",
  PAYMENT_AWAITING_CHARGEBACK_REVERSAL: "REFUNDED",
  PAYMENT_DUNNING_RECEIVED: "RECEIVED",
  PAYMENT_DUNNING_REQUESTED: "PENDING",
  PAYMENT_BANK_SLIP_VIEWED: "PENDING",
  PAYMENT_CHECKOUT_VIEWED: "PENDING",
};

/* Subscription events map to their statuses */
const SUBSCRIPTION_STATUS_MAP: Record<string, string> = {
  SUBSCRIPTION_CREATED: "ACTIVE",
  SUBSCRIPTION_UPDATED: "ACTIVE",
  SUBSCRIPTION_ACTIVATED: "ACTIVE",
  SUBSCRIPTION_SUSPENDED: "INACTIVE",
  SUBSCRIPTION_CANCELED: "CANCELED",
  SUBSCRIPTION_EXPIRED: "EXPIRED",
};

/* ------------------------------------------------------------------ */
/*  Helpers                                                            */
/* ------------------------------------------------------------------ */

function getWebhookToken(): string | undefined {
  return process.env.ASAAS_WEBHOOK_TOKEN?.trim();
}

function logWebhookEvent(params: {
  eventType: string;
  eventId: string;
  paymentId?: string;
  subscriptionId?: string;
}): void {
  logInfo("asaas_webhook_received", "Asaas webhook received", params);
}

/* ------------------------------------------------------------------ */
/*  Event processing helpers                                           */
/* ------------------------------------------------------------------ */

/**
 * Update local AsaasPayment status from a PAYMENT_* webhook event.
 *
 * If the payment cannot be found by `paymentId` (which happens for
 * subscription-linked payments where asaasPaymentId starts as null),
 * falls back to lookup by `subscriptionId` + null asaasPaymentId.
 * When found via subscription linkage, the real asaasPaymentId is set.
 */
async function processPaymentEvent(
  paymentId: string,
  eventType: string,
  paymentStatus: string | null,
  subscriptionId?: string | null,
): Promise<void> {
  try {
    let localPayment = await prisma.asaasPayment.findUnique({
      where: { asaasPaymentId: paymentId },
    });

    // Not found by paymentId — try subscription linkage
    if (!localPayment && subscriptionId) {
      localPayment = await prisma.asaasPayment.findFirst({
        where: {
          asaasSubscriptionId: subscriptionId,
          asaasPaymentId: null,
        },
        // Deterministic: when multiple rows share the same asaasSubscriptionId
        // and have null asaasPaymentId (possible during race conditions), pick
        // the earliest-created row so the fallback is repeatable.
        orderBy: { createdAt: "asc" },
      });
    }

    if (localPayment) {
      const statusCandidate = EVENT_STATUS_MAP[eventType] || paymentStatus || localPayment.status;
      const nextStatus: AsaasPaymentStatus = Object.values(AsaasPaymentStatus).includes(
        statusCandidate as AsaasPaymentStatus,
      )
        ? (statusCandidate as AsaasPaymentStatus)
        : localPayment.status;

      const updateData: Record<string, unknown> = { status: nextStatus };

      // Set asaasPaymentId if it was null (first time we see the real payment ID)
      if (!localPayment.asaasPaymentId && paymentId) {
        updateData.asaasPaymentId = paymentId;
      }

      await prisma.asaasPayment.update({
        where: { id: localPayment.id },
        data: updateData,
      });
      logInfo("asaas_payment_status_updated", "Payment status updated from webhook", {
        paymentId,
        localPaymentId: localPayment.id,
        oldStatus: localPayment.status,
        newStatus: nextStatus,
        eventType,
        asaasPaymentIdSet: !localPayment.asaasPaymentId,
      });
    } else {
      logInfo("asaas_webhook_orphan_payment", "Webhook for unknown payment (race: local record may not exist yet)", {
        paymentId,
        eventType,
        subscriptionId: subscriptionId ?? undefined,
      });
    }
  } catch (err) {
    logError("asaas_webhook_status_update_failed", "Failed to update local payment status", {
      paymentId,
      eventType,
      error: String(err),
    });
  }
}

/**
 * Update local AsaasSubscription status from a SUBSCRIPTION_* webhook event.
 */
async function processSubscriptionEvent(
  subscriptionId: string,
  eventType: string,
): Promise<void> {
  try {
    const localSubscription = await prisma.asaasSubscription.findUnique({
      where: { asaasSubscriptionId: subscriptionId },
    });

    if (localSubscription) {
      const nextStatus = SUBSCRIPTION_STATUS_MAP[eventType] || localSubscription.status;

      await prisma.asaasSubscription.update({
        where: { id: localSubscription.id },
        data: { status: nextStatus },
      });
      logInfo("asaas_subscription_status_updated", "Subscription status updated from webhook", {
        subscriptionId,
        localSubscriptionId: localSubscription.id,
        oldStatus: localSubscription.status,
        newStatus: nextStatus,
        eventType,
      });

      // Also update linked payment records
      await prisma.asaasPayment.updateMany({
        where: { asaasSubscriptionId: subscriptionId },
        data: {
          status: nextStatus === "ACTIVE" ? "CONFIRMED" : "CANCELED",
        },
      });
    } else {
      logInfo("asaas_webhook_orphan_subscription", "Webhook for unknown subscription", {
        subscriptionId,
        eventType,
      });
    }

    // Sync RelayUser subscription status via KV
    try {
      const { findRelayUserByAsaasSubscription, saveRelayUser } = await import("@/lib/relay-kv");
      const relayUser = await findRelayUserByAsaasSubscription(subscriptionId);

      if (relayUser) {
        const isActive = SUBSCRIPTION_STATUS_MAP[eventType] === "ACTIVE";
        const nextRelayStatus = isActive ? "active" : "inactive";
        const expiresAtMs = isActive
          ? Date.now() + 31 * 24 * 60 * 60 * 1000
          : 0;

        await saveRelayUser({
          ...relayUser,
          subscriptionStatus: nextRelayStatus,
          expiresAtMs,
          updatedAt: new Date().toISOString(),
        });

        logInfo("relay_user_subscription_synced", "RelayUser subscription status synced from webhook", {
          relayUserId: relayUser.id,
          asaasSubscriptionId: subscriptionId,
          eventType,
          newStatus: nextRelayStatus,
        });
      }
    } catch (relayErr) {
      logError("relay_user_sync_failed", "Failed to sync RelayUser from webhook", {
        subscriptionId,
        eventType,
        error: String(relayErr),
      });
    }
  } catch (err) {
    logError("asaas_webhook_subscription_update_failed", "Failed to update local subscription status", {
      subscriptionId,
      eventType,
      error: String(err),
    });
  }
}

/* ------------------------------------------------------------------ */
/*  POST                                                               */
/* ------------------------------------------------------------------ */

/**
 * Asaas webhook ingestion endpoint.
 *
 * Expected payload (POST):
 * ```json
 * {
 *   "id": "evt_xxxxxxxx",
 *   "event": "PAYMENT_RECEIVED",
 *   "payment": {
 *     "id": "pay_xxxxxxxx",
 *     "status": "RECEIVED",
 *     ...
 *   }
 * }
 * ```
 *
 * For subscription events:
 * ```json
 * {
 *   "id": "evt_xxxxxxxx",
 *   "event": "SUBSCRIPTION_CANCELED",
 *   "subscription": {
 *     "id": "sub_xxxxxxxx",
 *     ...
 *   }
 * }
 * ```
 *
 * Authentication: `asaas-access-token` header must match ASAAS_WEBHOOK_TOKEN env var (if configured).
 * Idempotency: event `id` is stored uniquely — duplicate events return 200 without processing.
 */
export async function POST(request: Request) {
  /* --- 1. Webhook auth token verification --- */
  const configuredToken = getWebhookToken();
  const isProduction = process.env.NODE_ENV === "production";

  if (isProduction && !configuredToken) {
    logError("asaas_webhook_misconfig", "ASAAS_WEBHOOK_TOKEN is not configured in production");
    return NextResponse.json(
      { ok: false, message: "Server misconfiguration: webhook token not set" },
      { status: 500 },
    );
  }

  if (configuredToken) {
    const headerToken = request.headers.get("asaas-access-token") ?? "";
    if (!headerToken || headerToken !== configuredToken) {
      logWarn("asaas_webhook_auth_failed", "Asaas webhook token mismatch or missing");
      return NextResponse.json(
        { ok: false, message: "Unauthorized" },
        { status: 401 },
      );
    }
  }

  /* --- 2. Parse payload --- */
  let payload: Record<string, unknown>;
  try {
    payload = (await request.json()) as Record<string, unknown>;
  } catch {
    return NextResponse.json({ ok: false, message: "Invalid JSON payload" }, { status: 400 });
  }

  const eventId = String(payload.id ?? "").trim();
  const eventType = String(payload.event ?? "").trim();

  // Payment event fields — the payment object may also carry a `subscription` field
  // for payments generated by a subscription (contains the sub_* ID)
  const paymentObj = payload.payment as Record<string, unknown> | undefined;
  const paymentId = paymentObj ? String(paymentObj.id ?? "").trim() : null;
  const paymentStatus = paymentObj ? String(paymentObj.status ?? "").trim() : null;
  const paymentSubscriptionId = paymentObj
    ? String(paymentObj.subscription ?? "").trim() || null
    : null;

  // Subscription event fields
  const subscriptionObj = payload.subscription as Record<string, unknown> | undefined;
  const subscriptionId = subscriptionObj
    ? String(subscriptionObj.id ?? "").trim()
    : String(payload.subscriptionId ?? "").trim() || null;

  if (!eventId || !eventType) {
    return NextResponse.json(
      { ok: false, message: "Missing event id or event type" },
      { status: 400 },
    );
  }

  /* --- 3. Idempotency: skip if event already processed --- */
  try {
    await prisma.asaasWebhookEvent.create({
      data: {
        asaasEventId: eventId,
        eventType,
        paymentId,
        paymentStatus,
        subscriptionId,
        rawPayload: JSON.stringify(payload),
      },
    });
  } catch (error) {
    if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === "P2002") {
      // Duplicate event — already processed, ack 200
      return NextResponse.json({ ok: true, message: "Duplicate event ignored (idempotency)" });
    }
    logError("asaas_webhook_persistence_failed", "Failed to persist webhook event", {
      eventId,
      eventType,
      error: String(error),
    });
    throw error;
  }

  logWebhookEvent({
    eventType,
    eventId,
    paymentId: paymentId ?? undefined,
    subscriptionId: subscriptionId ?? undefined,
  });

  /* --- 4. Route to appropriate handler based on event type --- */
  const isPaymentEvent = eventType.startsWith("PAYMENT_");
  const isSubscriptionEvent = eventType.startsWith("SUBSCRIPTION_");

  if (isPaymentEvent && paymentId) {
    // For PAYMENT_* events, pass any subscription link from the payment object
    await processPaymentEvent(paymentId, eventType, paymentStatus, paymentSubscriptionId);
  } else if (isSubscriptionEvent && subscriptionId) {
    await processSubscriptionEvent(subscriptionId, eventType);

    // Subscription events may also carry a payment object for the first invoice
    if (paymentId) {
      await processPaymentEvent(paymentId, eventType, paymentStatus, subscriptionId);
    }
  } else {
    logInfo("asaas_webhook_unhandled_event", "Unhandled webhook event type", {
      eventType,
      eventId,
    });
  }

  /* --- 5. Always ack 200 after persistence --- */
  return NextResponse.json({ ok: true, message: "Webhook processed" });
}
