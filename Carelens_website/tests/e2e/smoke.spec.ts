import { expect, test } from "@playwright/test";

test.describe("CareLens smoke flow", () => {
  test("landing page shows key conversion blocks", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByRole("heading", { level: 1 })).toContainText("Tecnologia assistiva inteligente para um envelhecimento mais seguro");
    await expect(page.getByRole("link", { name: "Saiba Mais" }).first()).toBeVisible();
    await expect(page.getByRole("link", { name: "Como Funciona" }).first()).toBeVisible();
  });

  test("checkout page collects one-time data before coupon", async ({ page }) => {
    await page.goto("/single_purchase_onboarding");
    await expect(page.getByRole("heading", { name: "Entrar ou criar conta" })).toBeVisible();
  });

  test("coupon page prints ticket and drag handle responds", async ({ page }) => {
    await page.goto("/coupon?purchaseType=glasses_subscription&fullName=Teste%20Paciente&email=teste%40carelens.local&sex=FEMALE");
    await expect(page.getByRole("heading", { name: /Assinatura|Cupom/ })).toBeVisible();

    await expect(page.getByTestId("printer-running-indicator")).toBeVisible();
    await expect(page.getByTestId("printer-running-indicator")).toBeHidden({ timeout: 6000 });
    await expect(page.getByRole("link", { name: "Alterar informações" })).toBeVisible();

    await expect(page.getByTestId("printer-shell")).toHaveClass(/opacity-0/, { timeout: 6000 });

    await expect(page.getByRole("button", { name: "Deslizar para cortar ticket" })).toBeVisible();
  });

  test("payment page boots checkout flow (Asaas or Stripe)", async ({ page }) => {
    await page.goto(
      "/payment?purchaseType=glasses_subscription&fullName=Teste%20Paciente&email=teste%40carelens.local&sex=FEMALE&couponCode=WELCOME10",
    );

    await expect(page.getByRole("heading", { name: /Finalize|Pagamento/ })).toBeVisible();

    const isAsaas = (await page.getByText("Forma de pagamento").count()) > 0;

    if (isAsaas) {
      await expect(page.getByRole("button", { name: /^PIX / })).toBeVisible();
      await expect(page.getByRole("button", { name: /^Boleto Bancário/ })).toBeVisible();
      await expect(page.getByText(/Ambiente de demonstração/i)).not.toBeVisible();
      await expect(page).toHaveURL(/\/payment/);
      return;
    }

    const missingKeyWarning = page.getByText("Configure `NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY`");
    if (await missingKeyWarning.count()) {
      await expect(missingKeyWarning).toBeVisible();
      return;
    }

    await expect(page.getByRole("button", { name: "Ir para pagamento" })).toBeVisible();
  });

  test("sign in page shows default create-account flow", async ({ page }) => {
    await page.goto("/signin");
    await expect(page.getByRole("heading", { name: "Entrar ou criar conta" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Entrar com Google" })).toBeVisible();
  });

  test("logout page is accessible", async ({ page }) => {
    await page.goto("/logout");
    await expect(page.getByRole("heading", { name: "Encerrar sessão" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Confirmar saída" })).toBeVisible();
  });

  test("success page and support page are accessible", async ({ page }) => {
    await page.goto("/success?mock=1");
    await expect(page.getByRole("heading", { name: /sucesso|confirmado/ })).toBeVisible();

    await page.goto("/support");
    await expect(page.getByRole("heading", { name: /Precisa de ajuda/ })).toBeVisible();
    await expect(page.getByText("Suporte CareLens")).toBeVisible();
  });

  test("family dashboard requires family role session", async ({ page }) => {
    await page.goto("/family");
    await expect(page.getByRole("heading", { name: "Entrar ou criar conta" })).toBeVisible();
    await expect(page.getByText("Faça login para continuar.")).toBeVisible();
  });

  test("pharmacy dashboard requires admin role session", async ({ page }) => {
    await page.goto("/pharmacy");
    await expect(page.getByRole("heading", { name: "Entrar ou criar conta" })).toBeVisible();
    await expect(page.getByText("Faça login para continuar.")).toBeVisible();
  });

  test("admin users page requires admin role session", async ({ page }) => {
    await page.goto("/admin/users");
    await expect(page.getByRole("heading", { name: "Entrar ou criar conta" })).toBeVisible();
    await expect(page.getByText("Faça login para continuar.")).toBeVisible();
  });
});
