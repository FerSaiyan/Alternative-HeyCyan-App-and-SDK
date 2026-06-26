import {
  extractBearerToken,
  getRelayUserByToken,
  normalizePlan,
  RELAY_PLANS,
  saveRelayUser,
  type RelayUser,
} from "@/lib/relay-kv";

const OPENROUTER_API_KEY = process.env.OPENROUTER_API_KEY ?? "";
const OPENROUTER_BASE_URL = process.env.OPENROUTER_BASE_URL ?? "https://openrouter.ai/api/v1";
export const OPENROUTER_DEFAULT_MODEL = process.env.OPENROUTER_DEFAULT_MODEL ?? "deepseek/deepseek-v4-flash";

const MODEL_CACHE_TTL_MS = 10 * 60 * 1000;

const PLAN_MONTHLY_LIMITS: Record<string, number> = {
  free_trial: 1000000,
  cheap: 3500000,
  standard: 18500000,
  max: 74000000,
};

type PricingField = "prompt" | "completion" | "image" | "audio";

interface CuratedModelConfig {
  id: string;
  label: string;
  quotaMultiplier: number;
  aliases?: string[];
  fallbackPricing: Partial<Record<PricingField, number>>;
}

interface OpenRouterCatalogEntry {
  id: string;
  name: string;
  pricing: Partial<Record<PricingField, number>>;
}

export interface VisibleOpenRouterModel {
  id: string;
  label: string;
  quotaMultiplier: number;
  actualQuotaMultiplier: number;
  promptPriceUsd: number;
  completionPriceUsd: number;
  imagePriceUsd: number;
  audioPriceUsd: number;
}

export interface OpenRouterUsage {
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  imageTokens: number;
  audioTokens: number;
}

export interface RelayQuotaSnapshot {
  used: number;
  limit: number;
  remaining: number;
  resetAtMs: number;
  model: string;
  plan: string;
  priceMonthlyUsd: number;
  spentUsd: number;
  quotaMultiplier: number;
  actualQuotaMultiplier: number;
  referenceModel: string;
}

declare global {
  var __cyanbridgeOpenRouterCatalog:
    | { fetchedAtMs: number; entries: Map<string, OpenRouterCatalogEntry> }
    | undefined;
}

const CURATED_MODELS: CuratedModelConfig[] = [
  {
    id: "openrouter/free",
    label: "Cheap models router",
    quotaMultiplier: 1,
    aliases: ["free models router", "cheap models router", "openrouter free", "free_router"],
    fallbackPricing: { prompt: 0, completion: 0 },
  },
  {
    id: "deepseek/deepseek-v4-flash",
    label: "DeepSeek V4 Flash",
    quotaMultiplier: 2,
    aliases: ["deepseek v4 flash"],
    fallbackPricing: { prompt: 0.00000009, completion: 0.00000018 },
  },
  {
    id: "minimax/minimax-m3",
    label: "MiniMax M3",
    quotaMultiplier: 5,
    aliases: ["minimax m3", "minimax/minimax-m3", "minimax/minimax-m2.5", "minimax m2.5"],
    fallbackPricing: { prompt: 0.0000003, completion: 0.0000012 },
  },
  {
    id: "openai/gpt-5.4",
    label: "GPT 5.4",
    quotaMultiplier: 30,
    aliases: ["gpt-5.4", "openai/gpt-5.4"],
    fallbackPricing: { prompt: 0.0000025, completion: 0.000015 },
  },
  {
    id: "openai/gpt-5.4-nano",
    label: "GPT 5.4 Nano",
    quotaMultiplier: 6,
    aliases: ["gpt-5.4-nano", "gpt 5.4 nano", "openai/gpt-5.4-nano"],
    fallbackPricing: { prompt: 0.0000002, completion: 0.00000125 },
  },
  {
    id: "deepseek/deepseek-v4-pro",
    label: "DeepSeek V4 Pro",
    quotaMultiplier: 5,
    aliases: ["deepseek v4 pro"],
    fallbackPricing: { prompt: 0.000000435, completion: 0.00000087 },
  },
  {
    id: "xiaomi/mimo-v2.5-pro",
    label: "MiMo V2.5 Pro",
    quotaMultiplier: 5,
    aliases: ["mimo v2.5 pro", "xiaomi/mimo-v2.5-pro"],
    fallbackPricing: { prompt: 0.000000435, completion: 0.00000087 },
  },
  {
    id: "google/gemini-3-flash-preview",
    label: "Gemini 3 Flash Preview",
    quotaMultiplier: 13,
    aliases: ["gemini 3 flash preview", "google/gemini-3-flash-preview"],
    fallbackPricing: { prompt: 0.0000005, completion: 0.000003, image: 0.0000005, audio: 0.000001 },
  },
];

const CURATED_MODEL_MAP = new Map(CURATED_MODELS.map((model) => [model.id, model]));
const MODEL_ALIAS_MAP = new Map<string, string>();
for (const model of CURATED_MODELS) {
  MODEL_ALIAS_MAP.set(model.id.toLowerCase(), model.id);
  MODEL_ALIAS_MAP.set(model.label.toLowerCase(), model.id);
  for (const alias of model.aliases ?? []) {
    MODEL_ALIAS_MAP.set(alias.toLowerCase(), model.id);
  }
}

function numberFromUnknown(value: unknown): number {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string") {
    const parsed = Number(value.trim());
    return Number.isFinite(parsed) ? parsed : 0;
  }
  return 0;
}

function averageTextTokenPrice(pricing: Partial<Record<PricingField, number>>): number {
  const prompt = pricing.prompt ?? 0;
  const completion = pricing.completion ?? 0;
  if (prompt > 0 && completion > 0) return (prompt + completion) / 2;
  return Math.max(prompt, completion, 0);
}

const REFERENCE_MODEL_ID = "deepseek/deepseek-v4-flash";
const REFERENCE_MODEL = CURATED_MODEL_MAP.get(REFERENCE_MODEL_ID)!;
const REFERENCE_TOKEN_COST_USD = averageTextTokenPrice(REFERENCE_MODEL.fallbackPricing);

function currentQuotaPeriodKey(now = new Date()): string {
  const year = now.getUTCFullYear();
  const month = String(now.getUTCMonth() + 1).padStart(2, "0");
  return `${year}-${month}`;
}

function nextQuotaResetMs(now = new Date()): number {
  return Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 1, 0, 0, 0, 0);
}

function normalizedQuotaState(user: RelayUser | null, now = new Date()) {
  const periodKey = currentQuotaPeriodKey(now);
  const used = user?.quotaPeriodKey === periodKey ? Math.max(0, Math.floor(user?.quotaUsedReferenceTokens ?? 0)) : 0;
  const spentUsd = user?.quotaPeriodKey === periodKey ? Math.max(0, Number(user?.quotaSpentUsd ?? 0)) : 0;
  return { periodKey, used, spentUsd };
}

async function fetchOpenRouterCatalog(): Promise<Map<string, OpenRouterCatalogEntry>> {
  const cached = globalThis.__cyanbridgeOpenRouterCatalog;
  if (cached && Date.now() - cached.fetchedAtMs < MODEL_CACHE_TTL_MS) {
    return cached.entries;
  }

  const entries = new Map<string, OpenRouterCatalogEntry>();
  if (!OPENROUTER_API_KEY) {
    globalThis.__cyanbridgeOpenRouterCatalog = { fetchedAtMs: Date.now(), entries };
    return entries;
  }

  try {
    const res = await fetch(`${OPENROUTER_BASE_URL}/models`, {
      headers: { Authorization: `Bearer ${OPENROUTER_API_KEY}` },
      cache: "no-store",
    });
    if (!res.ok) throw new Error(`openrouter_models_${res.status}`);

    const payload = (await res.json()) as {
      data?: Array<{
        id?: string;
        name?: string;
        pricing?: Partial<Record<PricingField, string | number>>;
      }>;
    };

    for (const item of payload.data ?? []) {
      const id = String(item.id ?? "").trim();
      if (!id) continue;
      entries.set(id, {
        id,
        name: String(item.name ?? id).trim() || id,
        pricing: {
          prompt: numberFromUnknown(item.pricing?.prompt),
          completion: numberFromUnknown(item.pricing?.completion),
          image: numberFromUnknown(item.pricing?.image),
          audio: numberFromUnknown(item.pricing?.audio),
        },
      });
    }
  } catch {
    // Fall back to curated static pricing.
  }

  globalThis.__cyanbridgeOpenRouterCatalog = { fetchedAtMs: Date.now(), entries };
  return entries;
}

export function resolveOpenRouterModelId(model: string): string {
  const clean = model.trim();
  if (!clean || clean === "auto") return OPENROUTER_DEFAULT_MODEL;
  return MODEL_ALIAS_MAP.get(clean.toLowerCase()) ?? clean;
}

async function resolveModelMetadata(model: string): Promise<VisibleOpenRouterModel> {
  const id = resolveOpenRouterModelId(model);
  const curated = CURATED_MODEL_MAP.get(id);
  const catalog = await fetchOpenRouterCatalog();
  const live = catalog.get(id);
  const pricing = {
    ...(curated?.fallbackPricing ?? {}),
    ...(live?.pricing ?? {}),
  };
  const actualMultiplierRaw = averageTextTokenPrice(pricing) > 0
    ? averageTextTokenPrice(pricing) / REFERENCE_TOKEN_COST_USD
    : 0;
  const actualQuotaMultiplier = Number(actualMultiplierRaw.toFixed(2));
  const baseLabel = curated?.label ?? live?.name ?? id;
  const quotaMultiplier = curated?.quotaMultiplier ?? Math.max(1, Math.round(actualMultiplierRaw) || 1);
  return {
    id,
    label: `${baseLabel} (${quotaMultiplier}x)`,
    quotaMultiplier,
    actualQuotaMultiplier,
    promptPriceUsd: pricing.prompt ?? 0,
    completionPriceUsd: pricing.completion ?? 0,
    imagePriceUsd: pricing.image ?? 0,
    audioPriceUsd: pricing.audio ?? 0,
  };
}

export async function getVisibleOpenRouterModels(): Promise<VisibleOpenRouterModel[]> {
  const models = await Promise.all(CURATED_MODELS.map((model) => resolveModelMetadata(model.id)));
  return models;
}

export function extractOpenRouterUsage(payload: unknown): OpenRouterUsage | null {
  if (!payload || typeof payload !== "object") return null;
  const usage = (payload as { usage?: Record<string, unknown> }).usage;
  if (!usage || typeof usage !== "object") return null;

  const promptTokens = numberFromUnknown(usage.prompt_tokens);
  const completionTokens = numberFromUnknown(usage.completion_tokens);
  const totalTokens = numberFromUnknown(usage.total_tokens) || promptTokens + completionTokens;
  const imageTokens = numberFromUnknown(usage.image_tokens);
  const audioTokens = numberFromUnknown(usage.audio_tokens);

  if (promptTokens <= 0 && completionTokens <= 0 && totalTokens <= 0 && imageTokens <= 0 && audioTokens <= 0) {
    return null;
  }

  return { promptTokens, completionTokens, totalTokens, imageTokens, audioTokens };
}

function usageCostUsd(model: VisibleOpenRouterModel, usage: OpenRouterUsage): number {
  const fallbackAverage = averageTextTokenPrice({
    prompt: model.promptPriceUsd,
    completion: model.completionPriceUsd,
  });
  const promptCost = usage.promptTokens * model.promptPriceUsd;
  const completionCost = usage.completionTokens * model.completionPriceUsd;
  const imageCost = usage.imageTokens * model.imagePriceUsd;
  const audioCost = usage.audioTokens * model.audioPriceUsd;
  const missingTokenCost = Math.max(0, usage.totalTokens - usage.promptTokens - usage.completionTokens) * fallbackAverage;
  return promptCost + completionCost + imageCost + audioCost + missingTokenCost;
}

function usageReferenceTokens(model: VisibleOpenRouterModel, usage: OpenRouterUsage): number {
  const policyFloor = Math.max(usage.totalTokens, usage.promptTokens + usage.completionTokens, 0) * model.quotaMultiplier;
  const actualCost = usageCostUsd(model, usage);
  const actualEquivalent = actualCost > 0 ? actualCost / REFERENCE_TOKEN_COST_USD : 0;
  return Math.max(policyFloor, actualEquivalent);
}

export async function buildRelayQuotaSnapshot(user: RelayUser | null, requestedModel: string): Promise<RelayQuotaSnapshot> {
  const plan = normalizePlan(user?.plan ?? "standard");
  const limit = PLAN_MONTHLY_LIMITS[plan] ?? PLAN_MONTHLY_LIMITS.standard;
  const { used, spentUsd } = normalizedQuotaState(user);
  const model = await resolveModelMetadata(requestedModel);
  return {
    used,
    limit,
    remaining: Math.max(0, limit - used),
    resetAtMs: nextQuotaResetMs(),
    model: model.id,
    plan,
    priceMonthlyUsd: RELAY_PLANS[plan]?.priceUsd ?? 5,
    spentUsd: Number(spentUsd.toFixed(6)),
    quotaMultiplier: model.quotaMultiplier,
    actualQuotaMultiplier: model.actualQuotaMultiplier,
    referenceModel: REFERENCE_MODEL_ID,
  };
}

export async function getRelayQuotaSnapshotFromRequest(request: Request, requestedModel: string) {
  const token = extractBearerToken(request);
  const user = token ? await getRelayUserByToken(token) : null;
  const quota = await buildRelayQuotaSnapshot(user, requestedModel);
  return { token, user, quota };
}

export async function enforceRelayQuota(request: Request, requestedModel: string) {
  const token = extractBearerToken(request);
  const user = token ? await getRelayUserByToken(token) : null;
  const quota = await buildRelayQuotaSnapshot(user, requestedModel);
  if (user && quota.plan === "free_trial" && quota.model !== "openrouter/free") {
    return {
      allowed: false as const,
      user,
      quota,
      response: Response.json({
        error: "model_not_allowed_for_plan",
        message: "Free trial is limited to the Cheap models router.",
        quota,
      }, { status: 403 }),
    };
  }
  if (user && quota.remaining <= 0) {
    return {
      allowed: false as const,
      user,
      quota,
      response: Response.json({
        error: "quota_exhausted",
        message: `Monthly quota exhausted for plan ${quota.plan}.`,
        quota,
      }, { status: 402 }),
    };
  }
  return { allowed: true as const, user, quota };
}

export async function recordRelayQuotaUsage(user: RelayUser | null, requestedModel: string, usage: OpenRouterUsage | null) {
  if (!user || !usage) {
    return buildRelayQuotaSnapshot(user, requestedModel);
  }

  const model = await resolveModelMetadata(requestedModel);
  const referenceTokens = Math.ceil(usageReferenceTokens(model, usage));
  const spentUsd = usageCostUsd(model, usage);
  const quotaState = normalizedQuotaState(user);
  const updatedUser: RelayUser = {
    ...user,
    quotaPeriodKey: quotaState.periodKey,
    quotaUsedReferenceTokens: quotaState.used + referenceTokens,
    quotaSpentUsd: Number((quotaState.spentUsd + spentUsd).toFixed(6)),
    updatedAt: new Date().toISOString(),
  };
  await saveRelayUser(updatedUser);
  return buildRelayQuotaSnapshot(updatedUser, model.id);
}
