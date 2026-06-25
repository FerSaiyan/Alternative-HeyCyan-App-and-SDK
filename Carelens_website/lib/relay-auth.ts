import { prisma } from "@/lib/prisma";

export const RELAY_PLANS: Record<string, { priceUsd: number; label: string }> = {
  free_trial: { priceUsd: 0, label: "Free Trial (30 days)" },
  cheap: { priceUsd: 1, label: "Cheap ($1/mo)" },
  standard: { priceUsd: 5, label: "Standard ($5/mo)" },
  max: { priceUsd: 20, label: "Max ($20/mo)" },
};

export function normalizePlan(raw: string): string {
  const p = (raw || "").trim().toLowerCase();
  if (p === "monthly" || p === "standard") return "standard";
  if (p === "max" || p === "premium") return "max";
  if (p === "cheap" || p === "budget" || p === "low") return "cheap";
  if (p === "free_trial" || p === "trial" || p === "free" || p === "freemium") return "free_trial";
  return "standard";
}

export function isValidPlan(plan: string): boolean {
  return plan in RELAY_PLANS;
}

export function planExpiryMs(plan: string): number {
  const days = plan === "free_trial" ? 30 : 31;
  return Date.now() + days * 24 * 60 * 60 * 1000;
}

export function extractBearerToken(request: Request): string | null {
  const auth = request.headers.get("authorization") ?? "";
  if (auth.startsWith("Bearer ")) {
    return auth.slice(7).trim() || null;
  }
  return null;
}

export async function getRelayUserByToken(apiToken: string) {
  return prisma.relayUser.findUnique({ where: { apiToken } });
}

export async function ensureRelayUser(apiToken?: string, email?: string) {
  if (apiToken) {
    const existing = await prisma.relayUser.findUnique({ where: { apiToken } });
    if (existing) return existing;
  }

  const token = apiToken || `relay_${Math.random().toString(36).slice(2)}${Date.now().toString(36)}`;
  return prisma.relayUser.create({
    data: {
      apiToken: token,
      email: email || null,
    },
  });
}
