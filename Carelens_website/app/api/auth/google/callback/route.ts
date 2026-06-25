import { NextResponse } from "next/server";
import {
  exchangeGoogleCodeForAccessToken,
  fetchGoogleBirthday,
  fetchGoogleUserInfo,
  isGoogleOAuthConfigured,
} from "@/lib/google-oauth";
import { getRequestOrigin } from "@/lib/request-origin";
import { ensureGoogleUserByEmail, getSessionUserById } from "@/lib/session-user";

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
  const url = new URL(request.url);

  if (!isGoogleOAuthConfigured()) {
    return NextResponse.redirect(new URL("/signin?auth=required&reason=google_config", appOrigin), {
      status: 303,
    });
  }

  const code = String(url.searchParams.get("code") ?? "").trim();
  const state = String(url.searchParams.get("state") ?? "").trim();
  const stateCookie = request.headers.get("cookie")?.match(/carelens_google_oauth_state=([^;]+)/)?.[1] ?? "";
  const nextCookie = request.headers.get("cookie")?.match(/carelens_google_oauth_next=([^;]+)/)?.[1] ?? "";
  const nextPathDecoded = decodeURIComponent(nextCookie || "/sub_onboarding");
  const nextPath = nextPathDecoded.startsWith("/") ? nextPathDecoded : "/sub_onboarding";

  if (!code || !state || !stateCookie || state !== stateCookie) {
    const invalidResponse = NextResponse.redirect(
      new URL(`/signin?auth=required&reason=google_state&next=${encodeURIComponent(nextPath)}`, appOrigin),
      { status: 303 },
    );
    invalidResponse.cookies.delete("carelens_google_oauth_state");
    invalidResponse.cookies.delete("carelens_google_oauth_next");
    return invalidResponse;
  }

  try {
    const accessToken = await exchangeGoogleCodeForAccessToken(appOrigin, code);
    const googleUser = await fetchGoogleUserInfo(accessToken);
    const birthday = await fetchGoogleBirthday(accessToken);

    const email = googleUser.email?.trim().toLowerCase();
    if (!email || googleUser.email_verified === false) {
      throw new Error("E-mail Google não verificado.");
    }

    const user = await ensureGoogleUserByEmail({
      email,
      fullName: googleUser.name,
      googleSub: googleUser.sub,
      dateOfBirth: birthday ?? undefined,
    });
    const sessionUser = await getSessionUserById(user.id);
    if (!sessionUser) {
      throw new Error("Usuário não encontrado após login Google.");
    }

    const resolvedPath = nextPath === "/account" ? defaultPathForRole(sessionUser.role) : nextPath;
    const response = NextResponse.redirect(new URL(resolvedPath, appOrigin), { status: 303 });
    const secure = appOrigin.startsWith("https://");

    response.cookies.set("carelens_user_id", user.id, {
      httpOnly: true,
      sameSite: "lax",
      secure,
      path: "/",
      maxAge: 60 * 60 * 24 * 60,
    });
    response.cookies.delete("carelens_google_oauth_state");
    response.cookies.delete("carelens_google_oauth_next");

    return response;
  } catch (error) {
    const reason =
      error instanceof Error && error.name === "EMAIL_REGISTERED_WITH_PASSWORD"
        ? "google_email_conflict"
        : "google_failed";
    const failedResponse = NextResponse.redirect(
      new URL(`/signin?auth=required&reason=${reason}&next=${encodeURIComponent(nextPath)}`, appOrigin),
      { status: 303 },
    );
    failedResponse.cookies.delete("carelens_google_oauth_state");
    failedResponse.cookies.delete("carelens_google_oauth_next");
    return failedResponse;
  }
}
