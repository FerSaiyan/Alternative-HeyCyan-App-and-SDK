import { NextResponse } from "next/server";

const OPENROUTER_API_KEY = process.env.OPENROUTER_API_KEY ?? "";
const OPENROUTER_BASE_URL = process.env.OPENROUTER_BASE_URL ?? "https://openrouter.ai/api/v1";
const DEFAULT_MODELS = [
  "auto", "deepseek/deepseek-v4-flash", "minimax/minimax-m2.5:free",
  "z-ai/glm-5", "google/gemini-3-flash-preview",
];

export async function GET() {
  // If we have an OpenRouter key, fetch real models
  if (OPENROUTER_API_KEY) {
    try {
      const res = await fetch(`${OPENROUTER_BASE_URL}/models`, {
        headers: {
          Authorization: `Bearer ${OPENROUTER_API_KEY}`,
        },
        next: { revalidate: 300 },
      });

      if (res.ok) {
        const data = (await res.json()) as {
          data?: Array<{ id: string; name?: string }>;
        };

        const models = (data.data ?? []).map((m) => ({
          id: m.id,
          label: m.name ?? m.id,
          quota_multiplier: 1,
        }));

        // Ensure our default models are at the top
        const defaultSet = new Set(DEFAULT_MODELS);
        const defaults = DEFAULT_MODELS.map((id) => ({
          id,
          label: id === "auto" ? "Auto" : id,
          quota_multiplier: 1,
        }));
        const rest = models.filter((m) => !defaultSet.has(m.id));

        return NextResponse.json({ data: [...defaults, ...rest] });
      }
    } catch {
      // fallback below
    }
  }

  // Fallback: return configured models
  const configured = process.env.AVAILABLE_MODELS?.split(",").map((s) => s.trim()).filter(Boolean) ?? DEFAULT_MODELS;
  const data = configured.map((id) => ({
    id,
    label: id === "auto" ? "Auto" : id,
    quota_multiplier: 1,
  }));

  return NextResponse.json({ data });
}

// Also handle POST for /v1/models
export async function POST() {
  return GET();
}
