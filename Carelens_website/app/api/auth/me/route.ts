import { NextResponse } from "next/server";
import { extractBearerToken, getRelayUserByToken } from "@/lib/relay-kv";

export async function GET(request: Request) {
  const token = extractBearerToken(request);
  if (!token) {
    return NextResponse.json({ error: "missing_token" }, { status: 401 });
  }

  const user = await getRelayUserByToken(token);
  if (!user) {
    return NextResponse.json({ error: "user_not_found" }, { status: 404 });
  }

  return NextResponse.json({
    api_token: user.apiToken,
    email: user.email ?? "",
    plan: user.plan,
    subscription_status: user.subscriptionStatus,
    expires_at_ms: Number(user.expiresAtMs),
  });
}
