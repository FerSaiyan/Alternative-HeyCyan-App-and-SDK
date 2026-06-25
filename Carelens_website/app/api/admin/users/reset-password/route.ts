import { Role } from "@prisma/client";
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
  const userId = String(formData.get("userId") ?? "").trim();
  const newPassword = String(formData.get("newPassword") ?? "");

  if (!userId || !newPassword || newPassword.length < 8) {
    return NextResponse.redirect(
      new URL("/admin/users?error=weak_password", appOrigin),
      { status: 303 }
    );
  }

  const passwordHash = await hashPassword(newPassword);
  await prisma.user.update({
    where: { id: userId },
    data: { passwordHash },
  });

  return NextResponse.redirect(
    new URL("/admin/users?password_reset=1", appOrigin),
    { status: 303 }
  );
}
