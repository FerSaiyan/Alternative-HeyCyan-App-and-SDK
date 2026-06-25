import crypto from "node:crypto";
import { prisma } from "@/lib/prisma";

const MAGIC_LINK_TTL_MINUTES = 20;

function hashToken(token: string): string {
  return crypto.createHash("sha256").update(token).digest("hex");
}

export async function createMagicLinkToken(userId: string): Promise<{
  rawToken: string;
  expiresAt: Date;
}> {
  const rawToken = crypto.randomBytes(24).toString("hex");
  const tokenHash = hashToken(rawToken);
  const expiresAt = new Date(Date.now() + MAGIC_LINK_TTL_MINUTES * 60 * 1000);

  await prisma.authMagicLink.create({
    data: {
      userId,
      tokenHash,
      expiresAt,
    },
  });

  return { rawToken, expiresAt };
}

export async function consumeMagicLinkToken(rawToken: string): Promise<{ userId: string } | null> {
  const tokenHash = hashToken(rawToken);

  const link = await prisma.authMagicLink.findUnique({
    where: { tokenHash },
  });

  if (!link) {
    return null;
  }

  if (link.usedAt) {
    return null;
  }

  if (link.expiresAt.getTime() < Date.now()) {
    return null;
  }

  await prisma.authMagicLink.update({
    where: { id: link.id },
    data: {
      usedAt: new Date(),
    },
  });

  return { userId: link.userId };
}
