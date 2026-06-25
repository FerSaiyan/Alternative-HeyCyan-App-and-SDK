import { NextResponse } from "next/server";
import { Role, WithdrawalStatus } from "@prisma/client";
import { prisma } from "@/lib/prisma";
import { getSessionUserById, extractCookieValue, parseUserIdCookie } from "@/lib/session-user";

export async function POST(request: Request) {
  const cookieHeader = request.headers.get("cookie") ?? "";
  const userId = parseUserIdCookie(extractCookieValue(cookieHeader, "carelens_user_id"));
  const adminUser = userId ? await getSessionUserById(userId) : null;

  if (!adminUser || adminUser.role !== Role.ADMIN) {
    return NextResponse.redirect(new URL("/admin", request.url));
  }

  const formData = await request.formData();
  const withdrawalId = String(formData.get("withdrawalId") ?? "").trim();
  const status = String(formData.get("status") ?? "").trim() as WithdrawalStatus;

  const baseUrl = new URL("/admin/affiliates", request.url);

  if (!withdrawalId || !status) {
    baseUrl.searchParams.set("error", "withdrawal_not_found");
    return NextResponse.redirect(baseUrl);
  }

  if (status !== "APPROVED" && status !== "REJECTED" && status !== "PAID") {
    baseUrl.searchParams.set("error", "withdrawal_not_found");
    return NextResponse.redirect(baseUrl);
  }

  const withdrawal = await prisma.affiliateWithdrawal.findUnique({ where: { id: withdrawalId } });
  if (!withdrawal) {
    baseUrl.searchParams.set("error", "withdrawal_not_found");
    return NextResponse.redirect(baseUrl);
  }

  await prisma.affiliateWithdrawal.update({
    where: { id: withdrawalId },
    data: {
      status,
      processedAt: status === "APPROVED" || status === "PAID" ? new Date() : undefined,
    },
  });

  baseUrl.searchParams.set("success", "withdrawal_updated");
  return NextResponse.redirect(baseUrl);
}
