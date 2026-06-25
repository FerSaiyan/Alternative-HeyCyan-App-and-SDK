import { NextResponse } from "next/server";

const OPENROUTER_API_KEY = process.env.OPENROUTER_API_KEY ?? "";
const OPENROUTER_BASE_URL = process.env.OPENROUTER_BASE_URL ?? "https://openrouter.ai/api/v1";
const OPENROUTER_DEFAULT_MODEL = process.env.OPENROUTER_DEFAULT_MODEL ?? "deepseek/deepseek-v4-flash";

function resolveModel(model: string): string {
  const clean = model?.trim();
  if (!clean || clean === "auto") return OPENROUTER_DEFAULT_MODEL;
  return clean;
}

async function openrouterChat(
  messages: Array<{ role: string; content: string | unknown }>,
  model: string,
): Promise<{ text: string; model: string }> {
  const resolvedModel = resolveModel(model);

  const res = await fetch(`${OPENROUTER_BASE_URL}/chat/completions`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${OPENROUTER_API_KEY}`,
      "HTTP-Referer": process.env.OPENROUTER_HTTP_REFERER ?? "https://cyanbridge.vercel.app",
      "X-Title": process.env.OPENROUTER_APP_TITLE ?? "CyanBridge",
    },
    body: JSON.stringify({
      model: resolvedModel,
      messages,
      max_tokens: parseInt(process.env.OPENROUTER_MAX_TOKENS ?? "4096") || 4096,
    }),
  });

  if (!res.ok) {
    const errText = await res.text();
    throw new Error(`openrouter_http_${res.status}: ${errText.slice(0, 200)}`);
  }

  const data = (await res.json()) as {
    choices?: Array<{ message?: { content?: string } }>;
    model?: string;
  };

  const text = data.choices?.[0]?.message?.content?.trim() ?? "";
  if (!text) throw new Error("openrouter_empty_content");

  return { text, model: data.model ?? resolvedModel };
}

export async function POST(request: Request) {
  if (!OPENROUTER_API_KEY) {
    return NextResponse.json({ error: "openrouter_api_key_missing" }, { status: 503 });
  }

  let body: Record<string, unknown>;
  try {
    body = (await request.json()) as Record<string, unknown>;
  } catch {
    return NextResponse.json({ error: "invalid_json" }, { status: 400 });
  }

  const model = String(body.model ?? "").trim();
  const prompt = String(body.prompt ?? "").trim();
  const rawMessages = body.messages;

  let messages: Array<{ role: string; content: string | unknown }>;

  if (Array.isArray(rawMessages) && rawMessages.length > 0) {
    messages = rawMessages as Array<{ role: string; content: string | unknown }>;
  } else if (prompt) {
    messages = [{ role: "user", content: prompt }];
  } else {
    return NextResponse.json({ error: "missing_prompt_or_messages" }, { status: 400 });
  }

  try {
    const result = await openrouterChat(messages, model);
    return NextResponse.json({ reply: result.text, model: result.model });
  } catch (error) {
    const msg = error instanceof Error ? error.message : "chat_failed";
    return NextResponse.json({ error: msg }, { status: 502 });
  }
}
