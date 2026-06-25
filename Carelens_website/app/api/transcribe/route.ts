import { NextResponse } from "next/server";

const OPENROUTER_API_KEY = process.env.OPENROUTER_API_KEY ?? "";
const OPENROUTER_BASE_URL = process.env.OPENROUTER_BASE_URL ?? "https://openrouter.ai/api/v1";

export async function POST(request: Request) {
  if (!OPENROUTER_API_KEY) {
    // Return stub transcript if no key
    const formData = await request.formData();
    const file = formData.get("file") as File | null;
    const filename = file?.name ?? "unknown";
    const bytes = file?.size ?? 0;
    return NextResponse.json({
      text: `[stub-transcript] file=${filename} bytes=${bytes}`,
    });
  }

  try {
    const formData = await request.formData();
    const file = formData.get("file") as File | null;

    if (!file) {
      return NextResponse.json({ error: "missing_file" }, { status: 400 });
    }

    // Forward to OpenRouter Whisper
    const orFormData = new FormData();
    orFormData.append("file", file);
    orFormData.append("model", "openai/whisper-large-v3");

    const res = await fetch(`${OPENROUTER_BASE_URL}/audio/transcriptions`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${OPENROUTER_API_KEY}`,
      },
      body: orFormData,
    });

    if (!res.ok) {
      const errText = await res.text();
      throw new Error(`openrouter_whisper_${res.status}: ${errText.slice(0, 200)}`);
    }

    const data = (await res.json()) as { text?: string };
    return NextResponse.json({ text: data.text ?? "" });
  } catch (error) {
    const msg = error instanceof Error ? error.message : "transcription_failed";
    return NextResponse.json({ error: msg }, { status: 502 });
  }
}
