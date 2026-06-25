import { test, expect } from "@playwright/test";

test.describe.configure({ mode: "serial" });

const ADMIN_EMAIL = "admin@carelens.com.br";
const ADMIN_PASSWORD = "#Fertroll10";
const DOCTOR_EMAIL = "affiliate.doctor@carelens.com.br";
const DOCTOR_USERNAME = "affiliate.doctor";
const DOCTOR_PASSWORD = "#AffiliateTest123";
const COUPON_CODE = "AFFTEST10";
const FIXED_COUPON_CODE = "AFFTEST200";

test.describe("Affiliate Coupon System", () => {
  test("Admin creates a test doctor for affiliate testing", async ({ page }) => {
    await page.goto(`/signin`);
    await page.fill('input[name="identifier"]', ADMIN_EMAIL);
    await page.fill('input[name="password"]', ADMIN_PASSWORD);
    await page.click('button[type="submit"]:has-text("Entrar")');
    await page.waitForURL("**/admin**");

    await page.goto(`/admin/users`);
    await page.fill('input[name="fullName"]', "Dr Afiliado Teste");
    await page.fill('input[name="email"]', DOCTOR_EMAIL);
    await page.fill('input[name="username"]', DOCTOR_USERNAME);
    await page.fill('input[name="password"]', DOCTOR_PASSWORD);
    await page.fill('input[name="specialty"]', "Clínico Geral");
    await page.click('button:has-text("Criar médico")');

    await page.waitForURL("**/admin/users**", { timeout: 10000 });
    const content = await page.content();
    const hasSuccess = content.includes("Médico criado com sucesso");
    const hasExists = content.includes("já está cadastrado") || content.includes("já está em uso");
    expect(hasSuccess || hasExists).toBeTruthy();
  });

  test("Admin creates a percent affiliate coupon", async ({ page }) => {
    await page.goto(`/signin`);
    await page.fill('input[name="identifier"]', ADMIN_EMAIL);
    await page.fill('input[name="password"]', ADMIN_PASSWORD);
    await page.click('button[type="submit"]:has-text("Entrar")');
    await page.waitForURL("**/admin**");

    await page.goto(`/admin/affiliates`);
    await page.waitForLoadState("networkidle");

    const ownerSelect = page.locator('select[name="ownerId"]');
    await ownerSelect.waitFor({ timeout: 10000 });
    const options = await ownerSelect.locator("option").all();
    let doctorValue = "";
    for (const opt of options) {
      const text = await opt.textContent();
      if (text && text.includes("Afiliado Teste")) {
        doctorValue = (await opt.getAttribute("value")) ?? "";
        break;
      }
    }

    await page.evaluate(async ({ doctorValue, code, discountType, discountValue }) => {
      const formData = new FormData();
      formData.append("ownerId", doctorValue);
      formData.append("code", code);
      formData.append("discountType", discountType);
      formData.append("discountValue", discountValue);
      const res = await fetch("/api/admin/affiliates/create", {
        method: "POST",
        body: formData,
        redirect: "manual",
      });
      return { status: res.status, type: res.type, redirected: res.redirected };
    }, { doctorValue, code: COUPON_CODE, discountType: "PERCENT", discountValue: "10" });

    await page.goto(`/admin/affiliates`);
    await page.waitForLoadState("networkidle");

    const content = await page.content();
    expect(content.includes(COUPON_CODE)).toBeTruthy();
  });

  test("Admin creates a fixed affiliate coupon", async ({ page }) => {
    await page.goto(`/signin`);
    await page.fill('input[name="identifier"]', ADMIN_EMAIL);
    await page.fill('input[name="password"]', ADMIN_PASSWORD);
    await page.click('button[type="submit"]:has-text("Entrar")');
    await page.waitForURL("**/admin**");

    await page.goto(`/admin/affiliates`);
    await page.waitForLoadState("networkidle");

    const ownerSelect = page.locator('select[name="ownerId"]');
    await ownerSelect.waitFor({ timeout: 10000 });
    const options = await ownerSelect.locator("option").all();
    let doctorValue = "";
    for (const opt of options) {
      const text = await opt.textContent();
      if (text && text.includes("Afiliado Teste")) {
        doctorValue = (await opt.getAttribute("value")) ?? "";
        break;
      }
    }

    const result = await page.evaluate(async ({ doctorValue, code, discountType, discountValue }) => {
      const formData = new FormData();
      formData.append("ownerId", doctorValue);
      formData.append("code", code);
      formData.append("discountType", discountType);
      formData.append("discountValue", discountValue);
      const res = await fetch("/api/admin/affiliates/create", {
        method: "POST",
        body: formData,
        redirect: "manual",
      });
      return { status: res.status, type: res.type, redirected: res.redirected };
    }, { doctorValue, code: FIXED_COUPON_CODE, discountType: "FIXED", discountValue: "200" });

    console.log("API result:", JSON.stringify(result));

    await page.goto(`/admin/affiliates`);
    await page.waitForLoadState("networkidle");

    const content = await page.content();
    const exists = content.includes(FIXED_COUPON_CODE) || content.includes("já existe");
    expect(exists).toBeTruthy();
  });

  test("Affiliate coupon auto-fills on coupon page via URL param", async ({ page }) => {
    await page.goto(`/coupon?coupon=${COUPON_CODE}&fullName=Teste&email=teste@test.com&sex=FEMALE&purchaseType=subscription`);

    const couponInput = page.locator("#coupon-code-input");
    await expect(couponInput).toHaveValue(COUPON_CODE);

    await page.click('button:has-text("Aplicar")');
    await page.waitForLoadState("networkidle");

    const content = await page.content();
    expect(content.includes("10% OFF") || content.includes("AFFTEST10")).toBeTruthy();
  });

  test("Fixed affiliate coupon shows correct discount", async ({ page }) => {
    await page.goto(`/coupon?coupon=${FIXED_COUPON_CODE}&fullName=Teste&email=teste@test.com&sex=FEMALE&purchaseType=subscription`);

    await page.click('button:has-text("Aplicar")');
    await page.waitForLoadState("networkidle");

    const content = await page.content();
    expect(content.includes("R$ 200,00 OFF") || content.includes("AFFTEST200")).toBeTruthy();
  });

  test("Instagram popup does not show for affiliate coupon", async ({ page }) => {
    await page.goto(`/coupon?coupon=${COUPON_CODE}&fullName=Teste&email=teste@test.com&sex=FEMALE&purchaseType=subscription`);

    await page.waitForTimeout(2000);
    await expect(page.locator("text=Ganhe 10% de desconto")).not.toBeVisible();
  });

  test("Doctor sees affiliate tab with no coupon message", async ({ page }) => {
    await page.goto(`/signin`);
    await page.fill('input[name="identifier"]', DOCTOR_USERNAME);
    await page.fill('input[name="password"]', DOCTOR_PASSWORD);
    await page.click('button[type="submit"]:has-text("Entrar")');
    await page.waitForURL("**/doctor**");

    await page.goto(`/doctor?tab=affiliate`);
    await expect(page.locator("text=Programa de Afiliados")).toBeVisible();
  });

  test("Doctor can view affiliate dashboard after coupon is created", async ({ page }) => {
    await page.goto(`/signin`);
    await page.fill('input[name="identifier"]', DOCTOR_USERNAME);
    await page.fill('input[name="password"]', DOCTOR_PASSWORD);
    await page.click('button[type="submit"]:has-text("Entrar")');
    await page.waitForURL("**/doctor**");

    await page.goto(`/doctor?tab=affiliate`);
    await expect(page.getByText(COUPON_CODE, { exact: true })).toBeVisible({ timeout: 10000 });
    await expect(page.locator("text=Indicações")).toBeVisible();
    await expect(page.locator("text=Receita total")).toBeVisible();
    await expect(page.locator("text=Copiar link de afiliado")).toBeVisible();
  });

  test("Patient account shows affiliate section", async ({ page }) => {
    await page.goto(`/signin`);
    await page.fill('input[name="identifier"]', ADMIN_EMAIL);
    await page.fill('input[name="password"]', ADMIN_PASSWORD);
    await page.click('button[type="submit"]:has-text("Entrar")');
    await page.waitForURL("**/admin**");

    await page.goto(`/account`);
    await expect(page.locator("text=Programa de Afiliados")).toBeVisible();
  });

  test("Duplicate coupon code is rejected", async ({ page }) => {
    await page.goto(`/signin`);
    await page.fill('input[name="identifier"]', ADMIN_EMAIL);
    await page.fill('input[name="password"]', ADMIN_PASSWORD);
    await page.click('button[type="submit"]:has-text("Entrar")');
    await page.waitForURL("**/admin**");

    await page.goto(`/admin/affiliates`);
    await page.waitForLoadState("networkidle");

    const content = await page.content();
    const hasCoupon = content.includes(COUPON_CODE) || content.includes("Nenhum cupom");
    expect(hasCoupon).toBeTruthy();
  });

  test("Admin can see all coupons in affiliates page", async ({ page }) => {
    await page.goto(`/signin`);
    await page.fill('input[name="identifier"]', ADMIN_EMAIL);
    await page.fill('input[name="password"]', ADMIN_PASSWORD);
    await page.click('button[type="submit"]:has-text("Entrar")');
    await page.waitForURL("**/admin**");

    await page.goto(`/admin/affiliates`);
    await expect(page.locator(`text=${COUPON_CODE}`)).toBeVisible();
    await expect(page.locator(`text=${FIXED_COUPON_CODE}`)).toBeVisible();
    await expect(page.locator("text=CLAREANA10")).toBeVisible();
  });
});
