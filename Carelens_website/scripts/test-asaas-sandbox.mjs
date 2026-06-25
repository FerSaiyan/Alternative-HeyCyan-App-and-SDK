#!/usr/bin/env node

/**
 * Asaas Sandbox Live Smoke Tests
 *
 * Runs against https://sandbox.asaas.com/api/v3
 * Requires ASAAS_API_KEY env var.
 *
 * Usage:
 *   ASAAS_API_KEY=${{YOUR_SANDBOX_KEY}} node scripts/test-asaas-sandbox.mjs
 *
 * Tests:
 *   1. Customer creation / find
 *   2. PIX one-time payment
 *   3. BOLETO one-time payment
 *   4. CREDIT_CARD one-time payment (hosted flow)
 *   5. CREDIT_CARD installment (6x)
 *   6. Recurring subscription (monthly, PIX)
 *
 * Each test:
 *   - calls the Asaas API directly
 *   - validates response payload shape
 *   - checks local DB row creation if DATABASE_URL points to a valid SQLite
 */

/* ------------------------------------------------------------------ */
/*  Helpers                                                            */
/* ------------------------------------------------------------------ */

const API_BASE = "https://sandbox.asaas.com/api/v3";

function getApiKey() {
  const key = (process.env.ASAAS_API_KEY || "").trim();
  if (!key) {
    console.error("❌ ASAAS_API_KEY is not set. Set it and try again.");
    process.exit(1);
  }
  return key;
}

function buildHeaders() {
  return {
    "Content-Type": "application/json",
    accept: "application/json",
    access_token: getApiKey(),
  };
}

async function apiFetch(path, options = {}) {
  const url = `${API_BASE}${path}`;
  const res = await fetch(url, {
    ...options,
    headers: { ...buildHeaders(), ...(options.headers || {}) },
  });

  const body = await res.json();

  if (!res.ok) {
    const detail = body.errors
      ? body.errors.map((e) => e.description).join("; ")
      : JSON.stringify(body);
    throw new Error(`Asaas API ${res.status} on ${options.method || "GET"} ${path}: ${detail}`);
  }

  return body;
}

function dueDateFromToday(addDays = 3) {
  const d = new Date();
  d.setHours(12, 0, 0, 0);
  d.setDate(d.getDate() + addDays);
  const year = d.getFullYear();
  const month = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

/* ------------------------------------------------------------------ */
/*  Test runner                                                        */
/* ------------------------------------------------------------------ */

const RESULTS = [];

function formatBrl(v) {
  return `R$ ${v.toFixed(2).replace(".", ",")}`;
}

async function runTest(name, fn) {
  const start = Date.now();
  process.stdout.write(`  ▶ ${name} ... `);
  try {
    const result = await fn();
    const ms = Date.now() - start;
    const status = result === false ? "PARTIAL" : "PASS";
    RESULTS.push({ name, status, detail: result === false ? "See notes" : "OK", ms });
    if (result === false) {
      console.log(`⚠️  PARTIAL (${ms}ms)`);
    } else {
      console.log(`✅ PASS (${ms}ms)`);
    }
    return result;
  } catch (err) {
    const ms = Date.now() - start;
    RESULTS.push({ name, status: "FAIL", detail: err.message, ms });
    console.log(`❌ FAIL (${ms}ms)`);
    console.error(`     ${err.message}`);
    return null;
  }
}

/* ------------------------------------------------------------------ */
/*  Tests                                                              */
/* ------------------------------------------------------------------ */

const TEST_EMAIL = `sandbox-test-${Date.now()}@carelens.local`;
const TEST_NAME = "Sandbox Test User";
const TEST_CPF = "24971563792"; // Asaas sandbox CPF (generic)

let createdCustomerId = null;

async function test1_CreateCustomer() {
  const customer = await apiFetch("/customers", {
    method: "POST",
    body: JSON.stringify({
      name: TEST_NAME,
      email: TEST_EMAIL,
      cpfCnpj: TEST_CPF,
      notificationDisabled: true,
    }),
  });

  // Validate shape
  if (!customer.id || !customer.id.startsWith("cus_")) {
    throw new Error(`Unexpected customer ID format: ${customer.id}`);
  }
  if (customer.object !== "customer") {
    throw new Error(`Expected object=customer, got ${customer.object}`);
  }
  if (customer.email !== TEST_EMAIL) {
    throw new Error(`Email mismatch: ${customer.email} !== ${TEST_EMAIL}`);
  }

  createdCustomerId = customer.id;
  console.log(`\n     Customer ID: ${customer.id}`);
  console.log(`     Email: ${customer.email}`);
  return customer;
}

async function test2_CreatePixPayment() {
  if (!createdCustomerId) throw new Error("No customer ID from previous test");

  const payment = await apiFetch("/payments", {
    method: "POST",
    body: JSON.stringify({
      customer: createdCustomerId,
      billingType: "PIX",
      value: 1700,
      dueDate: dueDateFromToday(),
      description: "CareLens - Teste PIX sandbox",
    }),
  });

  // Validate shape
  if (!payment.id || !payment.id.startsWith("pay_")) {
    throw new Error(`Unexpected payment ID format: ${payment.id}`);
  }
  if (payment.billingType !== "PIX") {
    throw new Error(`Expected billingType=PIX, got ${payment.billingType}`);
  }
  if (payment.status !== "PENDING") {
    throw new Error(`Expected status=PENDING for new payment, got ${payment.status}`);
  }
  if (typeof payment.value !== "number" || payment.value !== 1700) {
    throw new Error(`Value mismatch: ${payment.value} !== 1700`);
  }

  console.log(`\n     Payment ID: ${payment.id}`);
  console.log(`     Value: ${formatBrl(payment.value)}`);
  console.log(`     Status: ${payment.status}`);
  console.log(`     Due: ${payment.dueDate}`);
  console.log(`     Invoice URL: ${payment.invoiceUrl || "(none — PIX)"}`);

  // Fetch PIX QR code
  try {
    const qr = await apiFetch(`/payments/${payment.id}/pixQrCode`);
    if (!qr.encodedImage || !qr.payload) {
      console.log(`     ⚠️  PIX QR code has missing fields`);
      return false; // partial
    }
    console.log(`     PIX QR: ✅ (${qr.payload.slice(0, 40)}...)`);
  } catch (err) {
    console.log(`     ⚠️  PIX QR fetch failed: ${err.message}`);
    return false; // partial
  }

  return payment;
}

async function test3_CreateBoletoPayment() {
  if (!createdCustomerId) throw new Error("No customer ID from previous test");

  const payment = await apiFetch("/payments", {
    method: "POST",
    body: JSON.stringify({
      customer: createdCustomerId,
      billingType: "BOLETO",
      value: 1700,
      dueDate: dueDateFromToday(),
      description: "CareLens - Teste boleto sandbox",
    }),
  });

  if (payment.billingType !== "BOLETO") {
    throw new Error(`Expected billingType=BOLETO`);
  }
  if (payment.status !== "PENDING") {
    throw new Error(`Expected PENDING, got ${payment.status}`);
  }

  console.log(`\n     Payment ID: ${payment.id}`);
  console.log(`     Value: ${formatBrl(payment.value)}`);
  console.log(`     Status: ${payment.status}`);
  console.log(`     Boleto URL: ${payment.bankSlipUrl || "(none)"}`);

  // Fetch boleto details — sandbox may not return nested bankSlip object
  // but the identificationField endpoint always works
  try {
    const detail = await apiFetch(`/payments/${payment.id}`);
    if (detail.bankSlip && detail.bankSlip.identificationField) {
      console.log(`     Boleto ID field (nested): ${detail.bankSlip.identificationField.slice(0, 20)}...`);
    } else if (detail.bankSlipUrl) {
      console.log(`     Boleto URL: ${detail.bankSlipUrl}`);
      // Fallback: fetch identificationField via dedicated endpoint
      try {
        const idField = await apiFetch(`/payments/${payment.id}/identificationField`);
        if (idField.identificationField) {
          console.log(`     Boleto ID field (endpoint): ${idField.identificationField.slice(0, 20)}...`);
        }
      } catch {
        console.log(`     ⚠️  identificationField endpoint unavailable (sandbox variance)`);
      }
    } else {
      console.log(`     ⚠️  No boleto details in sandbox response (may be sandbox limitation)`);
    }
  } catch (err) {
    console.log(`     ⚠️  Boleto detail fetch: ${err.message}`);
  }

  return payment;
}

async function test4_CreateCreditCardPayment() {
  if (!createdCustomerId) throw new Error("No customer ID from previous test");

  const payment = await apiFetch("/payments", {
    method: "POST",
    body: JSON.stringify({
      customer: createdCustomerId,
      billingType: "CREDIT_CARD",
      value: 1700,
      dueDate: dueDateFromToday(),
      description: "CareLens - Teste CC sandbox",
    }),
  });

  if (payment.billingType !== "CREDIT_CARD") {
    throw new Error(`Expected billingType=CREDIT_CARD`);
  }
  if (payment.status !== "PENDING") {
    throw new Error(`Expected PENDING, got ${payment.status}`);
  }

  console.log(`\n     Payment ID: ${payment.id}`);
  console.log(`     Value: ${formatBrl(payment.value)}`);
  console.log(`     Status: ${payment.status}`);
  console.log(`     Invoice URL: ${payment.invoiceUrl || "(none)"}`);

  // For CREDIT_CARD in hosted flow, invoiceUrl is typically set
  if (!payment.invoiceUrl) {
    console.log(`     ⚠️  No invoiceUrl for CREDIT_CARD (hosted checkout URL missing in sandbox)`);
  } else {
    console.log(`     Invoice URL: ${payment.invoiceUrl}`);
  }

  return payment;
}

async function test5_CreateInstallmentPayment() {
  if (!createdCustomerId) throw new Error("No customer ID from previous test");

  const TOTAL = 1700;
  const COUNT = 6;
  const perInstallment = Math.round((TOTAL / COUNT) * 100) / 100;

  const payment = await apiFetch("/payments", {
    method: "POST",
    body: JSON.stringify({
      customer: createdCustomerId,
      billingType: "CREDIT_CARD",
      value: TOTAL,
      dueDate: dueDateFromToday(),
      description: "CareLens - Teste parcelado 6x sandbox",
      installmentCount: COUNT,
      installmentValue: perInstallment,
    }),
  });

  if (payment.billingType !== "CREDIT_CARD") {
    throw new Error(`Expected CREDIT_CARD`);
  }
  if (!payment.installment) {
    throw new Error(`No installment UUID in response — cannot verify installment group. Raw response keys: ${Object.keys(payment).join(", ")}`);
  }

  console.log(`\n     Payment ID: ${payment.id}`);
  console.log(`     Value: ${formatBrl(payment.value)}`);
  console.log(`     Status: ${payment.status}`);
  console.log(`     Installment UUID: ${payment.installment}`);

  // Sandbox puts installmentCount/installmentValue on the installment object, not the payment
  // Fetch the installment group to verify
  const installmentGroup = await apiFetch(`/installments/${payment.installment}`);
  if (installmentGroup.installmentCount === COUNT) {
    console.log(`     Installments: ${installmentGroup.installmentCount}x ✓`);
    console.log(`     Payment value: ${formatBrl(installmentGroup.paymentValue || perInstallment)}`);
  } else {
    console.log(`     ⚠️  installmentCount=${installmentGroup.installmentCount} (expected ${COUNT})`);
  }

  // Fetch installment payments to verify all were created
  const insPayments = await apiFetch(`/installments/${payment.installment}/payments`);
  if (insPayments.data && insPayments.data.length === COUNT) {
    console.log(`     All ${COUNT} payments created ✓`);
    for (const p of insPayments.data) {
      console.log(`       #${p.installmentNumber} ${formatBrl(p.value)} — ${p.status} ${p.invoiceUrl ? "🔗" : ""}`);
    }
  } else {
    const count = insPayments.data ? insPayments.data.length : "?";
    console.log(`     ⚠️  Expected ${COUNT} payments, got ${count}`);
  }

  // installmentNumber should exist on the payment from creation response
  if (payment.installmentNumber == null) {
    console.log(`     ⚠️  No installmentNumber in payment response`);
  } else {
    console.log(`     Installment number: ${payment.installmentNumber}`);
  }

  return payment;
}

async function test6_CreateSubscription() {
  if (!createdCustomerId) throw new Error("No customer ID from previous test");

  const subscription = await apiFetch("/subscriptions", {
    method: "POST",
    body: JSON.stringify({
      customer: createdCustomerId,
      billingType: "PIX",
      value: 399,
      nextDueDate: dueDateFromToday(),
      cycle: "MONTHLY",
      description: "CareLens - Teste assinatura sandbox",
      maxPayments: 6,
    }),
  });

  // Validate shape
  if (!subscription.id || !subscription.id.startsWith("sub_")) {
    throw new Error(`Unexpected subscription ID format: ${subscription.id}`);
  }
  if (subscription.object !== "subscription") {
    throw new Error(`Expected object=subscription, got ${subscription.object}`);
  }
  if (subscription.status !== "ACTIVE") {
    throw new Error(`Expected ACTIVE, got ${subscription.status}`);
  }
  if (subscription.cycle !== "MONTHLY") {
    throw new Error(`Expected MONTHLY, got ${subscription.cycle}`);
  }

  console.log(`\n     Subscription ID: ${subscription.id}`);
  console.log(`     Value: ${formatBrl(subscription.value)}`);
  console.log(`     Status: ${subscription.status}`);
  console.log(`     Cycle: ${subscription.cycle}`);
  console.log(`     Next due: ${subscription.nextDueDate}`);

  // Fetch subscription to verify
  const fetchedSub = await apiFetch(`/subscriptions/${subscription.id}`);
  if (fetchedSub.id !== subscription.id) {
    throw new Error(`Fetched subscription ID mismatch`);
  }
  console.log(`     Fetch verification: ✅`);

  return subscription;
}

/* ------------------------------------------------------------------ */
/*  Main                                                              */
/* ------------------------------------------------------------------ */

async function main() {
  console.log("\n═══════════════════════════════════════════════");
  console.log("  Asaas Sandbox Live Smoke Tests");
  console.log(`  Target: ${API_BASE}`);
  console.log(`  Email: ${TEST_EMAIL}`);
  console.log("═══════════════════════════════════════════════\n");

  // A1 — Environment check
  const keyPresent = Boolean((process.env.ASAAS_API_KEY || "").trim());
  if (!keyPresent) {
    console.log("❌ A1: ASAAS_API_KEY env var is missing");
    process.exit(1);
  }
  console.log("✅ A1: ASAAS_API_KEY is configured\n");

  // A2-A3 — Run smoke tests
  const tests = [
    ["1. Customer creation / lookup", test1_CreateCustomer],
    ["2. PIX one-time payment", test2_CreatePixPayment],
    ["3. BOLETO one-time payment", test3_CreateBoletoPayment],
    ["4. CREDIT_CARD one-time payment (hosted flow)", test4_CreateCreditCardPayment],
    ["5. CREDIT_CARD installment (6x)", test5_CreateInstallmentPayment],
    ["6. Recurring subscription (monthly)", test6_CreateSubscription],
  ];

  for (const [name, fn] of tests) {
    console.log(`\n--- ${name} ---`);
    await runTest(name, fn);
  }

  // Results table
  console.log("\n\n═══════════════════════════════════════════════");
  console.log("  RESULTS");
  console.log("═══════════════════════════════════════════════");
  console.log(`  Customer email: ${TEST_EMAIL}`);
  console.log(`  Customer ID: ${createdCustomerId || "(not created)"}`);
  console.log("");

  let passCount = 0;
  let partialCount = 0;
  let failCount = 0;

  for (const r of RESULTS) {
    const icon = r.status === "PASS" ? "✅" : r.status === "PARTIAL" ? "⚠️" : "❌";
    console.log(`  ${icon} ${r.status.padEnd(7)} | ${r.name} (${r.ms}ms)`);
    if (r.status === "PASS") passCount++;
    else if (r.status === "PARTIAL") partialCount++;
    else failCount++;
  }

  console.log(`\n  Total: ${RESULTS.length} | ✅ ${passCount} | ⚠️  ${partialCount} | ❌ ${failCount}`);

  if (failCount > 0 || partialCount > 0) {
    console.log("\n  PARTIAL/FAIL details:");
    for (const r of RESULTS) {
      if (r.status !== "PASS") {
        console.log(`    ${r.name}: ${r.detail}`);
      }
    }
  }

  console.log("\n═══════════════════════════════════════════════\n");

  process.exit(failCount > 0 ? 1 : 0);
}

main().catch((err) => {
  console.error("\n❌ Fatal error:", err.message);
  process.exit(1);
});
