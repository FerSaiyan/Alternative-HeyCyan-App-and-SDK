import { PrismaClient, Role } from "@prisma/client";

const prisma = new PrismaClient();

const defaults = {
  admin: process.env.SEED_ADMIN_EMAIL ?? "admin@carelens.local",
  family: process.env.SEED_FAMILY_EMAIL ?? "family@carelens.local",
};

async function upsertRoleUser(email, role) {
  return prisma.user.upsert({
    where: { email },
    update: { role },
    create: { email, role },
    select: { id: true, email: true, role: true },
  });
}

async function main() {
  const users = await Promise.all([
    upsertRoleUser(defaults.admin.trim().toLowerCase(), Role.ADMIN),
    upsertRoleUser(defaults.family.trim().toLowerCase(), Role.FAMILY),
  ]);

  process.stdout.write("Role seed completed:\n");
  for (const user of users) {
    process.stdout.write(`- ${user.role}: ${user.email} (${user.id})\n`);
  }
}

main()
  .catch((error) => {
    process.stderr.write(`Role seed failed: ${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = 1;
  })
  .finally(async () => {
    await prisma.$disconnect();
  });
