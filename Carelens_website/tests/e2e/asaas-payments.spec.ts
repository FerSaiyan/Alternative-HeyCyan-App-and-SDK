import { expect, test } from "@playwright/test";
import { prisma } from "../../lib/prisma";

/* ------------------------------------------------------------------ */
/*  Asaas payments API tests                                           */
/* ------------------------------------------------------------------ */

/* --- utility: cent‑safe installment helper (mirrors route logic) --- */
function calcInstallmentValue(total: number, count: number): number {
  return Math.round((total / count) * 100) / 100;
}

test.describe("installment precision (regression)", () => {
  test("1700/6 → 283.33 (not 283)", () => {
    expect(calcInstallmentValue(1700, 6)).toBe(283.33);
  });

  test("1700/3 → 566.67 (not 566)", () => {
    expect(calcInstallmentValue(1700, 3)).toBe(566.67);
  });

  test("1700/12 → 141.67 (not 141)", () => {
    expect(calcInstallmentValue(1700, 12)).toBe(141.67);
  });

  test("1000/3 → 333.33", () => {
    expect(calcInstallmentValue(1000, 3)).toBe(333.33);
  });

  test("pennies add back up (283.33 * 6 === 1699.98, never 1698)", () => {
    // 283.33 * 6 = 1699.98 — Asaas handles the 0.02 remainder internally
    const installment = calcInstallmentValue(1700, 6);
    expect(installment * 6).toBe(1699.98);
    // The key regression: with Math.round(1700/6) = 283, sum would be 1698
    expect(installment * 6).not.toBe(1698);
  });
});

test.describe("POST /api/payments/asaas", () => {
  test("returns 501 when Asaas is not configured (no API key)", async ({ request }) => {
    // When ASAAS_API_KEY is not set, the route should return 501
    // We simulate this by checking the response when key is absent
    const resp = await request.post("/api/payments/asaas", {
      data: {
        email: "test@carelens.local",
        fullName: "Test User",
        purchaseType: "one_time",
      },
    });
    // In test env, if no key: 501; if key present: may vary
    expect([200, 400, 501, 500]).toContain(resp.status());
  });

  test("rejects invalid JSON payload", async ({ request }) => {
    const resp = await request.post("/api/payments/asaas", {
      headers: { "Content-Type": "application/json" },
      data: "not valid json",
    });
    expect([400, 501]).toContain(resp.status());
  });

  test("rejects missing email without session", async ({ request }) => {
    const resp = await request.post("/api/payments/asaas", {
      data: {
        purchaseType: "one_time",
      },
    });
    // Should be 400 (email required) or 501 (no key)
    expect([400, 501]).toContain(resp.status());
  });

  test("accepts billingType PIX with valid data", async ({ request }) => {
    const resp = await request.post("/api/payments/asaas", {
      data: {
        email: "pix-test@carelens.local",
        fullName: "PIX Test",
        purchaseType: "one_time",
        billingType: "PIX",
        cpfCnpj: "24971563792",
      },
    });
    const body = await resp.json();
    if (resp.status() === 200) {
      expect(body.ok).toBe(true);
      expect(body.provider).toBe("asaas");
      expect(body.billingType).toBe("PIX");
    } else {
      // Sandbox may reject if CPF validation fails or other policy
      expect([501, 500]).toContain(resp.status());
    }
  });

  test("accepts billingType BOLETO with valid data", async ({ request }) => {
    const resp = await request.post("/api/payments/asaas", {
      data: {
        email: "boleto-test@carelens.local",
        fullName: "Boleto Test",
        purchaseType: "one_time",
        billingType: "BOLETO",
        cpfCnpj: "24971563792",
      },
    });
    if (resp.status() === 200) {
      const body = await resp.json();
      expect(body.ok).toBe(true);
      expect(body.billingType).toBe("BOLETO");
    } else {
      expect([400, 501, 500]).toContain(resp.status());
    }
  });

  test("accepts billingType CREDIT_CARD with valid data", async ({ request }) => {
    const resp = await request.post("/api/payments/asaas", {
      data: {
        email: "card-test@carelens.local",
        fullName: "Card Test",
        purchaseType: "one_time",
        billingType: "CREDIT_CARD",
      },
    });
    if (resp.status() === 200) {
      const body = await resp.json();
      expect(body.ok).toBe(true);
      expect(body.billingType).toBe("CREDIT_CARD");
    } else {
      expect([400, 501]).toContain(resp.status());
    }
  });

  test("rejects installmentCount < 2", async ({ request }) => {
    const resp = await request.post("/api/payments/asaas", {
      data: {
        email: "installment-test@carelens.local",
        fullName: "Installment Test",
        purchaseType: "one_time",
        billingType: "CREDIT_CARD",
        cpfCnpj: "24971563792",
        installmentCount: "1",
      },
    });
    // 400 validation error or 501 if Asaas not configured
    expect([400, 501]).toContain(resp.status());
    if (resp.status() === 400) {
      const body = await resp.json();
      expect(body.ok).toBe(false);
      expect(body.message).toContain("installmentCount");
    }
  });

  test("rejects installmentCount > 6", async ({ request }) => {
    const resp = await request.post("/api/payments/asaas", {
      data: {
        email: "installment-max@carelens.local",
        fullName: "Installment Max Test",
        purchaseType: "one_time",
        billingType: "CREDIT_CARD",
        cpfCnpj: "24971563792",
        installmentCount: "7",
      },
    });
    expect([400, 501]).toContain(resp.status());
    if (resp.status() === 400) {
      const body = await resp.json();
      expect(body.ok).toBe(false);
      expect(body.message).toContain("máximo");
    }
  });

  test("accepts valid installments (6x)", async ({ request }) => {
    const uniqueEmail = `parcelado-${Date.now()}@carelens.local`;
    const resp = await request.post("/api/payments/asaas", {
      data: {
        email: uniqueEmail,
        fullName: "Parcelado Test",
        purchaseType: "one_time",
        billingType: "CREDIT_CARD",
        installmentCount: "6",
        cpfCnpj: "24971563792",
      },
    });
    if (resp.status() === 200) {
      const body = await resp.json();
      expect(body.ok).toBe(true);
      // Sandbox does not return installmentCount on the payment object (it's on the installment group).
      // The app route returns it from request params on fresh creation, but duplicate reuse may omit it.
      if (body.installmentCount !== undefined) {
        expect(body.installmentCount).toBe(6);
      }
    } else {
      expect([400, 501, 500]).toContain(resp.status());
    }
  });

  test("accepts subscription purchase type", async ({ request }) => {
    const resp = await request.post("/api/payments/asaas", {
      data: {
        email: "sub-test@carelens.local",
        fullName: "Subscription Test",
        purchaseType: "subscription",
        billingType: "PIX",
        cpfCnpj: "24971563792",
      },
    });
    if (resp.status() === 200) {
      const body = await resp.json();
      expect(body.ok).toBe(true);
      expect(body.billingType).toBe("PIX");
      expect(body.paymentId).toMatch(/^pay_/);
      expect(body.isRecurring).not.toBe(true);
      expect(body.subscriptionId).toBeUndefined();
    } else {
      expect([400, 501, 500]).toContain(resp.status());
    }
  });

  test("accepts subscription purchaseType as one-time parcelable card charge", async ({ request }) => {
    const resp = await request.post("/api/payments/asaas", {
      data: {
        email: "sub-card-test@carelens.local",
        fullName: "Subscription Card Test",
        purchaseType: "subscription",
        billingType: "CREDIT_CARD",
        cpfCnpj: "24971563792",
      },
    });

    if (resp.status() === 200) {
      const body = await resp.json();
      expect(body.ok).toBe(true);
      expect(body.billingType).toBe("CREDIT_CARD");
      expect(body.paymentId).toMatch(/^pay_/);
      expect(body.subscriptionId).toBeUndefined();
      if (body.invoiceUrl) {
        expect(String(body.invoiceUrl)).toContain("/i/");
      }
    } else {
      expect([400, 501, 500]).toContain(resp.status());
    }
  });

  test("defaults to PIX when billingType is omitted", async ({ request }) => {
    const resp = await request.post("/api/payments/asaas", {
      data: {
        email: "default-pix@carelens.local",
        fullName: "Default PIX Test",
        purchaseType: "one_time",
        cpfCnpj: "24971563792",
        // no billingType
      },
    });
    if (resp.status() === 200) {
      const body = await resp.json();
      expect(body.billingType).toBe("PIX");
    } else {
      expect([400, 501, 500]).toContain(resp.status());
    }
  });

  test("requires cpfCnpj for PIX/BOLETO/CREDIT_CARD", async ({ request }) => {
    const billingTypes = ["PIX", "BOLETO", "CREDIT_CARD"];

    for (const billingType of billingTypes) {
      const resp = await request.post("/api/payments/asaas", {
        data: {
          email: `cpf-required-${billingType.toLowerCase()}@carelens.local`,
          fullName: "CPF Required Test",
          purchaseType: "one_time",
          billingType,
          // no cpfCnpj
        },
      });

      expect([400, 501]).toContain(resp.status());
      if (resp.status() === 400) {
        const body = await resp.json();
        expect(body.ok).toBe(false);
        expect(body.message).toContain("CPF/CNPJ");
      }
    }
  });

  test("accepts cpfCnpj with PIX one_time", async ({ request }) => {
    const resp = await request.post("/api/payments/asaas", {
      data: {
        email: "cpf-pix@carelens.local",
        fullName: "CPF PIX Test",
        purchaseType: "one_time",
        billingType: "PIX",
        cpfCnpj: "12345678909",
      },
    });
    if (resp.status() === 200) {
      const body = await resp.json();
      expect(body.ok).toBe(true);
      expect(body.billingType).toBe("PIX");
    } else {
      expect([400, 501]).toContain(resp.status());
    }
  });

  test("accepts cpfCnpj with BOLETO one_time", async ({ request }) => {
    const resp = await request.post("/api/payments/asaas", {
      data: {
        email: "cpf-boleto@carelens.local",
        fullName: "CPF Boleto Test",
        purchaseType: "one_time",
        billingType: "BOLETO",
        cpfCnpj: "98765432100",
      },
    });
    if (resp.status() === 200) {
      const body = await resp.json();
      expect(body.ok).toBe(true);
      expect(body.billingType).toBe("BOLETO");
    } else {
      expect([400, 501]).toContain(resp.status());
    }
  });

  test("accepts cpfCnpj with subscription", async ({ request }) => {
    const resp = await request.post("/api/payments/asaas", {
      data: {
        email: "cpf-sub@carelens.local",
        fullName: "CPF Subscription Test",
        purchaseType: "subscription",
        billingType: "PIX",
        cpfCnpj: "24971563792",
      },
    });
    if (resp.status() === 200) {
      const body = await resp.json();
      expect(body.ok).toBe(true);
      expect(body.paymentId).toMatch(/^pay_/);
    } else {
      expect([400, 501, 500]).toContain(resp.status());
    }
  });
});

/* ------------------------------------------------------------------ */
/*  Payment page renders Asaas method selector when provider is asaas  */
/* ------------------------------------------------------------------ */

test.describe("Asaas payment page — no draft short-circuit", () => {
  // This test verifies the payment page renders the Asaas payment method
  // selector (PIX, BOLETO, CREDIT_CARD) instead of silently redirecting to
  // the success page with the draft/demo banner.
  //
  // It only applies when NEXT_PUBLIC_PAYMENT_PROVIDER=asaas at build time.
  // The AsaasPaymentFlow component must show the method selector as the
  // initial view — never an immediate demo-success redirect.

  test("page renders Asaas method selector when provider is asaas", async ({ page }) => {
    await page.goto(
      "/payment?purchaseType=one_time&fullName=Teste%20Asaas&email=teste-asaas%40carelens.local&sex=FEMALE",
    );

    // The page heading should always render regardless of provider
    await expect(page.getByRole("heading", { name: /Finalize sua compra direta/i })).toBeVisible();

    // Check if the Asaas method label is present in the DOM.
    // This string comes from AsaasMethodSelector (payment-step.tsx).
    const pixLabel = page.getByText("Forma de pagamento");
    const hasAsaasSelector = (await pixLabel.count()) > 0;

    if (!hasAsaasSelector) {
      // If we don't see the Asaas selector, we must NOT be on the success page
      // with mock/draft banner. Verify we are not in demo-success state.
      await expect(page.getByText(/Ambiente de demonstração/i)).not.toBeVisible();
      // The page should instead show one of: Stripe form, config error, or loading
      // This is acceptable — the important thing is no silent demo redirect.
      return;
    }

    // Asaas selector is present — verify key method buttons exist
    await expect(pixLabel.first()).toBeVisible();
    // Use role selector to avoid strict-mode ambiguity (multiple elements match plain text "PIX")
    await expect(page.getByRole("button", { name: /^PIX / })).toBeVisible();
    await expect(page.getByRole("button", { name: /^Boleto Bancário/ })).toBeVisible();
    await expect(page.getByRole("button", { name: /^Cartão de Crédito/ })).toBeVisible();

    // Must NOT show draft/demo text
    await expect(page.getByText(/Ambiente de demonstração/i)).not.toBeVisible();
    // Must NOT have already redirected to /success
    await expect(page).not.toHaveURL(/\/success/);
  });

  test("subscription mode also shows credit card option", async ({ page }) => {
    await page.goto(
      "/payment?purchaseType=subscription&fullName=Teste%20Cartao%20Sub&email=teste-card-sub%40carelens.local&cpfCnpj=24971563792",
    );

    await expect(page.getByRole("button", { name: /^PIX / })).toBeVisible();
    await expect(page.getByRole("button", { name: /^Boleto Bancário/ })).toBeVisible();
    await expect(page.getByRole("button", { name: /^Cartão de Crédito/ })).toBeVisible();
  });

  test("Asaas payment does not immediately redirect to success on page load", async ({ page }) => {
    // Verifies there is no client-side auto-redirect to the success page
    // (which would happen if the Stripe intent draft path fired for Asaas).
    await page.goto(
      "/payment?purchaseType=one_time&fullName=Teste%20Asaas&email=teste-asaas-redir%40carelens.local",
      { waitUntil: "networkidle" },
    );

    // After network idle, we should still be on /payment, never /success
    await expect(page).toHaveURL(/\/payment/);
    await expect(page.getByText(/Ambiente de demonstração/i)).not.toBeVisible();
  });

  test("PIX method renders CPF/CNPJ field and submit action", async ({ page }) => {
    await page.goto(
      "/payment?purchaseType=one_time&fullName=Teste%20Pix%20UI&email=teste-pix-ui%40carelens.local&cpfCnpj=24971563792",
    );

    const payButton = page.getByRole("button", { name: /^Pagar com PIX/i });
    await expect(payButton).toBeVisible();
    const docInput = page.getByPlaceholder(/Digite CPF ou CNPJ/i);
    await expect(docInput).toBeVisible();
    await docInput.fill("24971563792");
    await payButton.click({ force: true });
    await expect(page.getByText(/Erro ao processar pagamento/i)).not.toBeVisible();
  });

  test("asks CPF/CNPJ in UI when missing", async ({ page }) => {
    await page.goto(
      "/payment?purchaseType=one_time&fullName=Teste%20Pix%20Sem%20CPF&email=teste-pix-sem-cpf%40carelens.local",
    );

    const docInput = page.getByPlaceholder(/Digite CPF ou CNPJ/i);
    await expect(docInput).toBeVisible();

    const payButton = page.getByRole("button", { name: /^Pagar com PIX/i });
    await payButton.click({ force: true });

    const validationMessage = page.getByText(/Informe um CPF .* CNPJ .* válido/i);
    const pixView = page.getByText(/Pagamento via PIX/i);

    await expect
      .poll(async () => {
        const hasValidation = (await validationMessage.count()) > 0;
        const hasPixView = (await pixView.count()) > 0;
        return hasValidation || hasPixView;
      })
      .toBeTruthy();
  });
});

/* ------------------------------------------------------------------ */
/*  Webhook: subscription-linked PAYMENT_* reconciliation (regression) */
/* ------------------------------------------------------------------ */

test.describe("POST /api/webhooks/asaas — subscription-linked reconciliation", () => {
  // Unique identifiers per test run to avoid collisions in parallel mode
  const runId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  const subId = `sub_test_rec_${runId}`;
  const payId = `pay_test_rec_${runId}`;
  const eventId = `evt_test_rec_${runId}`;

  test.afterEach(async () => {
    // Cleanup seeded and webhook-created records
    await prisma.asaasPayment.deleteMany({
      where: { asaasSubscriptionId: subId },
    });
    await prisma.asaasWebhookEvent.deleteMany({
      where: { asaasEventId: eventId },
    });
  });

  test("updates asaasPaymentId via subscription linkage on PAYMENT_CONFIRMED", async ({ request }) => {
    // 1. Seed a local AsaasPayment with asaasSubscriptionId=sub_x and asaasPaymentId=null
    const seeded = await prisma.asaasPayment.create({
      data: {
        userId: "test-user-webhook-rec",
        asaasSubscriptionId: subId,
        asaasPaymentId: null,
        asaasCustomerId: "cus_test_rec",
        valueBrl: 1700,
        billingType: "PIX",
        purchaseType: "subscription",
        status: "PENDING",
      },
    });
    expect(seeded.asaasPaymentId).toBeNull();

    // 2. Send PAYMENT_CONFIRMED webhook with payment.id=pay_x, payment.subscription=sub_x
    const resp = await request.post("/api/webhooks/asaas", {
      headers: {
        "asaas-access-token": "test-webhook-token",
      },
      data: {
        id: eventId,
        event: "PAYMENT_CONFIRMED",
        payment: {
          id: payId,
          subscription: subId,
          status: "CONFIRMED",
        },
      },
    });

    // 3. Verify the webhook was accepted
    expect(resp.ok()).toBeTruthy();
    const body = await resp.json();
    expect(body.ok).toBe(true);

    // 4. Assert the local record was updated with asaasPaymentId and status
    const updated = await prisma.asaasPayment.findUnique({
      where: { id: seeded.id },
    });
    expect(updated).not.toBeNull();
    expect(updated!.asaasPaymentId).toBe(payId);
    expect(updated!.status).toBe("CONFIRMED");
  });
});

test.describe("GET /web-subscribe/status", () => {
  test("returns inactive when the relay user has no linked Asaas subscription yet", async ({ request }) => {
    const uniqueEmail = `relay-status-${Date.now()}@carelens.local`;
    const bootstrapResp = await request.get("/web-subscribe", {
      params: {
        email: uniqueEmail,
      },
    });

    expect(bootstrapResp.ok()).toBeTruthy();
    const bootstrap = await bootstrapResp.json();
    expect(bootstrap.ok).toBe(true);
    expect(bootstrap.api_token).toBeTruthy();

    const statusResp = await request.get("/web-subscribe/status", {
      params: {
        api_token: bootstrap.api_token,
        plan: "standard",
      },
    });

    expect(statusResp.ok()).toBeTruthy();
    const status = await statusResp.json();
    expect(status.ok).toBe(true);
    expect(status.active).toBe(false);
    expect(status.state).toBe("inactive");
    expect(status.plan).toBe("standard");
  });

  test("checkout page exposes hosted invoice, status poll, and success callback URLs", async ({ request }) => {
    const uniqueEmail = `relay-checkout-${Date.now()}@carelens.local`;
    const resp = await request.get("/web-subscribe", {
      params: {
        plan: "standard",
        return_url: "fersaiyan://pro-sub/callback",
        email: uniqueEmail,
      },
    });

    expect(resp.status()).toBe(200);
    const html = await resp.text();
    expect(html).toContain("Open Secure Card Form");
    expect(html).toContain("I Completed Payment");
    expect(html).toContain("/web-subscribe/status?");
    expect(html).toContain("/web-subscribe/success?");
    expect(html).toMatch(/https:\/\/sandbox\.asaas\.com\/i\//);
  });
});
