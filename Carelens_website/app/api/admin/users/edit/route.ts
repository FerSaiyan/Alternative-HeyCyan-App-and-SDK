import { Role } from "@prisma/client";
import { NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import { requireApiRole } from "@/lib/role-guard";
import { getRequestOrigin } from "@/lib/request-origin";

export async function POST(request: Request) {
  const appOrigin = getRequestOrigin(request);
  const guard = await requireApiRole(request, [Role.ADMIN]);
  if (!guard.ok) {
    return guard.response;
  }

  const formData = await request.formData();
  const userId = String(formData.get("userId") ?? "").trim();
  const fullName = String(formData.get("fullName") ?? "").trim();
  const email = String(formData.get("email") ?? "").trim().toLowerCase();
  const username = String(formData.get("username") ?? "").trim().toLowerCase();

  if (!userId || !email) {
    return NextResponse.redirect(
      new URL("/admin/users?error=invalid", appOrigin),
      { status: 303 }
    );
  }

  // Check email uniqueness (excluding current user)
  const existingEmail = await prisma.user.findFirst({
    where: { email, NOT: { id: userId } },
    select: { id: true },
  });
  if (existingEmail) {
    return NextResponse.redirect(
      new URL("/admin/users?error=email_exists", appOrigin),
      { status: 303 }
    );
  }

  // Check username uniqueness (excluding current user)
  if (username) {
    const existingUsername = await prisma.user.findFirst({
      where: { username, NOT: { id: userId } },
      select: { id: true },
    });
    if (existingUsername) {
      return NextResponse.redirect(
        new URL("/admin/users?error=username_exists", appOrigin),
        { status: 303 }
      );
    }
  }

  await prisma.user.update({
    where: { id: userId },
    data: {
      fullName: fullName || null,
      email,
      username: username || null,
    },
  });

  return NextResponse.redirect(
    new URL("/admin/users?updated=1", appOrigin),
    { status: 303 }
  );
}
