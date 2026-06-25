import { Role } from "@prisma/client";
import { NextResponse } from "next/server";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { getSessionUserById, parseUserIdCookie, extractCookieValue, SessionUser } from "@/lib/session-user";

function loginRedirect(nextPath: string, reason?: "role"): never {
  const qs = new URLSearchParams({ auth: "required", next: nextPath, mode: "signin" });
  if (reason) {
    qs.set("reason", reason);
  }
  redirect(`/signin?${qs.toString()}`);
}

export async function requirePageRole(allowedRoles: Role[], nextPath: string): Promise<SessionUser> {
  const cookieStore = await cookies();
  const userId = parseUserIdCookie(cookieStore.get("carelens_user_id")?.value);

  if (!userId) {
    loginRedirect(nextPath);
  }

  const user = await getSessionUserById(userId);
  if (!user) {
    loginRedirect(nextPath);
  }

  if (!allowedRoles.includes(user.role)) {
    loginRedirect(nextPath, "role");
  }

  return user;
}

export async function requireApiRole(
  request: Request,
  allowedRoles: Role[],
): Promise<{ ok: true; user: SessionUser } | { ok: false; response: NextResponse }> {
  const cookieHeader = request.headers.get("cookie") ?? "";
  const userId = parseUserIdCookie(extractCookieValue(cookieHeader, "carelens_user_id"));

  if (!userId) {
    return {
      ok: false,
      response: NextResponse.json({ ok: false, message: "Sessão inválida." }, { status: 401 }),
    };
  }

  const user = await getSessionUserById(userId);
  if (!user) {
    return {
      ok: false,
      response: NextResponse.json({ ok: false, message: "Sessão não encontrada." }, { status: 401 }),
    };
  }

  if (!allowedRoles.includes(user.role)) {
    return {
      ok: false,
      response: NextResponse.json({ ok: false, message: "Acesso não autorizado para este perfil." }, { status: 403 }),
    };
  }

  return { ok: true, user };
}
