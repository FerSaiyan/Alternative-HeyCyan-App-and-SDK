import { PrismaClient, Role, AuthProvider } from "@prisma/client";
import { randomBytes, scrypt as scryptCb } from "node:crypto";
import { promisify } from "node:util";

const scrypt = promisify(scryptCb);
const prisma = new PrismaClient();

async function hashPassword(password: string): Promise<string> {
  const normalized = password.trim();
  const salt = randomBytes(16).toString("hex");
  const derived = (await scrypt(normalized, salt, 64)) as Buffer;
  return `scrypt$${salt}$${derived.toString("hex")}`;
}

async function main() {
  const email = "familia@carelens.com.br";
  const username = "familia.carelens";
  const password = process.env.FAMILY_SEED_PASSWORD;
  if (!password) {
    console.error("Set FAMILY_SEED_PASSWORD env var to run this seed.");
    process.exit(1);
  }
  const fullName = "Familia CareLens";

  // Check if already exists
  const existing = await prisma.user.findUnique({
    where: { email },
    select: { id: true },
  });

  if (existing) {
    console.log(`Family user ${email} already exists (id: ${existing.id}). Skipping creation.`);
    return;
  }

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

  await prisma.familyProfile.create({
    data: {
      userId: user.id,
      displayName: fullName,
      relationship: "Cuidador familiar",
    },
  });

  console.log(`✅ Family user created:`);
  console.log(`   Email: ${email}`);
  console.log(`   Username: ${username}`);
  console.log(`   Password: ${password}`);
  console.log(`   Role: FAMILY`);
  console.log(`   ID: ${user.id}`);
  console.log(`\n⚠️  Change the temporary password after first login!`);
}

main()
  .catch((e) => {
    console.error("Failed to seed family user:", e);
    process.exit(1);
  })
  .finally(() => prisma.$disconnect());
