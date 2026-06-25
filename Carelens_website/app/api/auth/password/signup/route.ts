import { NextResponse } from "next/server";
import { getRequestOrigin } from "@/lib/request-origin";
import { hashPassword, isValidPasswordShape } from "@/lib/password-auth";
import { registerPasswordUser } from "@/lib/session-user";

function parseDateOfBirth(raw: string): Date | null {
  const normalized = raw.trim();
  if (!normalized) {
    return null;
  }
  const parsed = new Date(`${normalized}T00:00:00.000Z`);
  if (Number.isNaN(parsed.getTime())) {
    return null;
  }
  return parsed;
}

export async function POST(request: Request) {
  const appOrigin = getRequestOrigin(request);
  const formData = await request.formData();
  const fullName = String(formData.get("fullName") ?? "").trim();
  const email = String(formData.get("email") ?? "").trim().toLowerCase();
  const password = String(formData.get("password") ?? "");
  const dateOfBirthRaw = String(formData.get("dateOfBirth") ?? "").trim();
  const nextPathRaw = String(formData.get("next") ?? "/sub_onboarding").trim();
  const nextPath = nextPathRaw.startsWith("/") ? nextPathRaw : "/sub_onboarding";

  if (!fullName || !email || !password || !dateOfBirthRaw) {
    return NextResponse.redirect(
      new URL(`/signin?reason=password_missing_fields&next=${encodeURIComponent(nextPath)}`, appOrigin),
      { status: 303 },
    );
  }

  if (!isValidPasswordShape(password)) {
    return NextResponse.redirect(
      new URL(`/signin?reason=password_weak&next=${encodeURIComponent(nextPath)}`, appOrigin),
      { status: 303 },
    );
  }

  const dateOfBirth = parseDateOfBirth(dateOfBirthRaw);
  if (!dateOfBirth) {
    return NextResponse.redirect(
      new URL(`/signin?reason=password_invalid_birthdate&next=${encodeURIComponent(nextPath)}`, appOrigin),
      { status: 303 },
    );
  }

  try {
    const passwordHash = await hashPassword(password);
    const user = await registerPasswordUser({
      email,
      passwordHash,
      fullName,
      dateOfBirth,
    });

    const response = NextResponse.redirect(new URL(nextPath, appOrigin), { status: 303 });
    response.cookies.set("carelens_user_id", user.id, {
      httpOnly: true,
      sameSite: "lax",
      secure: process.env.NODE_ENV === "production",
      path: "/",
      maxAge: 60 * 60 * 24 * 60,
    });

    return response;
  } catch (error) {
    const reason =
      error instanceof Error && error.name === "EMAIL_ALREADY_EXISTS"
        ? "password_email_exists"
        : "password_signup_failed";

    return NextResponse.redirect(
      new URL(`/signin?reason=${reason}&next=${encodeURIComponent(nextPath)}`, appOrigin),
      { status: 303 },
    );
  }
}
