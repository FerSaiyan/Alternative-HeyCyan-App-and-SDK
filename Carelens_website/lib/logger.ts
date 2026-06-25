/**
 * Simple structured logger for key application events.
 *
 * In production, replace console.log with a proper logging service (e.g., Datadog, Sentry, etc.).
 *
 * Event naming convention:
 * - coupon_cut
 * - payment_intent_created
 * - payment_intent_failed
 * - webhook_received
 * - user_created
 */

type LogLevel = "info" | "warn" | "error";

type LogContext = Record<string, unknown>;

interface LogEntry {
  timestamp: string;
  level: LogLevel;
  event: string;
  context?: LogContext;
  message?: string;
}

function formatLogEntry(level: LogLevel, event: string, message?: string, context?: LogContext): LogEntry {
  return {
    timestamp: new Date().toISOString(),
    level,
    event,
    message,
    context,
  };
}

function outputLog(entry: LogEntry): void {
  const formatted = JSON.stringify(entry);
  switch (entry.level) {
    case "error":
      console.error(formatted);
      break;
    case "warn":
      console.warn(formatted);
      break;
    default:
      console.log(formatted);
  }
}

export function logInfo(event: string, message?: string, context?: LogContext): void {
  outputLog(formatLogEntry("info", event, message, context));
}

export function logWarn(event: string, message?: string, context?: LogContext): void {
  outputLog(formatLogEntry("warn", event, message, context));
}

export function logError(event: string, message?: string, context?: LogContext): void {
  outputLog(formatLogEntry("error", event, message, context));
}

// Convenience helpers for payment flows
export function logCouponCut(params: { userId: string; purchaseType: string; couponCode?: string }): void {
  logInfo("coupon_cut", "Coupon cut by user", params);
}

export function logPaymentIntentCreated(params: {
  userId: string;
  purchaseType: string;
  amountBrl: number;
  couponApplied: boolean;
}): void {
  logInfo("payment_intent_created", "Payment intent created in Stripe", params);
}

export function logPaymentIntentFailed(params: { userId: string; purchaseType: string; error: string }): void {
  logError("payment_intent_failed", "Payment intent creation failed", params);
}

export function logWebhookReceived(params: { eventType: string; eventId: string }): void {
  logInfo("webhook_received", "Stripe webhook received", params);
}

export function logUserCreated(params: { userId: string; email: string }): void {
  logInfo("user_created", "User created or updated", params);
}

export function logAsaasWebhookReceived(params: { eventType: string; eventId: string; paymentId?: string }): void {
  logInfo("asaas_webhook_received", "Asaas webhook received", params);
}
