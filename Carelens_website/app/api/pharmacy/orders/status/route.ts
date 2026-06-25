import { GlassesOrderStatus, Role } from "@prisma/client";
import { NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import { requireApiRole } from "@/lib/role-guard";

function parseStatus(value: string): GlassesOrderStatus | null {
  if (value === GlassesOrderStatus.ORDERED) {
    return GlassesOrderStatus.ORDERED;
  }
  if (value === GlassesOrderStatus.SHIPPED) {
    return GlassesOrderStatus.SHIPPED;
  }
  if (value === GlassesOrderStatus.DELIVERED) {
    return GlassesOrderStatus.DELIVERED;
  }
  return null;
}

export async function POST(request: Request) {
  const guard = await requireApiRole(request, [Role.ADMIN]);
  if (!guard.ok) {
    return guard.response;
  }

  const formData = await request.formData();
  const orderId = String(formData.get("orderId") ?? "").trim();
  const nextStatus = parseStatus(String(formData.get("status") ?? "").trim());

  if (!orderId || !nextStatus) {
    return NextResponse.json({ ok: false, message: "Atualização de pedido inválida." }, { status: 400 });
  }

  await prisma.glassesOrder.update({
    where: { id: orderId },
    data: { status: nextStatus },
  });

  return NextResponse.redirect(new URL("/pharmacy?updated=1", request.url), { status: 303 });
}
