import { test, expect, Page } from "@playwright/test";

// ---------------------------------------------------------------------------
// Unique identifiers for this run
// ---------------------------------------------------------------------------
const ID = Date.now().toString(36);
const ADMIN_EMAIL = "admin@carelens.com.br";
const ADMIN_PASSWORD = "#Fertroll10";

// Shared credentials (mutated as tests change them sequentially)
const state = {
  email: `dr.teste.${ID}@carelens.com.br`,
  username: `dr.teste.${ID}`,
  password: "#TestePlaywright123",
  fullName: `Dr Teste Playwright ${ID}`,
  specialty: "Clínico Geral",
  newEmail: `dr.teste.novo.${ID}@carelens.com.br`,
  newPassword: "#NovaSenha456",
  newUsername: `dr.teste.updated.${ID}`,
  updatedName: `Dr Teste Atualizado ${ID}`,
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Navigate to /signin?mode=signin, fill credentials, submit. */
async function signIn(page: Page, identifier: string, password: string) {
  await page.goto("/signin?mode=signin");
  await page.waitForSelector("#signin-identifier", { state: "visible" });
  await page.fill("#signin-identifier", identifier);
  await page.fill("#signin-password", password);
  await page.getByRole("button", { name: "Entrar" }).click();
}

/** Navigate to /logout and click the confirm button. */
async function signOut(page: Page) {
  await page.goto("/logout");
  await page.waitForSelector('button[type="submit"]', { state: "visible" });
  await page.getByRole("button", { name: "Confirmar saída" }).click();
}

/** Log in as admin and go to /admin/users. */
async function adminLogin(page: Page) {
  await signIn(page, ADMIN_EMAIL, ADMIN_PASSWORD);
  // Admin → redirected to /admin
  await page.waitForURL(/\/admin/);
  await page.goto("/admin/users");
  await page.waitForSelector('form[action="/api/admin/doctors"]', { state: "visible" });
}

/** Helper: compute grid child index for availability scheduler.
 *
 * The grid is `grid-cols-8`:
 *   row 0 (header)  : <div/>, <div>Dom</div>, <div>Seg</div> … <div>Sáb</div>
 *   row 1 (08:00)   : <div>08:00</div>, button (Dom), button (Seg) … button (Sáb)
 *   row 2 (08:30)   : <div>08:30</div>, button (Dom), button (Seg) … button (Sáb)
 *   …
 *   24 time rows (08:00 → 19:30).
 */
function slotCellIndex(day: number, time: string): number {
  const TIME_SLOTS = (() => {
    const sl: string[] = [];
    for (let h = 8; h < 20; h++) {
      sl.push(`${String(h).padStart(2, "0")}:00`);
      sl.push(`${String(h).padStart(2, "0")}:30`);
    }
    return sl;
  })();
  const timeIdx = TIME_SLOTS.indexOf(time);
  if (timeIdx === -1) throw new Error(`Unknown time slot: ${time}`);
  // 8 header elements + timeIdx * 8 elements + 1 (skip time label) + day
  return 8 + timeIdx * 8 + 1 + day;
}

// ---------------------------------------------------------------------------
// Tests – serial mode because every step depends on DB state from prior steps
// ---------------------------------------------------------------------------
test.describe.configure({ mode: "serial" });
test.describe("Doctor dashboard E2E", () => {
  // ── Step 1: Create a test doctor via admin API ──────────────────────────
  test("Step 1 — Admin creates a test doctor", async ({ page }) => {
    test.setTimeout(60_000);
    await adminLogin(page);

    // Fill the "Criar conta de médico" form
    await page.fill('form[action="/api/admin/doctors"] input[name="fullName"]', state.fullName);
    await page.fill('form[action="/api/admin/doctors"] input[name="email"]', state.email);
    await page.fill('form[action="/api/admin/doctors"] input[name="username"]', state.username);
    await page.fill('form[action="/api/admin/doctors"] input[name="password"]', state.password);
    await page.fill('form[action="/api/admin/doctors"] input[name="specialty"]', state.specialty);

    // Submit
    await page.locator('form[action="/api/admin/doctors"] button[type="submit"]').click();

    // Verify redirect back with success param
    await page.waitForURL(/\/admin\/users\?created=1/);
    await expect(page.locator("text=Médico criado com sucesso.")).toBeVisible();

    // Verify the doctor appears in the user list
    await expect(page.locator(`td:has-text("${state.email}")`).first()).toBeVisible();
  });

  // ── Step 2: Log in as the test doctor ──────────────────────────────────
  test("Step 2 — Doctor signs in and sees dashboard", async ({ page }) => {
    test.setTimeout(30_000);
    await signIn(page, state.username, state.password);
    await page.waitForURL(/\/doctor/);

    await expect(page.getByText("Dashboard do médico parceiro")).toBeVisible();
    await expect(page.getByText(state.email)).toBeVisible();
    await expect(page.getByText("Visão geral")).toBeVisible();
  });

  // ── Step 3: Test changing email ────────────────────────────────────────
  test("Step 3 — Doctor changes email", async ({ page }) => {
    test.setTimeout(30_000);
    // Sign in with original credentials
    await signIn(page, state.username, state.password);
    await page.waitForURL(/\/doctor/);

    // Navigate to settings tab
    await page.goto("/doctor?tab=settings");
    await page.waitForSelector('form[action="/api/doctor/settings/email"]', { state: "visible" });

    // Fill the "Alterar e-mail" form
    await page.fill('form[action="/api/doctor/settings/email"] input[name="newEmail"]', state.newEmail);
    await page.fill('form[action="/api/doctor/settings/email"] input[name="password"]', state.password);

    // Submit
    await page.locator('form[action="/api/doctor/settings/email"] button[type="submit"]').click();

    // Verify success banner
    await page.waitForURL(/\/doctor\?tab=settings&updated=email/);
    await expect(page.locator("text=E-mail alterado com sucesso.")).toBeVisible();

    // Log out
    await signOut(page);
    await page.waitForURL(/\/signin/);

    // Log in with new email and old password
    await signIn(page, state.newEmail, state.password);
    await page.waitForURL(/\/doctor/);
    await expect(page.getByText("Dashboard do médico parceiro")).toBeVisible();

    // Update state for downstream tests
    state.email = state.newEmail;
  });

  // ── Step 4: Test changing password ─────────────────────────────────────
  test("Step 4 — Doctor changes password", async ({ page }) => {
    test.setTimeout(30_000);
    // Sign in with current email and old password
    await signIn(page, state.email, state.password);
    await page.waitForURL(/\/doctor/);

    await page.goto("/doctor?tab=settings");
    await page.waitForSelector('form[action="/api/doctor/settings/password"]', { state: "visible" });

    // Fill the "Alterar senha" form
    await page.fill('form[action="/api/doctor/settings/password"] input[name="currentPassword"]', state.password);
    await page.fill('form[action="/api/doctor/settings/password"] input[name="newPassword"]', state.newPassword);

    // Submit
    await page.locator('form[action="/api/doctor/settings/password"] button[type="submit"]').click();

    // Verify success banner
    await page.waitForURL(/\/doctor\?tab=settings&updated=password/);
    await expect(page.locator("text=Senha alterada com sucesso.")).toBeVisible();

    // Log out
    await signOut(page);
    await page.waitForURL(/\/signin/);

    // Log in with email and new password
    await signIn(page, state.email, state.newPassword);
    await page.waitForURL(/\/doctor/);
    await expect(page.getByText("Dashboard do médico parceiro")).toBeVisible();

    // Update state
    state.password = state.newPassword;
  });

  // ── Step 5: Test changing username ─────────────────────────────────────
  test("Step 5 — Doctor changes username", async ({ page }) => {
    test.setTimeout(30_000);
    // Sign in with email and current password
    await signIn(page, state.email, state.password);
    await page.waitForURL(/\/doctor/);

    await page.goto("/doctor?tab=settings");
    await page.waitForSelector('form[action="/api/doctor/settings/username"]', { state: "visible" });

    // Fill the "Alterar nome de usuário" form
    await page.fill('form[action="/api/doctor/settings/username"] input[name="newUsername"]', state.newUsername);
    await page.fill('form[action="/api/doctor/settings/username"] input[name="password"]', state.password);

    // Submit
    await page.locator('form[action="/api/doctor/settings/username"] button[type="submit"]').click();

    // Verify success banner
    await page.waitForURL(/\/doctor\?tab=settings&updated=username/);
    await expect(page.locator("text=Nome de usuário alterado com sucesso.")).toBeVisible();

    // Log out
    await signOut(page);
    await page.waitForURL(/\/signin/);

    // Log in with new username
    await signIn(page, state.newUsername, state.password);
    await page.waitForURL(/\/doctor/);
    await expect(page.getByText("Dashboard do médico parceiro")).toBeVisible();

    // Update state
    state.username = state.newUsername;
  });

  // ── Step 6: Test availability scheduler ────────────────────────────────
  test("Step 6 — Availability scheduler saves and persists", async ({ page }) => {
    test.setTimeout(60_000);
    // Sign in with current credentials
    await signIn(page, state.username, state.password);
    await page.waitForURL(/\/doctor/);

    await page.goto("/doctor?tab=availability");
    await page.waitForSelector('form[action="/api/doctor/availability"]', { state: "visible" });

    const grid = page.locator('form[action="/api/doctor/availability"] .grid');

    // Verify the grid has 7 day columns (header labels)
    await expect(grid.locator("text=Seg")).toBeVisible();
    await expect(grid.locator("text=Ter")).toBeVisible();
    await expect(grid.locator("text=Qua")).toBeVisible();
    await expect(grid.locator("text=Qui")).toBeVisible();
    await expect(grid.locator("text=Sex")).toBeVisible();
    await expect(grid.locator("text=Sáb")).toBeVisible();

    // Click a few slots to toggle them: Mon 09:00, Mon 09:30, Mon 10:00; Wed 14:00, Wed 14:30
    const slotsToToggle: [number, string][] = [
      [1, "09:00"],
      [1, "09:30"],
      [1, "10:00"],
      [3, "14:00"],
      [3, "14:30"],
    ];

    for (const [day, time] of slotsToToggle) {
      const cell = grid.locator("> *").nth(slotCellIndex(day, time));
      await expect(cell).toBeVisible();
      // Verify it starts inactive
      await expect(cell).toHaveClass(/border-slate-200/);
      // Click to toggle active
      await cell.click();
      // Verify it becomes active
      await expect(cell).toHaveClass(/border-emerald-400/);
    }

    // Save
    await page.locator('form[action="/api/doctor/availability"] button:has-text("Salvar disponibilidade")').click();

    // Verify redirect with updated=1
    await page.waitForURL(/\/doctor\?tab=availability&updated=1/);
    // Success banner
    await expect(page.locator("text=Desfecho clínico atualizado com sucesso.")).toBeVisible();

    // Reload the page
    await page.reload();
    await page.waitForSelector('form[action="/api/doctor/availability"]', { state: "visible" });

    // Verify previously selected slots are still active (persisted in DB)
    for (const [day, time] of slotsToToggle) {
      const cell = grid.locator("> *").nth(slotCellIndex(day, time));
      await expect(cell).toHaveClass(/border-emerald-400/);
    }

  });

  // ── Step 7: Admin changes doctor's full name ───────────────────────────
  test("Step 7 — Admin edits doctor full name", async ({ page }) => {
    test.setTimeout(60_000);
    // Admin login
    await adminLogin(page);

    // Find the doctor row by email (use the current email)
    const doctorRow = page.locator(`td:has-text("${state.email}")`).first();
    await expect(doctorRow).toBeVisible();

    // Walk up to the parent <tr>, find the "Editar" button
    const editBtn = doctorRow.locator("xpath=../../..").locator('button:has-text("Editar")').first();
    await editBtn.click();

    // Wait for the inline edit form to appear (the visible one)
    const editForm = page.locator('form[action="/api/admin/users/edit"]').first();
    await expect(editForm).toBeVisible({ timeout: 5000 });

    // Change the full name
    const nameInput = editForm.locator('input[name="fullName"]');
    await nameInput.fill(state.updatedName);

    // Submit
    await editForm.locator('button[type="submit"]').click();

    // Verify success
    await page.waitForURL(/\/admin\/users\?updated=1/);
    await expect(page.locator("text=Perfil atualizado com sucesso.")).toBeVisible();

    // Log out, log in as doctor and verify dashboard loads
    await signOut(page);
    await signIn(page, state.email, state.password);
    await page.waitForURL(/\/doctor/);
    await expect(page.getByText("Dashboard do médico parceiro")).toBeVisible();
    // The doctor page shows the email in "Sessão médica: {email}"
    await expect(page.getByText(state.email)).toBeVisible();
  });

  // ── Cleanup: Delete the test doctor ────────────────────────────────────
  test("Cleanup — Remove test doctor from DB", async () => {
    const { execSync } = await import("child_process");
    try {
      execSync(
        `sqlite3 prisma/dev.db "DELETE FROM DoctorProfile WHERE userId IN (SELECT id FROM User WHERE email='${state.email}');"`,
        { cwd: process.cwd() },
      );
      execSync(
        `sqlite3 prisma/dev.db "DELETE FROM DoctorAvailability WHERE userId IN (SELECT id FROM User WHERE email='${state.email}');"`,
        { cwd: process.cwd() },
      );
      execSync(
        `sqlite3 prisma/dev.db "DELETE FROM User WHERE email='${state.email}';"`,
        { cwd: process.cwd() },
      );
      console.log(`Cleaned up test doctor: ${state.email}`);
    } catch (err) {
      console.warn("Cleanup may have partially failed:", err);
    }
  });
});
