import { kv } from "@vercel/kv";

const hasRemoteKv = Boolean(process.env.KV_REST_API_URL && process.env.KV_REST_API_TOKEN);

type MemoryKvStore = Map<string, unknown>;

declare global {
  var __carelensRelayKvMemory: MemoryKvStore | undefined;
}

function memoryStore(): MemoryKvStore {
  if (!globalThis.__carelensRelayKvMemory) {
    globalThis.__carelensRelayKvMemory = new Map<string, unknown>();
  }
  return globalThis.__carelensRelayKvMemory;
}

async function kvGet<T>(key: string): Promise<T | null> {
  if (hasRemoteKv) {
    const value = await kv.get<T>(key);
    return value ?? null;
  }
  return (memoryStore().get(key) as T | undefined) ?? null;
}

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

export interface RelayUser {
  id: string;
  apiToken: string;
  email: string | null;
  plan: string;
  subscriptionStatus: string;
  expiresAtMs: number;
  asaasCustomerId: string | null;
  asaasSubscriptionId: string | null;
  quotaPeriodKey?: string | null;
  quotaUsedReferenceTokens?: number;
  quotaSpentUsd?: number;
  createdAt: string;
  updatedAt: string;
}

function normalizeRelayEmail(email?: string | null): string {
  return (email ?? "").trim().toLowerCase();
}

function userKey(apiToken: string): string {
  return `relay:user:${apiToken}`;
}

function userIdKey(id: string): string {
  return `relay:user_id:${id}`;
}

function userEmailKey(email: string): string {
  return `relay:user_email:${normalizeRelayEmail(email)}`;
}

export async function getRelayUserByToken(apiToken: string): Promise<RelayUser | null> {
  const user = await kvGet<RelayUser>(userKey(apiToken));
  return user ?? null;
}

export async function getRelayUserById(id: string): Promise<RelayUser | null> {
  const apiToken = await kvGet<string>(userIdKey(id));
  if (!apiToken) return null;
  return getRelayUserByToken(apiToken);
}

export async function getRelayUserByEmail(email: string): Promise<RelayUser | null> {
  const normalizedEmail = normalizeRelayEmail(email);
  if (!normalizedEmail) return null;

  const apiToken = await kvGet<string>(userEmailKey(normalizedEmail));
  if (apiToken) {
    return getRelayUserByToken(apiToken);
  }

  if (!hasRemoteKv) {
    for (const [key, value] of memoryStore().entries()) {
      if (!key.startsWith("relay:user:")) continue;
      const user = value as RelayUser | undefined;
      if (normalizeRelayEmail(user?.email) === normalizedEmail) {
        return user ?? null;
      }
    }
    return null;
  }

  let cursor = 0;
  do {
    const [nextCursor, keys] = await kv.scan(cursor, { match: "relay:user:*", count: 100 });
    cursor = Number(nextCursor);

    for (const key of keys) {
      const user = await kv.get<RelayUser>(key);
      if (normalizeRelayEmail(user?.email) === normalizedEmail) {
        return user;
      }
    }
  } while (cursor !== 0);

  return null;
}

export async function saveRelayUser(user: RelayUser): Promise<void> {
  const existing = await getRelayUserByToken(user.apiToken);
  const normalizedEmail = normalizeRelayEmail(user.email);
  const previousEmail = normalizeRelayEmail(existing?.email);

  if (hasRemoteKv) {
    const pipeline = kv.pipeline();
    pipeline.set(userKey(user.apiToken), user);
    pipeline.set(userIdKey(user.id), user.apiToken);
    if (normalizedEmail) {
      pipeline.set(userEmailKey(normalizedEmail), user.apiToken);
    }
    if (previousEmail && previousEmail !== normalizedEmail) {
      pipeline.del(userEmailKey(previousEmail));
    }
    await pipeline.exec();
    return;
  }

  const store = memoryStore();
  store.set(userKey(user.apiToken), user);
  store.set(userIdKey(user.id), user.apiToken);
  if (normalizedEmail) {
    store.set(userEmailKey(normalizedEmail), user.apiToken);
  }
  if (previousEmail && previousEmail !== normalizedEmail) {
    store.delete(userEmailKey(previousEmail));
  }
}

export async function ensureRelayUser(apiToken?: string, email?: string): Promise<RelayUser> {
  const normalizedEmail = normalizeRelayEmail(email);

  if (apiToken) {
    const existing = await getRelayUserByToken(apiToken);
    if (existing) {
      if (normalizedEmail) {
        const existingByEmail = await getRelayUserByEmail(normalizedEmail);
        if (existingByEmail && existingByEmail.apiToken !== existing.apiToken) {
          return existingByEmail;
        }

        if (normalizeRelayEmail(existing.email) !== normalizedEmail) {
          const updated: RelayUser = {
            ...existing,
            email: normalizedEmail,
            updatedAt: new Date().toISOString(),
          };
          await saveRelayUser(updated);
          return updated;
        }
      }
      return existing;
    }
  }

  if (normalizedEmail) {
    const existingByEmail = await getRelayUserByEmail(normalizedEmail);
    if (existingByEmail) {
      return existingByEmail;
    }
  }

  const id = `relay_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`;
  const token = apiToken || `relay_${Math.random().toString(36).slice(2)}${Date.now().toString(36)}`;
  const now = new Date().toISOString();

  const user: RelayUser = {
    id,
    apiToken: token,
    email: normalizedEmail || null,
    plan: "free_trial",
    subscriptionStatus: "inactive",
    expiresAtMs: 0,
    asaasCustomerId: null,
    asaasSubscriptionId: null,
    quotaPeriodKey: null,
    quotaUsedReferenceTokens: 0,
    quotaSpentUsd: 0,
    createdAt: now,
    updatedAt: now,
  };

  await saveRelayUser(user);
  return user;
}

export async function updateRelayUser(
  apiToken: string,
  updates: Partial<Omit<RelayUser, "id" | "apiToken" | "createdAt">>,
): Promise<RelayUser | null> {
  const user = await getRelayUserByToken(apiToken);
  if (!user) return null;

  const updated: RelayUser = {
    ...user,
    ...updates,
    updatedAt: new Date().toISOString(),
  };

  await saveRelayUser(updated);
  return updated;
}

export async function findRelayUserByAsaasSubscription(
  asaasSubscriptionId: string,
): Promise<RelayUser | null> {
  if (!hasRemoteKv) {
    for (const [key, value] of memoryStore().entries()) {
      if (!key.startsWith("relay:user:")) continue;
      const user = value as RelayUser | undefined;
      if (user?.asaasSubscriptionId === asaasSubscriptionId) {
        return user;
      }
    }
    return null;
  }

  // Scan all relay users to find by asaasSubscriptionId
  // This is acceptable for the small scale of relay users
  let cursor = 0;
  do {
    const [nextCursor, keys] = await kv.scan(cursor, { match: "relay:user:*", count: 100 });
    cursor = Number(nextCursor);

    for (const key of keys) {
      const user = await kv.get<RelayUser>(key);
      if (user?.asaasSubscriptionId === asaasSubscriptionId) {
        return user;
      }
    }
  } while (cursor !== 0);

  return null;
}
