import { NextResponse } from "next/server";
import { extractBearerToken, getRelayUserByToken, normalizePlan, RELAY_PLANS } from "@/lib/relay-kv";

export async function GET(request: Request) {
  const token = extractBearerToken(request);
  const { searchParams } = new URL(request.url);
  const model = searchParams.get("model") ?? "auto";

  let plan = "standard";
  if (token) {
    const user = await getRelayUserByToken(token);
    if (user) plan = normalizePlan(user.plan);
  }

  const planInfo = RELAY_PLANS[plan];
  const monthlyPrice = planInfo?.priceUsd ?? 5;

  // Simple quota calculation
  const dailyLimits: Record<string, number> = {
    free_trial: 20000,
    cheap: 50000,
    standard: 200000,
    max: 5000000,
  };

  const limit = dailyLimits[plan] ?? 200000;

  return NextResponse.json({
    used: 0,
    limit,
    remaining: limit,
    reset_at_ms: Date.now() + 24 * 60 * 60 * 1000,
    model,
    plan,
    price_monthly_usd: monthlyPrice,
  });
}

export async function POST(request: Request) {
  let body: Record<string, unknown> = {};
  try {
    body = (await request.json()) as Record<string, unknown>;
  } catch {
    // ignore
  }

  const model = String(body.model ?? "auto").trim();

  const token = extractBearerToken(request);
  let plan = "standard";
  if (token) {
    const user = await getRelayUserByToken(token);
    if (user) plan = normalizePlan(user.plan);
  }

  const planInfo = RELAY_PLANS[plan];
  const monthlyPrice = planInfo?.priceUsd ?? 5;

  const dailyLimits: Record<string, number> = {
    free_trial: 20000,
    cheap: 50000,
    standard: 200000,
    max: 5000000,
  };

  const limit = dailyLimits[plan] ?? 200000;

  return NextResponse.json({
    used: 0,
    limit,
    remaining: limit,
    reset_at_ms: Date.now() + 24 * 60 * 60 * 1000,
    model,
    plan,
    price_monthly_usd: monthlyPrice,
  });
}
