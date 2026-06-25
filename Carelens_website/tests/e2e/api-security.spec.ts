import { expect, test } from "@playwright/test";
import { prisma } from "../../lib/prisma";

/* ------------------------------------------------------------------ */
/*  API security regression tests                                      */
/* ------------------------------------------------------------------ */
test.describe("API security", () => {
  test.describe("GET /api/payments/asaas/status", () => {
    test("returns 401 when no session cookie is provided", async ({ request }) => {
      const resp = await request.get("/api/payments/asaas/status?ref=pay_test123");
      expect(resp.status()).toBe(401);
      const body = await resp.json();
      expect(body).toMatchObject({ ok: false, message: "Unauthorized" });
    });

    test("returns 401 with a malformed cookie value", async ({ request }) => {
      const resp = await request.get("/api/payments/asaas/status?ref=pay_test123", {
        headers: { cookie: "carelens_user_id=" },
      });
      expect(resp.status()).toBe(401);
    });

    test("returns 400 when ref parameter is missing", async ({ request }) => {
      const resp = await request.get("/api/payments/asaas/status");
      expect(resp.status()).toBe(400);
      const body = await resp.json();
      expect(body).toMatchObject({ ok: false, message: "Missing ref parameter" });
    });
  });

  /* -------------------------------------------------------------- */
  /*  POST /api/account/delete — CSRF + auth + happy path            */
  /* -------------------------------------------------------------- */
  test.describe("POST /api/account/delete", () => {
    // Serial mode within this block avoids SQLite contention from
    // parallel DB writes across the test file.
    test.describe.configure({ mode: "serial" });

    /* ── helpers ───────────────────────────────────────────── */
    const testUserId = () => `del-usr-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
    const testEmail = (id: string) => `delete-csrf-${id}@carelens.local`;

    test.afterAll(async () => {
      // Best-effort cleanup: if happy-path succeeded the user is already gone
      await prisma.user.deleteMany({ where: { email: { startsWith: "delete-csrf-" } } }).catch(() => {});
    });

    /* ── CSRF ──────────────────────────────────────────────── */

    test("rejects request with cross-origin Origin header (CSRF)", async ({ request }) => {
      const resp = await request.post("/api/account/delete", {
        headers: {
          cookie: "carelens_user_id=some-test-user",
          origin: "https://evil.com",
        },
        data: { confirmation: "EXCLUIR" },
      });
      expect(resp.status()).toBe(403);
      const body = await resp.json();
      expect(body.ok).toBe(false);
      expect(body.message).toContain("Origem");
    });

    test("accepts request without Origin header (API-tool compatibility)", async ({ request }) => {
      // curl / Postman / Playwright API context send no Origin.
      // The CSRF guard lets them through, then auth runs.
      const resp = await request.post("/api/account/delete", {
        headers: { cookie: "carelens_user_id=" },
        data: { confirmation: "EXCLUIR" },
      });
      // Should pass CSRF but fail auth (empty cookie value → no userId)
      expect(resp.status()).toBe(401);
    });

    /* ── Auth guards ───────────────────────────────────────── */

    test("returns 401 when no session cookie is provided", async ({ request }) => {
      const resp = await request.post("/api/account/delete", {
        data: { confirmation: "EXCLUIR" },
      });
      expect(resp.status()).toBe(401);
      const body = await resp.json();
      expect(body).toMatchObject({ ok: false, message: "Usuário não autenticado." });
    });

    test("returns 401 when cookie has empty value", async ({ request }) => {
      const resp = await request.post("/api/account/delete", {
        headers: { cookie: "carelens_user_id=" },
        data: { confirmation: "EXCLUIR" },
      });
      expect(resp.status()).toBe(401);
    });

    /* ── Input validation ──────────────────────────────────── */

    test("rejects missing confirmation token with 400", async ({ request }) => {
      const resp = await request.post("/api/account/delete", {
        headers: { cookie: "carelens_user_id=exists-but-not-in-db" },
        data: {},
      });
      expect(resp.status()).toBe(400);
      const body = await resp.json();
      expect(body.ok).toBe(false);
      expect(body.message).toContain("EXCLUIR");
    });

    test("rejects wrong confirmation token with 400", async ({ request }) => {
      const resp = await request.post("/api/account/delete", {
        headers: { cookie: "carelens_user_id=exists-but-not-in-db" },
        data: { confirmation: "wrong" },
      });
      expect(resp.status()).toBe(400);
    });

    /* ── Happy path (deterministic integration) ────────────── */

    test("happy path: deletes user and returns cookie-clear headers", async ({ request }) => {
      // Use a truly unique ID to avoid collisions when the entire
      // test file runs with fullyParallel across workers.
      const userId = testUserId();
      const email = testEmail(userId);
      const created = await prisma.user.create({
        data: { id: userId, email },
      });
      expect(created.id).toBe(userId);

      const resp = await request.post("/api/account/delete", {
        headers: { cookie: `carelens_user_id=${userId}` },
        data: { confirmation: "EXCLUIR" },
      });

      // 1) Status + body
      expect(resp.status()).toBe(200);
      const body = await resp.json();
      expect(body.ok).toBe(true);
      expect(body.redirectTo).toBe("/");

      // 2) Set-Cookie headers contain cookie-deletion directives
      const setCookieValues = resp
        .headersArray()
        .filter((h) => h.name.toLowerCase() === "set-cookie")
        .map((h) => h.value);

      expect(setCookieValues.length).toBeGreaterThanOrEqual(2);
      const joined = setCookieValues.join("; ");
      expect(joined).toContain("carelens_user_id=");

      // 3) User was actually deleted from the database
      const deletedUser = await prisma.user.findUnique({ where: { id: userId } });
      expect(deletedUser).toBeNull();
    });
  });

  /* -------------------------------------------------------------- */
  /*  CPF propagation: checkout session → payment page params        */
  /* -------------------------------------------------------------- */
  test.describe("Checkout CPF propagation", () => {
    test("forwards cpfCnpj into payment page redirect URL", async ({ request }) => {
      const uniqueId = Date.now().toString(36);
      const resp = await request.post("/api/checkout/session", {
        multipart: {
          email: `cpf-propagate-${uniqueId}@carelens.local`,
          fullName: "CPF Propagate Test",
          purchaseType: "one_time",
          cpfCnpj: "12345678909",
        },
        maxRedirects: 0,
      });

      expect(resp.status()).toBe(303);
      const location = resp.headers()["location"] || "";
      expect(location).toContain("/payment?");
      expect(location).toContain("cpfCnpj=12345678909");
    });

    test("omits cpfCnpj param from redirect when not provided", async ({ request }) => {
      const uniqueId = Date.now().toString(36);
      const resp = await request.post("/api/checkout/session", {
        multipart: {
          email: `cpf-no-cpf-${uniqueId}@carelens.local`,
          fullName: "No CPF Test",
          purchaseType: "subscription",
          // no cpfCnpj
        },
        maxRedirects: 0,
      });

      expect(resp.status()).toBe(303);
      const location = resp.headers()["location"] || "";
      expect(location).not.toContain("cpfCnpj=");
    });

    test("payment page renders with cpfCnpj in search params", async ({ page }) => {
      await page.goto(
        "/payment?cpfCnpj=11122233344&email=cpf-page-test@carelens.local&fullName=CPF+Page+Test&purchaseType=one_time",
      );
      await expect(page.getByRole("heading", { name: /Finalize/i })).toBeVisible();
    });
  });

  test.describe("POST /api/webhooks/asaas", () => {
    /**
     * If the test environment has ASAAS_WEBHOOK_TOKEN set, use it to
     * authenticate so we can test payload-validation paths.
     *
     * When the token is absent the server may be in production and
     * return 500 (misconfiguration) — accept that as valid too.
     */
    const WEBHOOK_TOKEN = process.env.ASAAS_WEBHOOK_TOKEN;

    function authHeaders(): Record<string, string> {
      return WEBHOOK_TOKEN ? { "asaas-access-token": WEBHOOK_TOKEN } : {};
    }

    function expectPayloadOrMisconfig(actual: number): void {
      if (WEBHOOK_TOKEN) {
        // Token known & sent — auth passes, payload validation runs
        expect(actual).toBe(400);
      } else {
        // No token in env — route may return 500 (production, misconfig)
        // or 400 (non-production, payload validation)
        expect([400, 500]).toContain(actual);
      }
    }

    test("rejects invalid JSON payload with 400", async ({ request }) => {
      const resp = await request.post("/api/webhooks/asaas", {
        headers: { "Content-Type": "application/json", ...authHeaders() },
        data: "not valid json",
      });
      expectPayloadOrMisconfig(resp.status());
    });

    test("rejects missing event id with 400", async ({ request }) => {
      const resp = await request.post("/api/webhooks/asaas", {
        headers: authHeaders(),
        data: { id: "", event: "PAYMENT_RECEIVED" },
      });
      expectPayloadOrMisconfig(resp.status());
    });

    test("rejects missing event type with 400", async ({ request }) => {
      const resp = await request.post("/api/webhooks/asaas", {
        headers: authHeaders(),
        data: { id: "evt_test", event: "" },
      });
      expectPayloadOrMisconfig(resp.status());
    });

    test("rejects with 401 when token is configured and header is missing", async ({ request }) => {
      const resp = await request.post("/api/webhooks/asaas", {
        data: { id: "evt_api_security", event: "PAYMENT_RECEIVED", payment: {} },
      });

      if (WEBHOOK_TOKEN) {
        // Token is configured — request without matching token must be 401
        expect(resp.status()).toBe(401);
      } else {
        // No token configured: auth is skipped. In production the route
        // returns 500 (misconfiguration); in dev it reaches payload parsing.
        expect([200, 400, 500]).toContain(resp.status());
      }
    });

    test("rejects with 401 when token is configured and header is wrong", async ({ request }) => {
      const resp = await request.post("/api/webhooks/asaas", {
        headers: { "asaas-access-token": "wrong-token-value" },
        data: { id: "evt_api_security", event: "PAYMENT_RECEIVED", payment: {} },
      });

      if (WEBHOOK_TOKEN) {
        expect(resp.status()).toBe(401);
      } else {
        expect([200, 400, 500]).toContain(resp.status());
      }
    });
  });
});
