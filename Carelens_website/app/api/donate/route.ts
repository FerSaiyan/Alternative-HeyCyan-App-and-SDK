import { NextResponse } from "next/server";
import {
  ensureRelayUser,
  saveRelayUser,
  type RelayUser,
} from "@/lib/relay-kv";
import {
  isAsaasConfigured,
  createCustomer,
  createSubscription,
  listPaymentsBySubscription,
} from "@/lib/asaas";
import { usdToBrl, getUsdToBrlRate } from "@/lib/exchange-rate";
import { logInfo, logError } from "@/lib/logger";

const DONATION_AMOUNTS = [3, 5, 10] as const;
const DONATION_LABELS: Record<number, string> = {
  3: "Coffee ($3)",
  5: "Lunch ($5)",
  10: "Dinner ($10)",
};

function appendQueryParams(baseUrl: string, params: Record<string, string>): string {
  const url = new URL(baseUrl);
  for (const [k, v] of Object.entries(params)) {
    url.searchParams.set(k, v);
  }
  return url.toString();
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/\"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function donationPageHtml(params: {
  invoiceUrl: string;
  amount: number;
  amountBrl: number;
  statusUrl: string;
  successUrl: string;
}): string {
  const invoiceUrl = escapeHtml(params.invoiceUrl);
  const statusUrlJson = JSON.stringify(params.statusUrl);
  const successUrlJson = JSON.stringify(params.successUrl);
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>CyanBridge - Donation</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#0a0a0a;color:#e0e0e0;display:flex;justify-content:center;align-items:center;min-height:100vh;padding:20px}
.card{background:#1a1a2e;border-radius:16px;padding:32px;max-width:400px;width:100%;text-align:center;box-shadow:0 8px 32px rgba(0,0,0,0.4)}
h1{font-size:24px;margin-bottom:8px;color:#fff}.subtitle{color:#888;font-size:14px;margin-bottom:24px}
.plan-box{background:#16213e;border-radius:12px;padding:16px;margin-bottom:24px}.plan-name{font-size:18px;font-weight:600;color:#4fc3f7}
.plan-price{font-size:14px;color:#aaa;margin-top:4px}.btn{display:block;width:100%;padding:16px;border:none;border-radius:12px;font-size:16px;font-weight:600;cursor:pointer;text-decoration:none;margin-bottom:12px;transition:opacity 0.2s}
.btn-primary{background:#4fc3f7;color:#000}.btn-secondary{background:transparent;color:#888;border:1px solid #333}.btn-disabled{opacity:0.6;pointer-events:none}
.status-box{display:none;background:#101827;border:1px solid #23314f;border-radius:12px;padding:14px;margin-bottom:18px;text-align:left}.status-box strong{display:block;color:#fff;margin-bottom:6px}.status-box p{font-size:13px;color:#b5c0d0;line-height:1.5}
</style>
</head>
<body>
<div class="card">
<h1>Support CyanBridge</h1>
<p class="subtitle">One-time donation via credit card</p>
<div class="plan-box"><div class="plan-name">${DONATION_LABELS[params.amount] || `$${params.amount}`}</div><div class="plan-price">$${params.amount}.00 &middot; R$ ${params.amountBrl.toFixed(2)}</div></div>
<div id="status-box" class="status-box"><strong id="status-title">Waiting for payment confirmation</strong><p id="status-message">After filling the secure Asaas card form, return here and tap the confirmation button below.</p></div>
<a id="open-checkout" class="btn btn-primary" href="${invoiceUrl}" target="_blank" rel="noopener noreferrer">Open Secure Card Form</a>
<button id="check-status" class="btn btn-secondary" type="button">I Completed Payment</button>
</div>
<script>
const statusUrl = ${statusUrlJson};
const successUrl = ${successUrlJson};
const openCheckoutBtn = document.getElementById("open-checkout");
const checkStatusBtn = document.getElementById("check-status");
const statusBox = document.getElementById("status-box");
const statusTitle = document.getElementById("status-title");
const statusMessage = document.getElementById("status-message");
let pollTimer = null;
let checkoutStarted = false;
function showStatus(title, message) { statusBox.style.display = "block"; statusTitle.textContent = title; statusMessage.textContent = message; }
function setChecking(isChecking) { checkStatusBtn.disabled = isChecking; checkStatusBtn.classList.toggle("btn-disabled", isChecking); checkStatusBtn.textContent = isChecking ? "Checking..." : "I Completed Payment"; }
function startPolling() { if (pollTimer !== null) return; pollTimer = window.setInterval(() => { void checkStatus(false); }, 4000); }
async function checkStatus(showPendingMessage) {
  if (checkStatusBtn.disabled) return;
  setChecking(true);
  try {
    const response = await fetch(statusUrl, { method: "GET", headers: { Accept: "application/json" }, cache: "no-store" });
    const payload = await response.json();
    if (!response.ok) throw new Error(payload.message || "Unable to verify payment yet.");
    if (payload.confirmed) { showStatus("Payment confirmed", "Thank you for your support!"); window.location.assign(successUrl); return; }
    showStatus("Waiting for confirmation", payload.message || "Complete the card form in Asaas, then try again.");
  } catch (error) {
    showStatus("Verification problem", error instanceof Error ? error.message : "Unable to verify payment yet.");
  } finally { setChecking(false); }
}
openCheckoutBtn.addEventListener("click", () => { checkoutStarted = true; showStatus("Card form opened", "Complete the secure Asaas checkout, then return here. We will keep checking in the background."); startPolling(); });
checkStatusBtn.addEventListener("click", () => { checkoutStarted = true; startPolling(); void checkStatus(true); });
document.addEventListener("visibilitychange", () => { if (!document.hidden && checkoutStarted) { void checkStatus(false); } });
window.addEventListener("beforeunload", () => { if (pollTimer !== null) window.clearInterval(pollTimer); });
</script>
</body>
</html>`;
}

function isValidDonationAmount(value: number): value is (typeof DONATION_AMOUNTS)[number] {
  return DONATION_AMOUNTS.includes(value as (typeof DONATION_AMOUNTS)[number]);
}

function isRealEmail(email: string | null | undefined): boolean {
  const normalized = (email ?? "").trim().toLowerCase();
  if (!normalized) return false;
  if (normalized.startsWith("relay_")) return false;
  if (normalized.endsWith("@cyanbridge.placeholder")) return false;
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalized);
}

export async function GET(request: Request) {
  const { searchParams } = new URL(request.url);
  const returnUrl = searchParams.get("return_url") ?? "";
  const email = (searchParams.get("email") ?? "").trim().toLowerCase();
  const apiToken = (searchParams.get("api_token") ?? "").trim();
  const rawAmount = parseInt(searchParams.get("amount") ?? "0", 10);
  const amount = isValidDonationAmount(rawAmount) ? rawAmount : 5;

  if (!returnUrl) {
    return NextResponse.json({
      ok: true,
      message: "donate endpoint alive",
      amounts: [3, 5, 10],
      required_query: ["return_url", "email", "amount"],
    });
  }

  if (!isRealEmail(email)) {
    return NextResponse.json({ ok: false, message: "A valid email is required." }, { status: 400 });
  }

  const user = await ensureRelayUser(apiToken || undefined, email);

  if (!isAsaasConfigured()) {
    return NextResponse.json({ ok: false, message: "Payment provider not configured." }, { status: 500 });
  }

  try {
    const exchangeRate = await getUsdToBrlRate();
    const amountBrl = await usdToBrl(amount);

    let asaasCustomerId = user.asaasCustomerId;
    if (!asaasCustomerId) {
      const customer = await createCustomer({
        name: email,
        email,
        foreignCustomer: true,
        externalReference: user.id,
      });
      asaasCustomerId = customer.id;
      await saveRelayUser({
        ...user,
        email,
        asaasCustomerId,
        updatedAt: new Date().toISOString(),
      });
    }

    const nextDueDateStr = new Date().toISOString().slice(0, 10);
    const subscription = await createSubscription({
      customerId: asaasCustomerId,
      billingType: "CREDIT_CARD",
      value: amountBrl,
      nextDueDate: nextDueDateStr,
      cycle: "MONTHLY",
      maxPayments: 1,
      description: `CyanBridge Donation - $${amount} (${DONATION_LABELS[amount] || `$${amount}`})`,
      externalReference: `donation_${user.id}`,
    });

    logInfo("donation_subscription_created", "Donation subscription created", {
      relayUserId: user.id,
      asaasSubscriptionId: subscription.id,
      amount,
      amountBrl,
      exchangeRate,
    });

    const subRecord = subscription as unknown as Record<string, unknown>;
    let invoiceUrl = subRecord.invoiceUrl as string | undefined;
    if (!invoiceUrl) {
      const payments = await listPaymentsBySubscription(subscription.id, 5);
      invoiceUrl = payments.find((p) => p.invoiceUrl)?.invoiceUrl;
    }

    const successUrl = appendQueryParams(new URL("/donate/status", request.url).toString(), {
      subscription_id: subscription.id,
      return_url: returnUrl,
      api_token: user.apiToken,
      amount: String(amount),
    });

    const statusUrl = appendQueryParams(new URL("/donate/status", request.url).toString(), {
      subscription_id: subscription.id,
      api_token: user.apiToken,
      amount: String(amount),
    });

    if (invoiceUrl) {
      const html = donationPageHtml({
        invoiceUrl,
        amount,
        amountBrl,
        statusUrl,
        successUrl,
      });
      return new NextResponse(html, {
        status: 200,
        headers: { "Content-Type": "text/html; charset=utf-8" },
      });
    }

    return NextResponse.json(
      { ok: false, message: "Asaas did not return a hosted checkout link." },
      { status: 500 },
    );
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unknown error";
    logError("donation_creation_failed", "Failed to create donation", {
      email,
      amount,
      error: message,
    });
    return NextResponse.json({ ok: false, message }, { status: 500 });
  }
}

export async function POST(request: Request) {
  let email = "";
  let amount = 5;
  let returnUrl = "";
  let apiToken = "";

  const contentType = request.headers.get("content-type") ?? "";
  if (contentType.includes("application/json")) {
    const body = (await request.json()) as Record<string, unknown>;
    email = String(body.email ?? "").trim().toLowerCase();
    amount = isValidDonationAmount(Number(body.amount)) ? Number(body.amount) : 5;
    returnUrl = String(body.return_url ?? "").trim();
    apiToken = String(body.api_token ?? "").trim();
  } else {
    const formData = await request.formData();
    email = String(formData.get("email") ?? "").trim().toLowerCase();
    amount = isValidDonationAmount(Number(formData.get("amount"))) ? Number(formData.get("amount")) : 5;
    returnUrl = String(formData.get("return_url") ?? "").trim();
    apiToken = String(formData.get("api_token") ?? "").trim();
  }

  if (!isRealEmail(email)) {
    return NextResponse.json({ ok: false, message: "A valid email is required." }, { status: 400 });
  }

  const user = await ensureRelayUser(apiToken || undefined, email);

  if (!isAsaasConfigured()) {
    return NextResponse.json({ ok: false, message: "Payment provider not configured." }, { status: 500 });
  }

  try {
    const exchangeRate = await getUsdToBrlRate();
    const amountBrl = await usdToBrl(amount);

    let asaasCustomerId = user.asaasCustomerId;
    if (!asaasCustomerId) {
      const customer = await createCustomer({
        name: email,
        email,
        foreignCustomer: true,
        externalReference: user.id,
      });
      asaasCustomerId = customer.id;
      await saveRelayUser({
        ...user,
        email,
        asaasCustomerId,
        updatedAt: new Date().toISOString(),
      });
    }

    const nextDueDateStr = new Date().toISOString().slice(0, 10);
    const subscription = await createSubscription({
      customerId: asaasCustomerId,
      billingType: "CREDIT_CARD",
      value: amountBrl,
      nextDueDate: nextDueDateStr,
      cycle: "MONTHLY",
      maxPayments: 1,
      description: `CyanBridge Donation - $${amount} (${DONATION_LABELS[amount] || `$${amount}`})`,
      externalReference: `donation_${user.id}`,
    });

    logInfo("donation_subscription_created", "Donation subscription created (POST)", {
      relayUserId: user.id,
      asaasSubscriptionId: subscription.id,
      amount,
      amountBrl,
      exchangeRate,
    });

    const subRecord = subscription as unknown as Record<string, unknown>;
    let invoiceUrl = subRecord.invoiceUrl as string | undefined;
    if (!invoiceUrl) {
      const payments = await listPaymentsBySubscription(subscription.id, 5);
      invoiceUrl = payments.find((p) => p.invoiceUrl)?.invoiceUrl;
    }

    const statusUrl = appendQueryParams(new URL("/donate/status", request.url).toString(), {
      subscription_id: subscription.id,
      api_token: user.apiToken,
      amount: String(amount),
    });

    const successUrl = appendQueryParams(new URL("/donate/status", request.url).toString(), {
      subscription_id: subscription.id,
      return_url: returnUrl,
      api_token: user.apiToken,
      amount: String(amount),
    });

    return NextResponse.json({
      ok: true,
      invoice_url: invoiceUrl || "",
      status_url: statusUrl,
      success_url: successUrl,
      subscription_id: subscription.id,
      amount,
      amount_brl: amountBrl,
      api_token: user.apiToken,
      email,
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unknown error";
    logError("donation_creation_failed", "Failed to create donation (POST)", {
      email,
      amount,
      error: message,
    });
    return NextResponse.json({ ok: false, message }, { status: 500 });
  }
}
