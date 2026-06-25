import crypto from "node:crypto";

export function isGoogleOAuthConfigured(): boolean {
  return !!(process.env.GOOGLE_CLIENT_ID && process.env.GOOGLE_CLIENT_SECRET);
}

export function createGoogleOAuthState(): string {
  return crypto.randomBytes(24).toString("hex");
}

export function buildGoogleAuthorizationUrl(appOrigin: string, state: string): string {
  const redirectUri = `${appOrigin}/api/auth/google/callback`;
  const url = new URL("https://accounts.google.com/o/oauth2/v2/auth");
  url.searchParams.set("client_id", process.env.GOOGLE_CLIENT_ID ?? "");
  url.searchParams.set("redirect_uri", redirectUri);
  url.searchParams.set("response_type", "code");
  url.searchParams.set(
    "scope",
    "openid email profile https://www.googleapis.com/auth/user.birthday.read",
  );
  url.searchParams.set("state", state);
  url.searchParams.set("prompt", "select_account");
  url.searchParams.set("include_granted_scopes", "true");
  return url.toString();
}

type GoogleTokenResponse = {
  access_token: string;
};

export async function exchangeGoogleCodeForAccessToken(
  appOrigin: string,
  code: string,
): Promise<string> {
  const redirectUri = `${appOrigin}/api/auth/google/callback`;
  const payload = new URLSearchParams({
    code,
    client_id: process.env.GOOGLE_CLIENT_ID ?? "",
    client_secret: process.env.GOOGLE_CLIENT_SECRET ?? "",
    redirect_uri: redirectUri,
    grant_type: "authorization_code",
  });

  const tokenRes = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: payload.toString(),
  });

  if (!tokenRes.ok) {
    throw new Error("Falha ao trocar código OAuth por token.");
  }

  const tokenJson = (await tokenRes.json()) as GoogleTokenResponse;
  if (!tokenJson.access_token) {
    throw new Error("Google não retornou token de acesso.");
  }

  return tokenJson.access_token;
}

type GoogleUserInfo = {
  sub?: string;
  email?: string;
  email_verified?: boolean;
  name?: string;
};

export async function fetchGoogleUserInfo(accessToken: string): Promise<GoogleUserInfo> {
  const profileRes = await fetch("https://openidconnect.googleapis.com/v1/userinfo", {
    headers: {
      Authorization: `Bearer ${accessToken}`,
    },
  });

  if (!profileRes.ok) {
    throw new Error("Falha ao buscar perfil do Google.");
  }

  return (await profileRes.json()) as GoogleUserInfo;
}

type GoogleBirthdaysResponse = {
  birthdays?: Array<{
    date?: {
      year?: number;
      month?: number;
      day?: number;
    };
  }>;
};

export async function fetchGoogleBirthday(accessToken: string): Promise<Date | null> {
  const response = await fetch("https://people.googleapis.com/v1/people/me?personFields=birthdays", {
    headers: {
      Authorization: `Bearer ${accessToken}`,
    },
  });

  if (!response.ok) {
    return null;
  }

  const json = (await response.json()) as GoogleBirthdaysResponse;
  const firstComplete = json.birthdays?.find(
    (entry) => entry.date?.year && entry.date?.month && entry.date?.day,
  );

  if (!firstComplete?.date?.year || !firstComplete.date.month || !firstComplete.date.day) {
    return null;
  }

  const iso = `${String(firstComplete.date.year).padStart(4, "0")}-${String(firstComplete.date.month).padStart(2, "0")}-${String(firstComplete.date.day).padStart(2, "0")}`;
  const parsed = new Date(`${iso}T00:00:00.000Z`);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}
