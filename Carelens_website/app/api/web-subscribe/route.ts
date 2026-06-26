import { NextResponse } from "next/server";
import {
  ensureRelayUser,
  normalizePlan,
  isValidPlan,
  planExpiryMs,
  RELAY_PLANS,
  saveRelayUser,
  type RelayUser,
} from "@/lib/relay-kv";
import {
  isAsaasConfigured,
  createCustomer,
  createSubscription,
  listPaymentsBySubscription,
  tokenizeCreditCard,
  updateCustomer,
} from "@/lib/asaas";
import { usdToBrl, getUsdToBrlRate } from "@/lib/exchange-rate";
import { logInfo, logError } from "@/lib/logger";

function appendQueryParams(baseUrl: string, params: Record<string, string>): string {
  const url = new URL(baseUrl);
  for (const [k, v] of Object.entries(params)) {
    url.searchParams.set(k, v);
  }
  return url.toString();
}

function isSubscriptionActive(subStatus: string, expiresAtMs: number): boolean {
  if (subStatus === "active" || subStatus === "trial") {
    return expiresAtMs <= 0 || expiresAtMs > Date.now();
  }
  return false;
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/\"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function isRealCustomerEmail(email: string | null | undefined): boolean {
  const normalized = (email ?? "").trim().toLowerCase();
  if (!normalized) return false;
  if (normalized.startsWith("relay_")) return false;
  if (normalized.endsWith("@cyanbridge.placeholder")) return false;
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalized);
}

function normalizeDigits(value: string): string {
  return value.replace(/\D+/g, "");
}

function normalizeCardNumber(value: string): string {
  return normalizeDigits(value);
}

function normalizeMonth(value: string): string {
  const digits = normalizeDigits(value).slice(0, 2);
  return digits.padStart(2, "0");
}

function normalizeYear(value: string): string {
  const digits = normalizeDigits(value);
  if (digits.length >= 4) return digits.slice(-4);
  if (digits.length === 2) return `20${digits}`;
  return digits;
}

function getClientIp(request: Request): string {
  const forwarded = request.headers.get("x-forwarded-for") ?? "";
  const firstForwarded = forwarded.split(",")[0]?.trim();
  const candidate =
    firstForwarded ||
    request.headers.get("x-real-ip")?.trim() ||
    request.headers.get("x-vercel-forwarded-for")?.trim() ||
    "";

  return candidate || "127.0.0.1";
}

type DirectCheckoutState = {
  holderName: string;
  email: string;
};

function emptyDirectState(email: string): DirectCheckoutState {
  return {
    holderName: "",
    email,
  };
}

function legacyCheckoutHtml(params: {
  invoiceUrl: string;
  planLabel: string;
  priceUsd: number;
  priceBrl: number;
  statusUrl: string;
  successUrl: string;
}): string {
  const planLabel = escapeHtml(params.planLabel);
  const invoiceUrl = escapeHtml(params.invoiceUrl);
  const statusUrlJson = JSON.stringify(params.statusUrl);
  const successUrlJson = JSON.stringify(params.successUrl);
  const supportNote = escapeHtml("Hi, thanks for your interest in the Pro Sub. I work on this project besides my PhD studies and your comments and support motivate me. I havent been able to setup Stripe yet because of their special requirements for Brazilian businesses, so for now i am using a payment processor called Asaas and the checkout page is sadly in portuguese, so please use Google Translate. I know this is a bummer and i am actively looking for a better alternative. Thanks again for your support!");
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>CyanBridge Pro - Legacy Checkout</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#0a0a0a;color:#e0e0e0;display:flex;justify-content:center;align-items:center;min-height:100vh;padding:20px}
.card{background:#1a1a2e;border-radius:16px;padding:32px;max-width:400px;width:100%;text-align:center;box-shadow:0 8px 32px rgba(0,0,0,0.4)}
h1{font-size:24px;margin-bottom:8px;color:#fff}.subtitle{color:#888;font-size:14px;margin-bottom:24px}
.plan-box{background:#16213e;border-radius:12px;padding:16px;margin-bottom:24px}.plan-name{font-size:18px;font-weight:600;color:#4fc3f7}
.plan-price{font-size:14px;color:#aaa;margin-top:4px}.btn{display:block;width:100%;padding:16px;border:none;border-radius:12px;font-size:16px;font-weight:600;cursor:pointer;text-decoration:none;margin-bottom:12px;transition:opacity 0.2s}
.btn-primary{background:#4fc3f7;color:#000}.btn-secondary{background:transparent;color:#888;border:1px solid #333}.btn-disabled{opacity:0.6;pointer-events:none}
.status-box{display:none;background:#101827;border:1px solid #23314f;border-radius:12px;padding:14px;margin-bottom:18px;text-align:left}.status-box strong{display:block;color:#fff;margin-bottom:6px}.status-box p{font-size:13px;color:#b5c0d0;line-height:1.5}
.note{background:#101827;border:1px solid #2d3748;border-radius:12px;padding:14px;margin-bottom:18px;text-align:left;color:#d1d5db;font-size:13px;line-height:1.55}
</style>
</head>
<body>
<div class="card">
<h1>CyanBridge Pro</h1>
<p class="subtitle">Legacy Asaas hosted checkout</p>
<div class="plan-box"><div class="plan-name">${planLabel}</div><div class="plan-price">$${params.priceUsd}/mo &middot; R$ ${params.priceBrl.toFixed(2)}/mo</div></div>
<div class="note">${supportNote}</div>
<div id="status-box" class="status-box"><strong id="status-title">Waiting for payment confirmation</strong><p id="status-message">After finishing the card form in Asaas, return here and tap the confirmation button below.</p></div>
<a id="open-checkout" class="btn btn-primary" href="${invoiceUrl}" target="_blank" rel="noopener noreferrer">Open Legacy Asaas Card Form</a>
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
    if (!response.ok) throw new Error(payload.message || "Unable to verify the subscription yet.");
    if (payload.active) { showStatus("Payment confirmed", "Returning to CyanBridge..."); window.location.assign(successUrl); return; }
    if (payload.state === "inactive") { showStatus("Subscription not active yet", payload.message || "Complete the card form in Asaas, then try again."); }
    else if (showPendingMessage || payload.state === "pending") { showStatus("Waiting for confirmation", payload.message || "Asaas has not confirmed the recurring subscription yet. Please wait a few seconds and try again."); }
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unable to verify the subscription yet.";
    showStatus("Verification problem", message);
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

function englishCheckoutHtml(params: {
  plan: string;
  planLabel: string;
  priceUsd: number;
  priceBrl: number;
  apiToken: string;
  returnUrl: string;
  email: string;
  legacyCheckoutUrl: string;
  changePlanRequested: boolean;
  replaceSubscriptionId?: string;
  errorMessage?: string;
  values?: DirectCheckoutState;
}): string {
  const values = params.values ?? emptyDirectState(params.email);
  const errorBlock = params.errorMessage
    ? `<div class="error">${escapeHtml(params.errorMessage)}</div>`
    : "";
  const helperText = params.changePlanRequested
    ? "Enter your card details below to securely change your recurring CyanBridge plan."
    : "Pay on this English CyanBridge page. We send the card securely to Asaas, tokenize it, and create the recurring subscription directly.";

  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>CyanBridge Pro - Secure Card Checkout</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#08111f;color:#e6edf7;min-height:100vh;padding:20px}
.wrap{max-width:920px;margin:0 auto;display:grid;gap:20px;grid-template-columns:1.1fr 0.9fr}
.card{background:#101a2d;border:1px solid #21314f;border-radius:18px;padding:24px;box-shadow:0 18px 50px rgba(0,0,0,.35)}
h1{font-size:28px;margin-bottom:10px;color:#fff}.muted{color:#9db0c8;font-size:14px;line-height:1.5}.plan{background:#0f223f;border:1px solid #29446f;border-radius:14px;padding:18px;margin-top:18px}
.plan strong{display:block;font-size:20px;color:#7fd5ff}.price{margin-top:6px;color:#bfd4ea;font-size:14px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:14px;margin-top:18px}
label{display:block;font-size:13px;color:#bfd4ea;margin-bottom:6px}.field{width:100%;padding:14px 15px;border-radius:12px;border:1px solid #2f456b;background:#0a1424;color:#fff;font-size:15px}
.field::placeholder{color:#6f86a6}.row-3{display:grid;grid-template-columns:1.4fr .8fr .8fr;gap:14px}.full{grid-column:1 / -1}
.actions{display:flex;gap:12px;flex-wrap:wrap;margin-top:20px}.btn{appearance:none;border:none;border-radius:12px;padding:14px 18px;font-size:15px;font-weight:700;cursor:pointer;text-decoration:none;display:inline-flex;align-items:center;justify-content:center}
.btn-primary{background:#69d1ff;color:#02111e}.btn-secondary{background:transparent;color:#bfd4ea;border:1px solid #35517d}.btn-muted{background:#173156;color:#d6e6f8;border:1px solid #35517d}
.error{background:#3a1620;border:1px solid #8b3645;color:#ffd7dc;border-radius:12px;padding:12px 14px;margin:16px 0 0}.small{font-size:12px;color:#91a6c2;line-height:1.5;margin-top:14px}
.bullet{margin-top:14px;padding-left:18px;color:#bfd4ea;font-size:14px;line-height:1.6}.bullet li{margin-bottom:8px}.badge{display:inline-block;padding:6px 10px;border-radius:999px;background:#173156;color:#9ddfff;font-size:12px;font-weight:700;letter-spacing:.03em;margin-bottom:12px}
@media (max-width: 860px){.wrap{grid-template-columns:1fr}.grid,.row-3{grid-template-columns:1fr}}
</style>
</head>
<body>
<div class="wrap">
  <section class="card">
    <div class="badge">English checkout beta</div>
    <h1>Secure card checkout</h1>
    <p class="muted">${escapeHtml(helperText)}</p>
    <div class="plan">
      <strong>${escapeHtml(params.planLabel)}</strong>
      <div class="price">$${params.priceUsd}/month · R$ ${params.priceBrl.toFixed(2)}/month</div>
    </div>
    ${errorBlock}
    <form method="POST" action="/web-subscribe">
      <input type="hidden" name="api_token" value="${escapeHtml(params.apiToken)}">
      <input type="hidden" name="return_url" value="${escapeHtml(params.returnUrl)}">
      <input type="hidden" name="plan" value="${escapeHtml(params.plan)}">
      <input type="hidden" name="change_plan" value="${params.changePlanRequested ? "1" : "0"}">
      <input type="hidden" name="replace_subscription_id" value="${escapeHtml(params.replaceSubscriptionId ?? "")}">
      <div class="grid">
        <div class="full"><label for="holder_name">Name on card</label><input id="holder_name" class="field" name="holder_name" value="${escapeHtml(values.holderName)}" placeholder="Jane Smith" autocomplete="cc-name" required></div>
        <div class="full"><label for="email">Email</label><input id="email" class="field" type="email" name="email" value="${escapeHtml(values.email)}" placeholder="you@example.com" autocomplete="email" required></div>
        <div class="full"><label for="card_number">Card number</label><input id="card_number" class="field" name="card_number" placeholder="4111 1111 1111 1111" autocomplete="cc-number" inputmode="numeric" required></div>
        <div class="row-3 full">
          <div><label for="expiry_month">Expiry month</label><input id="expiry_month" class="field" name="expiry_month" placeholder="03" autocomplete="cc-exp-month" inputmode="numeric" required></div>
          <div><label for="expiry_year">Expiry year</label><input id="expiry_year" class="field" name="expiry_year" placeholder="2029" autocomplete="cc-exp-year" inputmode="numeric" required></div>
          <div><label for="ccv">Security code</label><input id="ccv" class="field" name="ccv" placeholder="123" autocomplete="cc-csc" inputmode="numeric" required></div>
        </div>
      </div>
      <div class="actions">
        <button class="btn btn-primary" type="submit">Pay securely in English</button>
        <a class="btn btn-secondary" href="${escapeHtml(params.legacyCheckoutUrl)}">Use legacy Asaas page</a>
      </div>
    </form>
  </section>
  <aside class="card">
    <h1 style="font-size:22px">How this works</h1>
    <ul class="bullet">
      <li>We collect the card details on this English CyanBridge page over HTTPS.</li>
      <li>We tokenize the card with Asaas and then create the recurring subscription directly in the API.</li>
      <li>Your card details are not stored in CyanBridge after the request completes.</li>
      <li>If the processor rejects the card, you can correct the details here without leaving the flow.</li>
    </ul>
    <p class="small">If your Asaas account does not have card tokenization enabled in production, use the legacy fallback link while the account setting is being enabled.</p>
  </aside>
</div>
</body>
</html>`;
}

async function ensureAsaasCustomerForCheckout(user: RelayUser, params: {
  name: string;
  email: string;
}): Promise<{ user: RelayUser; customerId: string }> {
  let currentUser = user;
  let asaasCustomerId = user.asaasCustomerId;

  if (asaasCustomerId) {
    try {
      await updateCustomer({
        customerId: asaasCustomerId,
        name: params.name,
        email: params.email,
        externalReference: user.id,
      });
    } catch {
      // Keep going; we can still try the existing customer id.
    }
  }

  if (!asaasCustomerId) {
    const customer = await createCustomer({
      name: params.name,
      email: params.email,
      foreignCustomer: true,
      externalReference: user.id,
    });
    asaasCustomerId = customer.id;
    currentUser = {
      ...currentUser,
      email: params.email,
      asaasCustomerId,
      updatedAt: new Date().toISOString(),
    };
    await saveRelayUser(currentUser);
  }

  return { user: currentUser, customerId: asaasCustomerId };
}

async function renderLegacyHostedCheckout(request: Request, params: {
  user: RelayUser;
  plan: string;
  returnUrl: string;
  customerEmail: string;
  native: boolean;
  replaceSubscriptionId?: string;
}): Promise<NextResponse> {
  const planInfo = RELAY_PLANS[params.plan];
  const priceUsd = planInfo?.priceUsd ?? 5;
  const exchangeRate = await getUsdToBrlRate();
  const priceBrl = await usdToBrl(priceUsd);

  const customerName = params.customerEmail;
  const { user, customerId } = await ensureAsaasCustomerForCheckout(params.user, {
    name: customerName,
    email: params.customerEmail,
  });

  const nextDueDateStr = new Date().toISOString().slice(0, 10);
  const subscription = await createSubscription({
    customerId,
    billingType: "CREDIT_CARD",
    value: priceBrl,
    nextDueDate: nextDueDateStr,
    cycle: "MONTHLY",
    description: `CyanBridge Pro - ${planInfo?.label ?? params.plan} ($${priceUsd}/mo)`,
    externalReference: user.id,
  });

  await saveRelayUser({
    ...user,
    asaasCustomerId: customerId,
    asaasSubscriptionId: subscription.id,
    plan: params.plan,
    updatedAt: new Date().toISOString(),
  });

  logInfo("relay_subscription_created", "Legacy Asaas subscription created for relay user", {
    relayUserId: user.id,
    asaasSubscriptionId: subscription.id,
    plan: params.plan,
    priceUsd,
    priceBrl,
    exchangeRate,
  });

  const subRecord = subscription as unknown as Record<string, unknown>;
  let invoiceUrl = subRecord.invoiceUrl as string | undefined;
  if (!invoiceUrl) {
    const subscriptionPayments = await listPaymentsBySubscription(subscription.id, 5);
    invoiceUrl = subscriptionPayments.find((payment) => payment.invoiceUrl)?.invoiceUrl;
  }

  const successUrl = appendQueryParams(new URL("/web-subscribe/success", request.url).toString(), {
    subscription_id: subscription.id,
    return_url: params.returnUrl,
    api_token: user.apiToken,
    plan: params.plan,
    replace_subscription_id: params.replaceSubscriptionId ?? "",
  });

  const statusUrl = appendQueryParams(new URL("/web-subscribe/status", request.url).toString(), {
    subscription_id: subscription.id,
    api_token: user.apiToken,
    plan: params.plan,
  });

  if (invoiceUrl) {
    if (params.native) {
      return NextResponse.json(
        {
          ok: true,
          mode: "legacy_hosted_checkout",
          invoice_url: invoiceUrl,
          status_url: statusUrl,
          success_url: successUrl,
          subscription_id: subscription.id,
          plan: params.plan,
          plan_label: planInfo?.label ?? params.plan,
          price_usd: priceUsd,
          price_brl: priceBrl,
          api_token: user.apiToken,
          email: user.email ?? "",
        },
        { headers: { "Cache-Control": "no-store" } },
      );
    }

    const html = legacyCheckoutHtml({
      invoiceUrl,
      planLabel: planInfo?.label ?? params.plan,
      priceUsd,
      priceBrl,
      statusUrl,
      successUrl,
    });
    return new NextResponse(html, {
      status: 200,
      headers: { "Content-Type": "text/html; charset=utf-8" },
    });
  }

  const errUrl = appendQueryParams(params.returnUrl, {
    status: "error",
    plan: params.plan,
    api_token: user.apiToken,
    email: user.email ?? "",
    message: "Asaas did not return a hosted checkout link for this subscription.",
  });
  return NextResponse.redirect(errUrl, 303);
}

function parseFormState(formData: FormData): DirectCheckoutState {
  return {
    holderName: String(formData.get("holder_name") ?? "").trim(),
    email: String(formData.get("email") ?? "").trim().toLowerCase(),
  };
}

function validateDirectCheckoutInput(state: DirectCheckoutState, card: {
  number: string;
  expiryMonth: string;
  expiryYear: string;
  ccv: string;
}): string | null {
  if (!state.holderName) return "Cardholder name is required.";
  if (!isRealCustomerEmail(state.email)) return "A valid email address is required.";
  if (card.number.length < 12) return "Enter a valid card number.";
  if (card.expiryMonth.length !== 2) return "Enter a valid expiry month.";
  if (card.expiryYear.length !== 4) return "Enter a valid expiry year.";
  if (card.ccv.length < 3) return "Enter a valid security code.";
  return null;
}

export async function GET(request: Request) {
  const { searchParams } = new URL(request.url);
  let plan = normalizePlan(searchParams.get("plan") ?? "standard");
  if (!isValidPlan(plan)) plan = "standard";

  const returnUrl = searchParams.get("return_url") ?? "";
  const email = (searchParams.get("email") ?? "").trim().toLowerCase();
  const tokenHint = (searchParams.get("api_token") ?? "").trim();
  const changePlanRequested = searchParams.get("change_plan") === "1";
  const legacyCheckoutRequested = searchParams.get("legacy_checkout") === "1";
  const nativeLegacyRequested = searchParams.get("native_legacy") === "1";
  let replaceSubscriptionId = (searchParams.get("replace_subscription_id") ?? "").trim();

  const user = await ensureRelayUser(tokenHint || undefined, email || undefined);
  const customerEmail = (user.email ?? "").trim().toLowerCase();

  if (!returnUrl) {
    return NextResponse.json({
      ok: true,
      message: "web checkout endpoint alive",
      api_token: user.apiToken,
      asaas_configured: isAsaasConfigured(),
      plan,
      price_usd: RELAY_PLANS[plan]?.priceUsd ?? 5,
      required_query: ["return_url"],
      optional_query: ["plan", "package_name", "api_token", "email", "change_plan", "legacy_checkout", "native_legacy"],
      mode: "english_direct_card",
    });
  }

  const alreadyActive = isSubscriptionActive(user.subscriptionStatus, Number(user.expiresAtMs));
  if (alreadyActive && !changePlanRequested) {
    const restoredUrl = appendQueryParams(returnUrl, {
      status: "success",
      plan: user.plan,
      token: user.asaasSubscriptionId || "restored_subscription",
      expires_at_ms: String(Number(user.expiresAtMs)),
      api_token: user.apiToken,
      email: user.email ?? "",
      message: "Your Pro subscription is already active. We restored it on this device.",
    });
    return NextResponse.redirect(restoredUrl, 303);
  }

  if (plan === "free_trial") {
    const expiresAtMs = planExpiryMs("free_trial");
    await saveRelayUser({
      ...user,
      plan: "free_trial",
      subscriptionStatus: "active",
      expiresAtMs,
      billingDay: user.billingDay ?? new Date().getUTCDate(),
      updatedAt: new Date().toISOString(),
    });
    const trialUrl = appendQueryParams(returnUrl, {
      status: "success",
      plan: "free_trial",
      token: "free_trial_activated",
      expires_at_ms: String(expiresAtMs),
      api_token: user.apiToken,
      email: user.email ?? "",
      message: "Free trial activated for 30 days.",
    });
    return NextResponse.redirect(trialUrl, 303);
  }

  if (!isAsaasConfigured()) {
    const errUrl = appendQueryParams(returnUrl, {
      status: "error",
      plan,
      api_token: user.apiToken,
      email: user.email ?? "",
      message: "Payment provider not configured. Please contact support.",
    });
    return NextResponse.redirect(errUrl, 303);
  }

  const planInfo = RELAY_PLANS[plan];
  const priceUsd = planInfo?.priceUsd ?? 5;

  if (!isRealCustomerEmail(customerEmail)) {
    const errUrl = appendQueryParams(returnUrl, {
      status: "error",
      plan,
      api_token: user.apiToken,
      email: "",
      message: "A valid email address is required before starting the subscription checkout.",
    });
    return NextResponse.redirect(errUrl, 303);
  }

  if (alreadyActive && changePlanRequested && user.asaasSubscriptionId) {
    if (normalizePlan(user.plan) === plan) {
      const unchangedUrl = appendQueryParams(returnUrl, {
        status: "success",
        plan,
        token: user.asaasSubscriptionId,
        expires_at_ms: String(Number(user.expiresAtMs)),
        api_token: user.apiToken,
        email: user.email ?? "",
        message: `Your ${planInfo?.label ?? plan} plan is already active.`,
      });
      return NextResponse.redirect(unchangedUrl, 303);
    }

    // Asaas only documents in-place subscription edits for BOLETO/PIX. For
    // CREDIT_CARD subscriptions we replace the subscription after the new one
    // is successfully paid, then cancel the old recurring schedule.
    replaceSubscriptionId = user.asaasSubscriptionId;
  }

  if (legacyCheckoutRequested) {
    try {
      return await renderLegacyHostedCheckout(request, {
        user,
        plan,
        returnUrl,
        customerEmail,
        native: nativeLegacyRequested,
        replaceSubscriptionId,
      });
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unknown error";
      const errUrl = appendQueryParams(returnUrl, {
        status: "error",
        plan,
        api_token: user.apiToken,
        email: user.email ?? "",
        message: `Legacy checkout setup failed: ${message}`,
      });
      return NextResponse.redirect(errUrl, 303);
    }
  }

  const exchangeRate = await getUsdToBrlRate();
  const priceBrl = await usdToBrl(priceUsd);
  const legacyCheckoutUrl = appendQueryParams(new URL("/web-subscribe", request.url).toString(), {
    plan,
    return_url: returnUrl,
    api_token: user.apiToken,
    email: customerEmail,
    change_plan: changePlanRequested ? "1" : "0",
    replace_subscription_id: replaceSubscriptionId,
    legacy_checkout: "1",
  });

  logInfo("relay_english_checkout_rendered", "Rendered English direct card checkout", {
    relayUserId: user.id,
    plan,
    priceUsd,
    priceBrl,
    exchangeRate,
    changePlanRequested,
  });

  const html = englishCheckoutHtml({
    plan,
    planLabel: planInfo?.label ?? plan,
    priceUsd,
    priceBrl,
    apiToken: user.apiToken,
    returnUrl,
    email: customerEmail,
    legacyCheckoutUrl,
    changePlanRequested,
    replaceSubscriptionId,
  });
  return new NextResponse(html, {
    status: 200,
    headers: { "Content-Type": "text/html; charset=utf-8" },
  });
}

export async function POST(request: Request) {
  const contentType = request.headers.get("content-type") ?? "";
  const formData = contentType.includes("application/x-www-form-urlencoded") || contentType.includes("multipart/form-data")
    ? await request.formData()
    : (() => { throw new Error("Unsupported content type for checkout form."); })();

  let plan = normalizePlan(String(formData.get("plan") ?? "standard"));
  if (!isValidPlan(plan)) plan = "standard";

  const returnUrl = String(formData.get("return_url") ?? "").trim();
  const apiToken = String(formData.get("api_token") ?? "").trim();
  const changePlanRequested = String(formData.get("change_plan") ?? "0") === "1";
  const replaceSubscriptionId = String(formData.get("replace_subscription_id") ?? "").trim();
  const state = parseFormState(formData);

  const card = {
    number: normalizeCardNumber(String(formData.get("card_number") ?? "")),
    expiryMonth: normalizeMonth(String(formData.get("expiry_month") ?? "")),
    expiryYear: normalizeYear(String(formData.get("expiry_year") ?? "")),
    ccv: normalizeDigits(String(formData.get("ccv") ?? "")),
  };

  const planInfo = RELAY_PLANS[plan];
  const priceUsd = planInfo?.priceUsd ?? 5;
  const priceBrl = await usdToBrl(priceUsd);

  const renderError = (message: string) => {
    const legacyCheckoutUrl = appendQueryParams(new URL("/web-subscribe", request.url).toString(), {
      plan,
      return_url: returnUrl,
      api_token: apiToken,
      email: state.email,
      change_plan: changePlanRequested ? "1" : "0",
      replace_subscription_id: replaceSubscriptionId,
      legacy_checkout: "1",
    });
    const html = englishCheckoutHtml({
      plan,
      planLabel: planInfo?.label ?? plan,
      priceUsd,
      priceBrl,
      apiToken,
      returnUrl,
      email: state.email,
      legacyCheckoutUrl,
      changePlanRequested,
      replaceSubscriptionId,
      errorMessage: message,
      values: state,
    });
    return new NextResponse(html, {
      status: 400,
      headers: { "Content-Type": "text/html; charset=utf-8" },
    });
  };

  if (!apiToken) return renderError("Missing account token. Please reopen checkout from the app.");
  if (!returnUrl) return renderError("Missing return URL. Please reopen checkout from the app.");
  if (!isAsaasConfigured()) return renderError("Payment provider is not configured.");

  const validationError = validateDirectCheckoutInput(state, card);
  if (validationError) return renderError(validationError);

  try {
    const user = await ensureRelayUser(apiToken, state.email);
    const { user: ensuredUser, customerId } = await ensureAsaasCustomerForCheckout(user, {
      name: state.holderName,
      email: state.email,
    });

    const remoteIp = getClientIp(request);
    const tokenized = await tokenizeCreditCard({
      customerId,
      creditCard: {
        holderName: state.holderName,
        number: card.number,
        expiryMonth: card.expiryMonth,
        expiryYear: card.expiryYear,
        ccv: card.ccv,
      },
      creditCardHolderInfo: {
        name: state.holderName,
        email: state.email,
      },
      remoteIp,
    });

    const nextDueDateStr = new Date().toISOString().slice(0, 10);
    const subscription = await createSubscription({
      customerId,
      billingType: "CREDIT_CARD",
      value: priceBrl,
      nextDueDate: nextDueDateStr,
      cycle: "MONTHLY",
      description: `CyanBridge Pro - ${planInfo?.label ?? plan} ($${priceUsd}/mo)`,
      externalReference: ensuredUser.id,
      creditCardToken: tokenized.creditCardToken,
      remoteIp,
    });

    await saveRelayUser({
      ...ensuredUser,
      email: state.email,
      asaasCustomerId: customerId,
      asaasSubscriptionId: subscription.id,
      plan,
      updatedAt: new Date().toISOString(),
    });

    logInfo("relay_direct_subscription_created", "Created direct English Asaas subscription via tokenization", {
      relayUserId: ensuredUser.id,
      asaasCustomerId: customerId,
      asaasSubscriptionId: subscription.id,
      plan,
      priceUsd,
      priceBrl,
      changePlanRequested,
    });

    const successUrl = appendQueryParams(new URL("/web-subscribe/success", request.url).toString(), {
      subscription_id: subscription.id,
      return_url: returnUrl,
      api_token: ensuredUser.apiToken,
      plan,
      replace_subscription_id: replaceSubscriptionId,
    });
    return NextResponse.redirect(successUrl, 303);
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unknown error";
    logError("relay_direct_subscription_failed", "Failed to create direct English Asaas subscription", {
      apiToken,
      plan,
      error: message,
    });
    return renderError(message);
  }
}
