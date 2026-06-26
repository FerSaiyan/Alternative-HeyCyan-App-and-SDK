import { NextResponse } from "next/server";
import { buildRelayQuotaSnapshot, getRelayQuotaSnapshotFromRequest } from "@/lib/openrouter";

async function requestedModel(request: Request, bodyModel?: string): Promise<string> {
  if (bodyModel?.trim()) return bodyModel.trim();
  const { searchParams } = new URL(request.url);
  return searchParams.get("model")?.trim() || "auto";
}

export async function GET(request: Request) {
  const model = await requestedModel(request);
  const { user } = await getRelayQuotaSnapshotFromRequest(request, model);
  const quota = await buildRelayQuotaSnapshot(user, model);
  return NextResponse.json({
    used: quota.used,
    limit: quota.limit,
    remaining: quota.remaining,
    reset_at_ms: quota.resetAtMs,
    model: quota.model,
    plan: quota.plan,
    price_monthly_usd: quota.priceMonthlyUsd,
    spent_usd: quota.spentUsd,
    quota_multiplier: quota.quotaMultiplier,
    actual_quota_multiplier: quota.actualQuotaMultiplier,
    reference_model: quota.referenceModel,
  });
}

export async function POST(request: Request) {
  let body: Record<string, unknown> = {};
  try {
    body = (await request.json()) as Record<string, unknown>;
  } catch {
    // ignore
  }
  const model = await requestedModel(request, String(body.model ?? ""));
  const { user } = await getRelayQuotaSnapshotFromRequest(request, model);
  const quota = await buildRelayQuotaSnapshot(user, model);
  return NextResponse.json({
    used: quota.used,
    limit: quota.limit,
    remaining: quota.remaining,
    reset_at_ms: quota.resetAtMs,
    model: quota.model,
    plan: quota.plan,
    price_monthly_usd: quota.priceMonthlyUsd,
    spent_usd: quota.spentUsd,
    quota_multiplier: quota.quotaMultiplier,
    actual_quota_multiplier: quota.actualQuotaMultiplier,
    reference_model: quota.referenceModel,
  });
}
