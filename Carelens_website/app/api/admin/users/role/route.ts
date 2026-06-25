import { Role } from "@prisma/client";
import { NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import { requireApiRole } from "@/lib/role-guard";

function parseRole(value: string): Role | null {
  if (value === Role.ELDERLY) {
    return Role.ELDERLY;
  }
  if (value === Role.FAMILY) {
    return Role.FAMILY;
  }
  if (value === Role.PHARMACY) {
    return Role.PHARMACY;
  }
  if (value === Role.ADMIN) {
    return Role.ADMIN;
  }
  return null;
}

export async function POST(request: Request) {
  const guard = await requireApiRole(request, [Role.ADMIN]);
  if (!guard.ok) {
    return guard.response;
  }

  const formData = await request.formData();
  const userId = String(formData.get("userId") ?? "").trim();
  const role = parseRole(String(formData.get("role") ?? "").trim());

  if (!userId || !role) {
    return NextResponse.redirect(new URL("/admin/users?error=invalid", request.url), { status: 303 });
  }

  if (userId === guard.user.id && role !== Role.ADMIN) {
    return NextResponse.redirect(new URL("/admin/users?error=self", request.url), { status: 303 });
  }

  const target = await prisma.user.findUnique({
    where: { id: userId },
    select: { id: true },
  });

  if (!target) {
    return NextResponse.redirect(new URL("/admin/users?error=notfound", request.url), { status: 303 });
  }

  await prisma.user.update({
    where: { id: userId },
    data: { role },
  });

  return NextResponse.redirect(new URL("/admin/users?updated=1", request.url), { status: 303 });
}
