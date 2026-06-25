import { NextResponse } from "next/server";
import { ensureRelayUser } from "@/lib/relay-kv";

export async function POST(request: Request) {
  let body: Record<string, unknown>;
  try {
    body = (await request.json()) as Record<string, unknown>;
  } catch {
    body = {};
  }

  const apiToken = String(body.api_token ?? "").trim() || undefined;
  const email = String(body.email ?? "").trim().toLowerCase() || undefined;

  const user = await ensureRelayUser(apiToken, email);

  return NextResponse.json({
    api_token: user.apiToken,
    email: user.email ?? "",
    plan: user.plan,
    subscription_status: user.subscriptionStatus,
    expires_at_ms: Number(user.expiresAtMs),
  });
}
