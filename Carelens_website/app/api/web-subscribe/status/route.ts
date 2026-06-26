import { NextResponse } from "next/server";
import { getHostedSubscriptionState, getSubscription, isAsaasConfigured } from "@/lib/asaas";
import { getRelayUserByToken, normalizePlan, planExpiryMs, saveRelayUser } from "@/lib/relay-kv";

export async function GET(request: Request) {
  const { searchParams } = new URL(request.url);
  const apiToken = (searchParams.get("api_token") ?? "").trim();
  const requestedSubscriptionId = (searchParams.get("subscription_id") ?? "").trim();

  if (!apiToken) {
    return NextResponse.json({ ok: false, message: "Missing api_token" }, { status: 400 });
  }

  const user = await getRelayUserByToken(apiToken);
  if (!user) {
    return NextResponse.json({ ok: false, message: "User not found" }, { status: 404 });
  }

  const plan = normalizePlan(searchParams.get("plan") ?? user.plan ?? "standard");
  const subscriptionId = requestedSubscriptionId || user.asaasSubscriptionId || "";
  if (!subscriptionId) {
    return NextResponse.json(
      {
        ok: true,
        active: false,
        state: "inactive",
        plan,
        expires_at_ms: 0,
        message: "No Asaas subscription is linked to this account yet.",
      },
      { headers: { "Cache-Control": "no-store" } },
    );
  }

  if (!isAsaasConfigured()) {
    const active = user.subscriptionStatus === "active";
    return NextResponse.json(
      {
        ok: true,
        active,
        state: active ? "active" : "pending",
        plan,
        expires_at_ms: Number(user.expiresAtMs) || 0,
        message: "Asaas is not configured on this server.",
      },
      { headers: { "Cache-Control": "no-store" } },
    );
  }

  try {
    const subscription = await getSubscription(subscriptionId);
    const paymentSummary = await getHostedSubscriptionState(subscription.id);
    const state = paymentSummary.state;
    const active = state === "active";
    const expiresAtMs = active ? Number(user.expiresAtMs) || planExpiryMs(plan) : 0;

    await saveRelayUser({
      ...user,
      plan,
      subscriptionStatus: active ? "active" : state,
      expiresAtMs,
      asaasSubscriptionId: subscription.id,
      billingDay: active ? (user.billingDay ?? new Date().getUTCDate()) : user.billingDay,
      updatedAt: new Date().toISOString(),
    });

    return NextResponse.json(
      {
        ok: true,
        active,
        state,
        plan,
        expires_at_ms: expiresAtMs,
        asaas_status: subscription.status,
        payment_status: paymentSummary.paymentStatus,
        message: active
          ? "Subscription confirmed."
          : state === "pending"
            ? "Waiting for Asaas to confirm the recurring subscription."
            : "The subscription is not active.",
      },
      { headers: { "Cache-Control": "no-store" } },
    );
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unknown verification error";
    return NextResponse.json(
      {
        ok: false,
        active: false,
        state: "pending",
        plan,
        expires_at_ms: 0,
        message,
      },
      {
        status: 502,
        headers: { "Cache-Control": "no-store" },
      },
    );
  }
}
