import { NextResponse } from "next/server";
import { logInfo } from "@/lib/logger";

export async function POST(request: Request) {
  let body: Record<string, unknown>;
  try {
    body = (await request.json()) as Record<string, unknown>;
  } catch {
    return NextResponse.json({ error: "invalid_json" }, { status: 400 });
  }

  const installationId = String(body.installation_id ?? "").trim();
  if (!installationId) {
    return NextResponse.json({ error: "installation_id_required" }, { status: 400 });
  }

  const plan = String(body.plan ?? "standard").trim();
  const provider = String(body.provider ?? "").trim();
  const packageName = String(body.package_name ?? "").trim();

  logInfo("beta_cloud_interest", "Beta cloud interest registered", {
    installationId,
    plan,
    provider,
    packageName,
  });

  // Store in a simple way - we can use the RelayUser table or a separate mechanism
  // For now, just acknowledge
  return NextResponse.json({
    accepted: true,
    interested_count: 1,
    message: "Interest registered",
  });
}
