import { NextResponse } from "next/server";
import { extractBearerToken, ensureRelayUser, planExpiryMs, saveRelayUser } from "@/lib/relay-kv";

function isValidEmail(email: string): boolean {
  const normalized = email.trim().toLowerCase();
  if (!normalized) return false;
  if (normalized.startsWith("relay_")) return false;
  if (normalized.endsWith("@cyanbridge.placeholder")) return false;
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalized);
}

function isSubscriptionActive(subStatus: string, expiresAtMs: number): boolean {
  if (subStatus === "active" || subStatus === "trial") {
    return expiresAtMs <= 0 || expiresAtMs > Date.now();
  }
  return false;
}

export async function POST(request: Request) {
  const apiToken = extractBearerToken(request) ?? "";
  if (!apiToken) {
    return NextResponse.json({ ok: false, error: "Missing bearer token" }, { status: 401 });
  }

  let body: Record<string, unknown> = {};
  try {
    body = (await request.json()) as Record<string, unknown>;
  } catch {
    body = {};
  }

  const email = String(body.email ?? "").trim().toLowerCase();
  if (!isValidEmail(email)) {
    return NextResponse.json({ ok: false, error: "A valid email is required for the free trial." }, { status: 400 });
  }

  const user = await ensureRelayUser(apiToken, email);
  const alreadyActive = isSubscriptionActive(user.subscriptionStatus, Number(user.expiresAtMs));
  if (alreadyActive) {
    return NextResponse.json({
      ok: true,
      message: "Your existing Pro subscription is already active.",
      plan: user.plan,
      expires_at_ms: Number(user.expiresAtMs),
      email: user.email ?? email,
    });
  }

  const expiresAtMs = planExpiryMs("free_trial");
  await saveRelayUser({
    ...user,
    email,
    plan: "free_trial",
    subscriptionStatus: "active",
    expiresAtMs,
    billingDay: user.billingDay ?? new Date().getUTCDate(),
    updatedAt: new Date().toISOString(),
  });

  return NextResponse.json({
    ok: true,
    message: "Free trial activated for 30 days.",
    plan: "free_trial",
    expires_at_ms: expiresAtMs,
    email,
  });
}
