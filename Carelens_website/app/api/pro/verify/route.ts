import { NextResponse } from "next/server";
import { extractBearerToken, getRelayUserByToken, normalizePlan, saveRelayUser } from "@/lib/relay-kv";
import { getHostedSubscriptionState, isAsaasConfigured, getSubscription } from "@/lib/asaas";
import { logInfo } from "@/lib/logger";

function isSubscriptionActive(subStatus: string, expiresAtMs: number): boolean {
  if (subStatus === "active" || subStatus === "trial") {
    return expiresAtMs <= 0 || expiresAtMs > Date.now();
  }
  return false;
}

export async function POST(request: Request) {
  const token = extractBearerToken(request);
  if (!token) {
    return NextResponse.json({ active: false, plan: "none", expires_at_ms: 0 });
  }

  const user = await getRelayUserByToken(token);
  if (!user) {
    return NextResponse.json({ active: false, plan: "none", expires_at_ms: 0 });
  }

  let body: Record<string, unknown>;
  try {
    body = (await request.json()) as Record<string, unknown>;
  } catch {
    body = {};
  }

  const plan = normalizePlan(String(body.plan ?? user.plan ?? "standard"));

  // If user has an Asaas subscription, verify with Asaas
  if (user.asaasSubscriptionId && isAsaasConfigured()) {
    try {
      const sub = await getSubscription(user.asaasSubscriptionId);
      const paymentSummary = await getHostedSubscriptionState(user.asaasSubscriptionId);

      const isActive = paymentSummary.state === "active";
      const expiresAtMs = isActive ? Number(user.expiresAtMs) || Date.now() + 31 * 24 * 60 * 60 * 1000 : 0;

      // Update local status via KV
      await saveRelayUser({
        ...user,
        subscriptionStatus: isActive ? "active" : "inactive",
        expiresAtMs,
        billingDay: isActive ? (user.billingDay ?? new Date().getUTCDate()) : user.billingDay,
        updatedAt: new Date().toISOString(),
      });

      logInfo("relay_verify_asaas", "Subscription verified via Asaas", {
        relayUserId: user.id,
        asaasSubscriptionId: user.asaasSubscriptionId,
        asaasStatus: sub.status,
        paymentStatus: paymentSummary.paymentStatus,
        active: isActive,
      });

      return NextResponse.json({
        active: isActive,
        plan: isActive ? plan : "none",
        expires_at_ms: expiresAtMs,
      });
    } catch {
      // Fall through to local check
    }
  }

  // Local status check
  const active = isSubscriptionActive(user.subscriptionStatus, Number(user.expiresAtMs));
  const expiresAtMs = active ? Number(user.expiresAtMs) : 0;

  return NextResponse.json({
    active,
    plan: active ? plan : "none",
    expires_at_ms: expiresAtMs,
  });
}
