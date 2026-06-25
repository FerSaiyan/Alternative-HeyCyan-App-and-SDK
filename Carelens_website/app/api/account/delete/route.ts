import { NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import {
  parseUserIdCookie,
  extractCookieValue,
} from "@/lib/session-user";
import { logInfo, logWarn } from "@/lib/logger";

/* ------------------------------------------------------------------ */
/*  Helpers                                                            */
/* ------------------------------------------------------------------ */

/**
 * Accept same-origin POSTs only. Browsers always send `Origin` on
 * cross-origin POSTs.  When `Origin` is absent (curl, tools, tests)
 * the request is allowed through — this keeps local / test / headless
 * environments working without forcing callers to spoof a header.
 */
function isAllowedOrigin(request: Request): boolean {
  const origin = request.headers.get("origin");

  // No Origin header → not a browser-originated POST → allow
  if (!origin) return true;

  // 1) Check against the canonical app URL when configured
  const appUrl = process.env.NEXT_PUBLIC_APP_URL;
  if (appUrl) {
    try {
      if (origin === new URL(appUrl).origin) return true;
    } catch {
      // malformed URL — fall through to host-based check
    }
  }

  // 2) Fallback: construct expected origin from the Host header.
  //    Works in dev, test, and production (behind a reverse proxy
  //    that sets x-forwarded-proto).
  const host = request.headers.get("host");
  if (!host) return false;

  const isSecure =
    process.env.NODE_ENV === "production" ||
    request.headers.get("x-forwarded-proto") === "https";

  return origin === `${isSecure ? "https" : "http"}://${host}`;
}

/* ------------------------------------------------------------------ */
/*  POST — Delete authenticated user account and personal data         */
/* ------------------------------------------------------------------ */

/**
 * Deletes the authenticated user and all directly linked personal data.
 *
 * Cascade deletions (via Prisma schema `onDelete: Cascade`):
 *  - AuthMagicLink
 *  - Subscription
 *  - GlassesOrder
 *
 * Explicitly deleted before user:
 *  - AsaasPayment  (no cascade — linked via userId)
 *  - AsaasSubscription (no cascade — linked via userId)
 *
 * What is NOT deleted (retained per legal obligation):
 *  - AsaasWebhookEvent (anonymized — no personal data keyed to user)
 *  - StripeWebhookEvent (anonymized — no personal data keyed to user)
 *
 * After deletion, the response clears the session cookie.
 */
export async function POST(request: Request) {
  /* --- 0. CSRF check — reject non-same-origin POST requests --- */
  if (!isAllowedOrigin(request)) {
    logWarn("csrf_blocked", "Account delete blocked — cross-origin POST", {
      origin: request.headers.get("origin") ?? "(null)",
      host: request.headers.get("host") ?? "(null)",
    });
    return NextResponse.json(
      { ok: false, message: "Origem da requisição inválida." },
      { status: 403 },
    );
  }

  /* --- 1. Authenticate via session cookie --- */
  const cookieHeader = request.headers.get("cookie") ?? "";
  const userId = parseUserIdCookie(
    extractCookieValue(cookieHeader, "carelens_user_id"),
  );

  if (!userId) {
    return NextResponse.json(
      { ok: false, message: "Usuário não autenticado." },
      { status: 401 },
    );
  }

  /* --- 2. Parse confirmation token --- */
  let body: { confirmation?: string };
  try {
    body = (await request.json()) as { confirmation?: string };
  } catch {
    return NextResponse.json(
      { ok: false, message: "Payload inválido." },
      { status: 400 },
    );
  }

  if (body.confirmation !== "EXCLUIR") {
    return NextResponse.json(
      {
        ok: false,
        message:
          'Confirmação necessária. Envie {"confirmation": "EXCLUIR"} para prosseguir.',
      },
      { status: 400 },
    );
  }

  /* --- 3. Verify user exists --- */
  const user = await prisma.user.findUnique({
    where: { id: userId },
    select: { id: true, email: true },
  });

  if (!user) {
    return NextResponse.json(
      { ok: false, message: "Usuário não encontrado." },
      { status: 404 },
    );
  }

  /* --- 4. Delete dependent records (no cascade in schema) --- */

  await prisma.$transaction(async (tx) => {
    // AsaasPayments linked to this user
    await tx.asaasPayment.deleteMany({ where: { userId: user.id } });

    // AsaasSubscriptions linked to this user
    await tx.asaasSubscription.deleteMany({ where: { userId: user.id } });

    // User deletion (cascades: AuthMagicLink, Subscription, GlassesOrder)
    await tx.user.delete({ where: { id: user.id } });
  });

  logInfo("account_deleted", "User account and personal data deleted", {
    userId: user.id,
  });

  /* --- 5. Return response that clears session --- */
  const response = NextResponse.json({
    ok: true,
    message:
      "Conta e dados pessoais excluídos com sucesso. Redirecionando...",
    redirectTo: "/",
  });

  // Clear session cookies
  response.cookies.delete("carelens_user_id");
  response.cookies.delete("carelens_booking_id");

  return response;
}
