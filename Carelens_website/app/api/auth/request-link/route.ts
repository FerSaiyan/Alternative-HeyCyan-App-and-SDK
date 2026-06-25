import { NextResponse } from "next/server";
import { createMagicLinkToken } from "@/lib/magic-link";
import { sendMagicLinkEmail } from "@/lib/mailer";
import { getRequestOrigin } from "@/lib/request-origin";
import { ensureUserFromEmail } from "@/lib/session-user";

export async function POST(request: Request) {
  const formData = await request.formData();
  const email = String(formData.get("email") ?? "").trim();
  const nextPath = String(formData.get("next") ?? "/account").trim();

  if (!email) {
    return NextResponse.json({ ok: false, message: "E-mail é obrigatório." }, { status: 400 });
  }

  const user = await ensureUserFromEmail(email);
  const { rawToken } = await createMagicLinkToken(user.id);

  const appUrl = getRequestOrigin(request);
  const verifyUrl = new URL(`${appUrl}/api/auth/verify`);
  verifyUrl.searchParams.set("token", rawToken);
  verifyUrl.searchParams.set("next", nextPath.startsWith("/") ? nextPath : "/account");

  try {
    const result = await sendMagicLinkEmail({
      to: user.email,
      verifyUrl: verifyUrl.toString(),
    });

    const redirectUrl = new URL(`${appUrl}/signin/check-email`);
    redirectUrl.searchParams.set("email", user.email);
    if (result.previewUrl) {
      redirectUrl.searchParams.set("preview", result.previewUrl);
    }

    return NextResponse.redirect(redirectUrl, { status: 303 });
  } catch {
    return NextResponse.json(
      { ok: false, message: "Falha ao enviar link de acesso. Tente novamente." },
      { status: 500 },
    );
  }
}
