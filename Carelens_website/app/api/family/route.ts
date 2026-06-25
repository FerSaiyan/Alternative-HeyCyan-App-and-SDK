import { NextResponse } from "next/server";
import { Role, SubscriptionStatus } from "@prisma/client";
import { prisma } from "@/lib/prisma";
import { requireApiRole } from "@/lib/role-guard";

export async function GET(request: Request) {
  const auth = await requireApiRole(request, [Role.FAMILY, Role.ADMIN]);
  if (!auth.ok) {
    return auth.response;
  }

  // Find the most recently created elderly user
  const elderlyUser = await prisma.user.findFirst({
    where: { role: Role.ELDERLY },
    orderBy: { createdAt: "desc" },
    select: {
      id: true,
      fullName: true,
      email: true,
      dateOfBirth: true,
      visionLevel: true,
      hearingLevel: true,
      mobilityLevel: true,
      medications: true,
      allergies: true,
      emergencyContactName: true,
      emergencyContactPhone: true,
      glassesColor: true,
      createdAt: true,
    },
  });

  if (!elderlyUser) {
    return NextResponse.json(
      { ok: false, message: "Nenhum idoso encontrado." },
      { status: 404 },
    );
  }

  // Get subscription info
  const subscription = await prisma.subscription.findFirst({
    where: { userId: elderlyUser.id },
    orderBy: { createdAt: "desc" },
    select: {
      status: true,
      monthlyPriceBrl: true,
      createdAt: true,
    },
  });

  // Get glasses order
  const glassesOrder = await prisma.glassesOrder.findFirst({
    where: { userId: elderlyUser.id },
    orderBy: { createdAt: "desc" },
    select: {
      status: true,
      color: true,
      trackingCode: true,
      createdAt: true,
    },
  });

  const age = elderlyUser.dateOfBirth
    ? (() => {
        const today = new Date();
        let age = today.getFullYear() - elderlyUser.dateOfBirth.getFullYear();
        const monthDiff = today.getMonth() - elderlyUser.dateOfBirth.getMonth();
        if (monthDiff < 0 || (monthDiff === 0 && today.getDate() < elderlyUser.dateOfBirth.getDate())) {
          age--;
        }
        return age;
      })()
    : null;

  return NextResponse.json({
    ok: true,
    elderly: {
      ...elderlyUser,
      age,
      subscription: subscription
        ? {
            status: subscription.status,
            monthlyPriceBrl: subscription.monthlyPriceBrl,
            createdAt: subscription.createdAt.toISOString(),
            isActive:
              subscription.status === SubscriptionStatus.ACTIVE ||
              subscription.status === SubscriptionStatus.REFUND_PENDING,
          }
        : null,
      glassesOrder: glassesOrder
        ? {
            status: glassesOrder.status,
            color: glassesOrder.color,
            trackingCode: glassesOrder.trackingCode,
            createdAt: glassesOrder.createdAt.toISOString(),
            deviceConnected:
              glassesOrder.status === "DELIVERED" || glassesOrder.status === "SHIPPED",
          }
        : null,
      dateOfBirth: elderlyUser.dateOfBirth?.toISOString() ?? null,
      createdAt: elderlyUser.createdAt.toISOString(),
    },
  });
}
