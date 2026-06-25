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
  const email = "admin@carelens.com.br";
  const password = process.env.ADMIN_PASSWORD;

  if (!password) {
    console.error("Set ADMIN_PASSWORD env var to create the admin account.");
    process.exit(1);
  }

  const existing = await prisma.user.findUnique({
    where: { email },
    select: { id: true },
  });

  if (existing) {
    console.log(`Admin ${email} already exists (id: ${existing.id}). Updating role to ADMIN.`);
    await prisma.user.update({
      where: { id: existing.id },
      data: { role: Role.ADMIN },
    });
    console.log("✅ Role updated to ADMIN.");
    return;
  }

  const passwordHash = await hashPassword(password);

  const user = await prisma.user.create({
    data: {
      email,
      passwordHash,
      role: Role.ADMIN,
      fullName: "Admin CareLens",
      authProvider: AuthProvider.PASSWORD,
    },
  });

  console.log(`✅ Admin created:`);
  console.log(`   Email: ${email}`);
  console.log(`   Role: ADMIN`);
  console.log(`   ID: ${user.id}`);
}

main()
  .catch((e) => {
    console.error("Failed to create admin:", e);
    process.exit(1);
  })
  .finally(() => prisma.$disconnect());
