import { PrismaClient, DiscountType } from "@prisma/client";

const prisma = new PrismaClient();

async function main() {
  const adminUser = await prisma.user.findFirst({ where: { role: "ADMIN" } });
  if (!adminUser) {
    console.error("Admin user not found. Run create-admin.ts first.");
    process.exit(1);
  }

  const draClareana = await prisma.user.findFirst({
    where: { email: "dra.clareana@carelens.com.br" },
  });

  const testDoctor = await prisma.user.findFirst({
    where: { email: "dr.teste@carelens.com.br" },
  });

  // Create affiliate coupon for Dra Clareana (percent discount)
  if (draClareana) {
    const existing = await prisma.affiliateCoupon.findUnique({
      where: { code: "CLAREANA10" },
    });

    if (!existing) {
      await prisma.affiliateCoupon.create({
        data: {
          code: "CLAREANA10",
          ownerId: draClareana.id,
          discountType: DiscountType.PERCENT,
          discountValue: 10,
        },
      });
      console.log("Created affiliate coupon CLAREANA10 for Dra Clareana (10% off)");
    } else {
      console.log("Coupon CLAREANA10 already exists");
    }
  }

  // Create affiliate coupon for test doctor (fixed discount)
  if (testDoctor) {
    const existing = await prisma.affiliateCoupon.findUnique({
      where: { code: "DRTESTE200" },
    });

    if (!existing) {
      await prisma.affiliateCoupon.create({
        data: {
          code: "DRTESTE200",
          ownerId: testDoctor.id,
          discountType: DiscountType.FIXED,
          discountValue: 200,
        },
      });
      console.log("Created affiliate coupon DRTESTE200 for Dr Teste (R$200 off)");
    } else {
      console.log("Coupon DRTESTE200 already exists");
    }
  }

  // Create a general affiliate coupon for admin (percent)
  const adminCoupon = await prisma.affiliateCoupon.findUnique({
    where: { code: "CARELENS5" },
  });

  if (!adminCoupon) {
    await prisma.affiliateCoupon.create({
      data: {
        code: "CARELENS5",
        ownerId: adminUser.id,
        discountType: DiscountType.PERCENT,
        discountValue: 5,
      },
    });
    console.log("Created affiliate coupon CARELENS5 for admin (5% off)");
  } else {
    console.log("Coupon CARELENS5 already exists");
  }

  console.log("\nSeed complete. Affiliate coupons created:");
  const allCoupons = await prisma.affiliateCoupon.findMany({
    include: { owner: { select: { email: true } } },
  });
  for (const c of allCoupons) {
    console.log(`  ${c.code} -> ${c.owner.email} (${c.discountType === "FIXED" ? `R$${c.discountValue}` : `${c.discountValue}%`})`);
  }
}

main()
  .catch((e) => {
    console.error(e);
    process.exit(1);
  })
  .finally(async () => {
    await prisma.$disconnect();
  });
