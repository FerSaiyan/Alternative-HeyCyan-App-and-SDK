import { NextResponse } from "next/server";
import {
  buildGoogleAuthorizationUrl,
  createGoogleOAuthState,
  isGoogleOAuthConfigured,
} from "@/lib/google-oauth";
import { getRequestOrigin } from "@/lib/request-origin";

export async function GET(request: Request) {
  const appOrigin = getRequestOrigin(request);
  const url = new URL(request.url);
  const nextPathRaw = String(url.searchParams.get("next") ?? "/sub_onboarding").trim();
  const nextPath = nextPathRaw.startsWith("/") ? nextPathRaw : "/sub_onboarding";

  if (!isGoogleOAuthConfigured()) {
    return NextResponse.redirect(
      new URL(`/signin?auth=required&reason=google_config&next=${encodeURIComponent(nextPath)}`, appOrigin),
      { status: 303 },
    );
  }

  const state = createGoogleOAuthState();
  const authUrl = buildGoogleAuthorizationUrl(appOrigin, state);
  const response = NextResponse.redirect(authUrl, { status: 303 });
  const secure = appOrigin.startsWith("https://");

  response.cookies.set("carelens_google_oauth_state", state, {
    httpOnly: true,
    sameSite: "lax",
    secure,
    path: "/",
    maxAge: 60 * 10,
  });

  response.cookies.set("carelens_google_oauth_next", nextPath, {
    httpOnly: true,
    sameSite: "lax",
    secure,
    path: "/",
    maxAge: 60 * 10,
  });

  return response;
}
