import { NextResponse } from "next/server";
import { deleteSubscription, getHostedSubscriptionState, getSubscription } from "@/lib/asaas";
import { normalizePlan, planExpiryMs, isValidPlan, getRelayUserByToken, saveRelayUser } from "@/lib/relay-kv";
import { logInfo, logError } from "@/lib/logger";

function appendQueryParams(baseUrl: string, params: Record<string, string>): string {
  const url = new URL(baseUrl);
  for (const [k, v] of Object.entries(params)) {
    url.searchParams.set(k, v);
  }
  return url.toString();
}

export async function GET(request: Request) {
  const { searchParams } = new URL(request.url);
  const subscriptionId = searchParams.get("subscription_id") ?? "";
  const returnUrl = searchParams.get("return_url") ?? "";
  const apiToken = searchParams.get("api_token") ?? "";
  const replaceSubscriptionId = (searchParams.get("replace_subscription_id") ?? "").trim();
  let plan = normalizePlan(searchParams.get("plan") ?? "standard");
  if (!isValidPlan(plan)) plan = "standard";

  if (!apiToken) {
    return NextResponse.json({ ok: false, message: "Missing api_token" }, { status: 400 });
  }

  const user = await getRelayUserByToken(apiToken);
  if (!user) {
    return NextResponse.json({ ok: false, message: "User not found" }, { status: 404 });
  }

  const fallbackActive = user.subscriptionStatus === "active" && Number(user.expiresAtMs) > Date.now();
  let subStatus: "active" | "pending" | "inactive" = fallbackActive ? "active" : "pending";
  let asaasSubId = subscriptionId || user.asaasSubscriptionId || "";

  if (asaasSubId) {
    try {
      const sub = await getSubscription(asaasSubId);
      asaasSubId = sub.id;
      const paymentSummary = await getHostedSubscriptionState(asaasSubId);
      subStatus = paymentSummary.state;

      logInfo("relay_subscription_verified", "Subscription verified with Asaas", {
        relayUserId: user.id,
        asaasSubscriptionId: asaasSubId,
        asaasStatus: sub.status,
        mappedStatus: subStatus,
      });
    } catch (error) {
      logError("relay_subscription_verify_failed", "Failed to verify subscription with Asaas", {
        relayUserId: user.id,
        asaasSubscriptionId: asaasSubId,
        error: String(error),
      });
      subStatus = fallbackActive ? "active" : "pending";
    }
  }

  const expiresAtMs = subStatus === "active"
    ? Number(user.expiresAtMs) || planExpiryMs(plan)
    : 0;

  let cancelledPreviousSubscription = false;
  let replaceCancellationFailed = false;
  if (
    subStatus === "active" &&
    replaceSubscriptionId &&
    replaceSubscriptionId !== asaasSubId
  ) {
    try {
      await deleteSubscription(replaceSubscriptionId);
      cancelledPreviousSubscription = true;
      logInfo("relay_subscription_replaced", "Cancelled previous Asaas subscription after successful plan change", {
        relayUserId: user.id,
        previousAsaasSubscriptionId: replaceSubscriptionId,
        newAsaasSubscriptionId: asaasSubId,
      });
    } catch (error) {
      replaceCancellationFailed = true;
      logError("relay_subscription_replace_cancel_failed", "Failed to cancel previous Asaas subscription after plan change", {
        relayUserId: user.id,
        previousAsaasSubscriptionId: replaceSubscriptionId,
        newAsaasSubscriptionId: asaasSubId,
        error: String(error),
      });
    }
  }

  // Update user subscription status via KV
  await saveRelayUser({
    ...user,
    plan,
    subscriptionStatus: subStatus,
    expiresAtMs,
    asaasSubscriptionId: asaasSubId || user.asaasSubscriptionId,
    updatedAt: new Date().toISOString(),
  });

  // If no return URL, return JSON
  if (!returnUrl) {
    return NextResponse.json({
      status: subStatus === "active" ? "success" : subStatus,
      plan,
      api_token: user.apiToken,
      email: user.email ?? "",
      token: asaasSubId || "subscription_activated",
      expires_at_ms: expiresAtMs,
    });
  }

  // Redirect back to app
  const callbackUrl = appendQueryParams(returnUrl, {
    status: subStatus === "active" ? "success" : "pending",
    plan,
    token: asaasSubId || "subscription_activated",
    expires_at_ms: String(expiresAtMs),
    api_token: user.apiToken,
    email: user.email ?? "",
    message: subStatus === "active"
      ? cancelledPreviousSubscription
        ? "Subscription activated and previous recurring plan cancelled"
        : replaceCancellationFailed
          ? "Subscription activated, but the previous recurring plan could not be auto-cancelled. Contact support."
        : "Subscription activated"
      : subStatus === "inactive"
        ? "Subscription is not active"
        : "Payment pending",
  });

  return NextResponse.redirect(callbackUrl, 303);
}
