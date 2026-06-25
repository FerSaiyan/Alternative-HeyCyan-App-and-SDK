import { NextResponse } from "next/server";
import { getRequestOrigin } from "@/lib/request-origin";
import { verifyPassword } from "@/lib/password-auth";
import { getUserAuthByIdentifier } from "@/lib/session-user";
import { AuthProvider } from "@prisma/client";

export async function POST(request: Request) {
  const appOrigin = getRequestOrigin(request);
  const formData = await request.formData();
  const identifier = String(formData.get("identifier") ?? "").trim();
  const password = String(formData.get("password") ?? "");
  const nextPathRaw = String(formData.get("next") ?? "/account").trim();
  const nextPath = nextPathRaw.startsWith("/") ? nextPathRaw : "/account";

  if (!identifier || !password) {
    return NextResponse.redirect(
      new URL(`/signin?reason=password_missing_login&next=${encodeURIComponent(nextPath)}`, appOrigin),
      { status: 303 },
    );
  }

  const user = await getUserAuthByIdentifier(identifier);
  if (!user) {
    return NextResponse.redirect(
      new URL(`/signin?reason=password_invalid_credentials&next=${encodeURIComponent(nextPath)}`, appOrigin),
      { status: 303 },
    );
  }

  if (user.authProvider === AuthProvider.GOOGLE) {
    return NextResponse.redirect(
      new URL(`/signin?reason=password_google_account&next=${encodeURIComponent(nextPath)}`, appOrigin),
      { status: 303 },
    );
  }

  if (!user.passwordHash) {
    return NextResponse.redirect(
      new URL(`/signin?reason=password_not_configured&next=${encodeURIComponent(nextPath)}`, appOrigin),
      { status: 303 },
    );
  }

  const validPassword = await verifyPassword(password, user.passwordHash);
  if (!validPassword) {
    return NextResponse.redirect(
      new URL(`/signin?reason=password_invalid_credentials&next=${encodeURIComponent(nextPath)}`, appOrigin),
      { status: 303 },
    );
  }

  // Role-based redirect: non-elderly users go to their portal regardless of nextPath
  const roleRedirects: Record<string, string> = {
    ADMIN: "/admin",
    FAMILY: "/account",
    PHARMACY: "/pharmacy",
  };
  const redirectPath = roleRedirects[user.role] ?? nextPath;

  const response = NextResponse.redirect(new URL(redirectPath, appOrigin), { status: 303 });
  response.cookies.set("carelens_user_id", user.id, {
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge: 60 * 60 * 24 * 60,
  });

  return response;
}
