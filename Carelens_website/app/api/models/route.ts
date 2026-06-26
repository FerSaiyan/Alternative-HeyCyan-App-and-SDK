import { NextResponse } from "next/server";
import { extractBearerToken, getRelayUserByToken, normalizePlan } from "@/lib/relay-kv";
import { getVisibleOpenRouterModels } from "@/lib/openrouter";

export async function GET(request: Request) {
  const token = extractBearerToken(request);
  const user = token ? await getRelayUserByToken(token) : null;
  const plan = normalizePlan(user?.plan ?? "standard");

  let data = await getVisibleOpenRouterModels();
  if (plan === "free_trial") {
    data = data.filter((model) => model.id === "openrouter/free");
  }

  return NextResponse.json({
    data,
    reference_model: "deepseek/deepseek-v4-flash",
  });
}

export async function POST(request: Request) {
  return GET(request);
}
