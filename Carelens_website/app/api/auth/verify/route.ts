import { NextResponse } from "next/server";
import { consumeMagicLinkToken } from "@/lib/magic-link";
import { getRequestOrigin } from "@/lib/request-origin";
import { getSessionUserById } from "@/lib/session-user";

function defaultPathForRole(role: string): string {
  if (role === "ADMIN") {
    return "/admin";
  }
  if (role === "FAMILY") {
    return "/account";
  }
  if (role === "PHARMACY") {
    return "/pharmacy";
  }
  return "/account";
}

export async function GET(request: Request) {
  const appOrigin = getRequestOrigin(request);

  try {
    const url = new URL(request.url);
    const token = String(url.searchParams.get("token") ?? "").trim();
    const nextPath = String(url.searchParams.get("next") ?? "/account").trim();

    if (!token) {
      return NextResponse.redirect(new URL("/signin?auth=required", appOrigin), { status: 303 });
    }

    const consumed = await consumeMagicLinkToken(token);
    if (!consumed) {
      return NextResponse.redirect(new URL("/signin?auth=required", appOrigin), { status: 303 });
    }

    const user = await getSessionUserById(consumed.userId);
    if (!user) {
      return NextResponse.redirect(new URL("/signin?auth=required", appOrigin), { status: 303 });
    }

    const requestedPath = nextPath.startsWith("/") ? nextPath : "/account";
    const resolvedPath = requestedPath === "/account" ? defaultPathForRole(user.role) : requestedPath;

    const redirectUrl = new URL(resolvedPath, appOrigin);
    const response = NextResponse.redirect(redirectUrl, { status: 303 });
    response.cookies.set("carelens_user_id", user.id, {
      httpOnly: true,
      sameSite: "lax",
      secure: process.env.NODE_ENV === "production",
      path: "/",
      maxAge: 60 * 60 * 24 * 60,
    });

    return response;
  } catch (error) {
    const message = error instanceof Error ? error.message : "verify_error";
    return NextResponse.redirect(
      new URL(`/signin?auth=required&reason=verify&error=${encodeURIComponent(message)}`, appOrigin),
      { status: 303 },
    );
  }
}
