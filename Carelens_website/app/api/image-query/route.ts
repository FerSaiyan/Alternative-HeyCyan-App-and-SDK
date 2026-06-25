import { NextResponse } from "next/server";

const OPENROUTER_API_KEY = process.env.OPENROUTER_API_KEY ?? "";
const OPENROUTER_BASE_URL = process.env.OPENROUTER_BASE_URL ?? "https://openrouter.ai/api/v1";
function resolveVisionModel(model: string): string {
  const clean = model?.trim();
  if (!clean || clean === "auto") {
    return "google/gemini-3-flash-preview";
  }
  return clean;
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
  const prompt = String(body.prompt ?? "").trim() || "Describe this image in detail.";
  const imageBase64 = String(body.imageBase64 ?? "").trim();
  const filename = String(body.filename ?? "image.jpg").trim();

  if (!imageBase64) {
    return NextResponse.json({ error: "missing_imageBase64" }, { status: 400 });
  }

  const resolvedModel = resolveVisionModel(model);

  // Determine MIME type from filename
  const ext = filename.toLowerCase().split(".").pop() ?? "jpg";
  const mimeMap: Record<string, string> = {
    jpg: "image/jpeg", jpeg: "image/jpeg", png: "image/png",
    gif: "image/gif", webp: "image/webp", bmp: "image/bmp",
  };
  const mimeType = mimeMap[ext] ?? "image/jpeg";

  try {
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
        messages: [
          {
            role: "user",
            content: [
              { type: "text", text: prompt },
              {
                type: "image_url",
                image_url: { url: `data:${mimeType};base64,${imageBase64}` },
              },
            ],
          },
        ],
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

    return NextResponse.json({ reply: text, model: data.model ?? resolvedModel });
  } catch (error) {
    const msg = error instanceof Error ? error.message : "image_query_failed";
    return NextResponse.json({ error: msg }, { status: 502 });
  }
}
