import { AuthProvider, Role } from "@prisma/client";
import { NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import { requireApiRole } from "@/lib/role-guard";
import { hashPassword } from "@/lib/password-auth";
import { getRequestOrigin } from "@/lib/request-origin";

export async function POST(request: Request) {
  const appOrigin = getRequestOrigin(request);
  const guard = await requireApiRole(request, [Role.ADMIN]);
  if (!guard.ok) {
    return guard.response;
  }

  const formData = await request.formData();
  const fullName = String(formData.get("fullName") ?? "").trim();
  const email = String(formData.get("email") ?? "").trim().toLowerCase();
  const username = String(formData.get("username") ?? "").trim().toLowerCase();
  const password = String(formData.get("password") ?? "");
  const relationship = String(formData.get("relationship") ?? "").trim();

  // Validate
  if (!fullName || !email || !username || !password) {
    return NextResponse.redirect(
      new URL("/admin/users?error=missing_fields", appOrigin),
      { status: 303 }
    );
  }

  if (password.length < 8) {
    return NextResponse.redirect(
      new URL("/admin/users?error=weak_password", appOrigin),
      { status: 303 }
    );
  }

  if (!/^[a-z0-9._]{3,30}$/.test(username)) {
    return NextResponse.redirect(
      new URL("/admin/users?error=invalid_username", appOrigin),
      { status: 303 }
    );
  }

  // Check uniqueness
  const existingEmail = await prisma.user.findUnique({ where: { email }, select: { id: true } });
  if (existingEmail) {
    return NextResponse.redirect(
      new URL("/admin/users?error=email_exists", appOrigin),
      { status: 303 }
    );
  }

  const existingUsername = await prisma.user.findUnique({ where: { username }, select: { id: true } });
  if (existingUsername) {
    return NextResponse.redirect(
      new URL("/admin/users?error=username_exists", appOrigin),
      { status: 303 }
    );
  }

  // Create family member
  const passwordHash = await hashPassword(password);
  const user = await prisma.user.create({
    data: {
      email,
      username,
      passwordHash,
      role: Role.FAMILY,
      fullName,
      authProvider: AuthProvider.PASSWORD,
    },
  });

  // Create family profile
  await prisma.familyProfile.create({
    data: {
      userId: user.id,
      displayName: fullName,
      relationship: relationship || null,
    },
  });

  return NextResponse.redirect(
    new URL("/admin/users?created=1", appOrigin),
    { status: 303 }
  );
}
