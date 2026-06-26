/**
 * Asaas API v3 client module.
 *
 * Endpoints used:
 *   POST /customers         – create customer
 *   POST /payments          – create payment (PIX / BOLETO / CREDIT_CARD / parcelado)
 *   GET  /payments/{id}     – get payment details (incl. boleto identificationField)
 *   GET  /payments/{id}/pixQrCode – PIX QR code + payload
 *   POST /subscriptions     – create recurring subscription
 *
 * Auth: `access_token` header via ASAAS_API_KEY env var.
 *
 * @see https://docs.asaas.com/reference
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

export const ASAAS_SANDBOX_BASE = "https://sandbox.asaas.com/api/v3";
export const ASAAS_PRODUCTION_BASE = "https://api.asaas.com/api/v3";

/* ------------------------------------------------------------------ */
/*  Helpers                                                            */
/* ------------------------------------------------------------------ */

function resolveBaseUrl(): string {
  return process.env.ASAAS_API_BASE_URL?.trim() || ASAAS_SANDBOX_BASE;
}

const API_KEY_ENV_CANDIDATES = [
  "ASAAS_API_KEY",
  "ASAAS_SANDBOX_API_KEY",
  "NEXT_PRIVATE_ASAAS_API_KEY",
  "ASAAS_ACCESS_TOKEN",
] as const;

function unwrapQuotedValue(value: string): string {
  const trimmed = value.trim();
  if (
    (trimmed.startsWith('"') && trimmed.endsWith('"')) ||
    (trimmed.startsWith("'") && trimmed.endsWith("'"))
  ) {
    return trimmed.slice(1, -1);
  }
  return trimmed;
}

function normalizeApiKey(value: string): string {
  const trimmed = unwrapQuotedValue(value);
  if (trimmed.startsWith("\\$")) {
    return trimmed.slice(1);
  }
  return trimmed;
}

function readApiKeyFromDotEnv(): string | undefined {
  try {
    const envPath = join(process.cwd(), ".env");
    const content = readFileSync(envPath, "utf8");
    const lines = content.split(/\r?\n/);

    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith("#")) continue;

      const eqIndex = trimmed.indexOf("=");
      if (eqIndex < 1) continue;

      const key = trimmed.slice(0, eqIndex).trim();
      if (key !== "ASAAS_API_KEY") continue;

      const value = trimmed.slice(eqIndex + 1);
      const normalized = normalizeApiKey(value);
      return normalized || undefined;
    }
  } catch {
    // Optional fallback only.
  }
  return undefined;
}

function getApiKey(): string | undefined {
  for (const envName of API_KEY_ENV_CANDIDATES) {
    const raw = process.env[envName]?.trim();
    if (!raw) continue;
    const normalized = normalizeApiKey(raw);
    if (normalized) return normalized;
  }

  return readApiKeyFromDotEnv();
}

function buildHeaders(): Record<string, string> {
  const token = getApiKey();
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    accept: "application/json",
  };
  if (token) {
    headers["access_token"] = token;
  }
  return headers;
}

/** True when the Asaas API key is present in environment. */
export function isAsaasConfigured(): boolean {
  return Boolean(getApiKey());
}

/* ------------------------------------------------------------------ */
/*  Types                                                              */
/* ------------------------------------------------------------------ */

export interface AsaasCustomer {
  object: "customer";
  id: string;
  dateCreated: string;
  name: string;
  email: string;
  phone?: string;
  cpfCnpj?: string;
  externalReference?: string;
  notificationDisabled?: boolean;
}

export interface AsaasPayment {
  object: "payment";
  id: string;
  dateCreated: string;
  customer: string;
  billingType: "PIX" | "BOLETO" | "CREDIT_CARD";
  value: number;
  netValue: number;
  description?: string;
  dueDate: string;
  status: "PENDING" | "RECEIVED" | "CONFIRMED" | "OVERDUE" | "REFUNDED" | "RECEIVED_IN_CASH" | "PARTIAL" | "CANCELED";
  invoiceUrl?: string;
  invoiceNumber?: string;
  bankSlipUrl?: string;
  externalReference?: string;
  installmentNumber?: number | null;
  installmentCount?: number | null;
  installmentValue?: number | null;
  pixQrCode?: string | null;
  pixCopyPaste?: string | null;
  bankSlip?: {
    url: string;
    barCode: string;
    identificationField: string;
    nossoNumero: string;
  } | null;
}

/** Response detail when fetching a single payment (includes bankSlip nested object). */
export interface AsaasPaymentDetail extends AsaasPayment {
  bankSlip?: {
    url: string;
    barCode: string;
    identificationField: string;
    nossoNumero: string;
  } | null;
}

export interface AsaasPixQrCode {
  success: boolean;
  encodedImage: string;   // base64 PNG
  payload: string;        // copy-paste PIX code
  expirationDate: string;
}

export type AsaasHostedSubscriptionState = "active" | "pending" | "inactive";

export interface AsaasSubscription {
  object: "subscription";
  id: string;
  dateCreated: string;
  customer: string;
  billingType: "PIX" | "BOLETO" | "CREDIT_CARD";
  value: number;
  nextDueDate: string;
  cycle: "WEEKLY" | "BIWEEKLY" | "MONTHLY" | "QUARTERLY" | "SEMIANNUALLY" | "ANNUALLY";
  description?: string;
  status: "ACTIVE" | "INACTIVE" | "CANCELED" | "EXPIRED";
  externalReference?: string;
  debitDate?: string | null;
}

export interface AsaasCreditCard {
  holderName: string;
  number: string;
  expiryMonth: string;
  expiryYear: string;
  ccv: string;
}

export interface AsaasCreditCardHolderInfo {
  name: string;
  email: string;
  cpfCnpj?: string;
  postalCode?: string;
  addressNumber?: string;
  phone?: string;
  mobilePhone?: string;
}

export interface AsaasCreditCardTokenizationResult {
  creditCardToken: string;
}

export interface AsaasApiError {
  errors: Array<{
    code: string;
    description: string;
  }>;
}

/** Parameters for creating a generic Asaas payment (single charge). */
export interface CreatePaymentParams {
  customerId: string;
  billingType: "PIX" | "BOLETO" | "CREDIT_CARD";
  value: number;
  dueDate: string;          // "YYYY-MM-DD"
  description?: string;
  externalReference?: string;
  /** For installment charges: number of installments (≥2). Omit for single charge. */
  installmentCount?: number;
  /** For installment charges: value per installment. If omitted, value/total is used as installmentValue. */
  installmentValue?: number;
}

/** Parameters for creating an Asaas subscription (recurring). */
export interface CreateSubscriptionParams {
  customerId: string;
  billingType: "PIX" | "BOLETO" | "CREDIT_CARD";
  value: number;
  nextDueDate: string;      // "YYYY-MM-DD" – first charge date
  cycle: "WEEKLY" | "BIWEEKLY" | "MONTHLY" | "QUARTERLY" | "SEMIANNUALLY" | "ANNUALLY";
  description?: string;
  externalReference?: string;
  endDate?: string;          // optional end date
  maxPayments?: number;      // optional max number of payments
  creditCard?: AsaasCreditCard;
  /** Credit card holder info (required for CREDIT_CARD billingType subscriptions). */
  creditCardHolderInfo?: AsaasCreditCardHolderInfo;
  creditCardToken?: string;
  remoteIp?: string;
}

export interface TokenizeCreditCardParams {
  customerId: string;
  creditCard: AsaasCreditCard;
  creditCardHolderInfo: AsaasCreditCardHolderInfo;
  remoteIp: string;
}

export interface UpdateSubscriptionParams {
  subscriptionId: string;
  billingType?: "PIX" | "BOLETO" | "CREDIT_CARD";
  value?: number;
  nextDueDate?: string;
  cycle?: "WEEKLY" | "BIWEEKLY" | "MONTHLY" | "QUARTERLY" | "SEMIANNUALLY" | "ANNUALLY";
  description?: string;
  externalReference?: string;
  endDate?: string;
  maxPayments?: number;
}

/* ------------------------------------------------------------------ */
/*  API calls                                                          */
/* ------------------------------------------------------------------ */

async function apiFetch<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const base = resolveBaseUrl();
  const url = `${base}${path}`;
  const res = await fetch(url, {
    ...options,
    headers: {
      ...buildHeaders(),
      ...(options.headers as Record<string, string>),
    },
  });

  if (!res.ok) {
    let detail = `Asaas API ${res.status} on ${options.method ?? "GET"} ${path}`;
    try {
      const body = (await res.json()) as AsaasApiError;
      if (body.errors?.length) {
        detail += `: ${body.errors.map((e) => e.description).join("; ")}`;
      }
    } catch {
      // ignore parse failure
    }
    throw new Error(detail);
  }

  return res.json() as Promise<T>;
}

/**
 * Create an Asaas customer.
 * If `cpfCnpj` is omitted the customer is created as generic.
 * Set `foreignCustomer: true` for non-Brazilian customers (no CPF/CNPJ required).
 */
export async function createCustomer(params: {
  name: string;
  email: string;
  cpfCnpj?: string;
  externalReference?: string;
  foreignCustomer?: boolean;
}): Promise<AsaasCustomer> {
  return apiFetch<AsaasCustomer>("/customers", {
    method: "POST",
    body: JSON.stringify({
      name: params.name,
      email: params.email,
      cpfCnpj: params.cpfCnpj || undefined,
      externalReference: params.externalReference || undefined,
      foreignCustomer: params.foreignCustomer ?? false,
      notificationDisabled: true,
    }),
  });
}

export async function updateCustomer(params: {
  customerId: string;
  name: string;
  email: string;
  cpfCnpj?: string;
  externalReference?: string;
}): Promise<AsaasCustomer> {
  const body = JSON.stringify({
    name: params.name,
    email: params.email,
    cpfCnpj: params.cpfCnpj || undefined,
    externalReference: params.externalReference || undefined,
    notificationDisabled: true,
  });

  try {
    return await apiFetch<AsaasCustomer>(`/customers/${params.customerId}`, {
      method: "POST",
      body,
    });
  } catch {
    return apiFetch<AsaasCustomer>(`/customers/${params.customerId}`, {
      method: "PUT",
      body,
    });
  }
}

/**
 * Find existing customers by email (exact match).
 * Returns the first match or null.
 */
export async function findCustomerByEmail(
  email: string,
): Promise<AsaasCustomer | null> {
  const data = await apiFetch<{
    object: "list";
    data: AsaasCustomer[];
    totalCount: number;
  }>(`/customers?email=${encodeURIComponent(email)}&limit=10`);

  return data.data.find((c) => c.email.toLowerCase() === email.toLowerCase()) ?? null;
}

/**
 * Create a payment (PIX, BOLETO, or CREDIT_CARD), optionally with installments.
 *
 * For single charges, omit `installmentCount` and `installmentValue`.
 * For installment charges, set `installmentCount` (≥2) and optionally `installmentValue`.
 */
export async function createPayment(
  params: CreatePaymentParams,
): Promise<AsaasPayment> {
  const body: Record<string, unknown> = {
    customer: params.customerId,
    billingType: params.billingType,
    value: params.value,
    dueDate: params.dueDate,
    description: params.description || undefined,
    externalReference: params.externalReference || undefined,
  };

  // Installment fields: only send when installmentCount >= 2
  if (params.installmentCount && params.installmentCount >= 2) {
    body.installmentCount = params.installmentCount;
    if (params.installmentValue !== undefined) {
      body.installmentValue = params.installmentValue;
    }
  }

  return apiFetch<AsaasPayment>("/payments", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

/**
 * Convenience: create a PIX payment (single charge).
 * Delegates to the generic `createPayment`.
 */
export async function createPixPayment(params: {
  customerId: string;
  value: number;
  dueDate: string;
  description?: string;
  externalReference?: string;
}): Promise<AsaasPayment> {
  return createPayment({
    ...params,
    billingType: "PIX",
  });
}

/**
 * Retrieve PIX QR code data for a given payment.
 */
export async function getPixQrCode(
  paymentId: string,
): Promise<AsaasPixQrCode> {
  return apiFetch<AsaasPixQrCode>(`/payments/${paymentId}/pixQrCode`);
}

/**
 * Find Asaas customer by CPF/CNPJ.
 */
export async function findCustomerByCpfCnpj(
  cpfCnpj: string,
): Promise<AsaasCustomer | null> {
  const data = await apiFetch<{
    object: "list";
    data: AsaasCustomer[];
    totalCount: number;
  }>(`/customers?cpfCnpj=${encodeURIComponent(cpfCnpj)}&limit=10`);

  return data.data.find((c) => c.cpfCnpj === cpfCnpj) ?? null;
}

/**
 * Find Asaas customer by externalReference.
 */
export async function findCustomerByExternalReference(
  externalReference: string,
): Promise<AsaasCustomer | null> {
  const data = await apiFetch<{
    object: "list";
    data: AsaasCustomer[];
    totalCount: number;
  }>(`/customers?externalReference=${encodeURIComponent(externalReference)}&limit=10`);

  return data.data.find((c) => c.externalReference === externalReference) ?? null;
}

/**
 * Convenience: create or find an Asaas customer.
 * Checks externalReference first, then email, then creates new.
 */
export async function createOrFindCustomer(params: {
  name: string;
  email: string;
  cpfCnpj?: string;
  externalReference?: string;
}): Promise<AsaasCustomer> {
  async function ensureDocumentIfMissing(customer: AsaasCustomer): Promise<AsaasCustomer> {
    if (!params.cpfCnpj) return customer;
    if (customer.cpfCnpj === params.cpfCnpj) return customer;

    try {
      return await updateCustomer({
        customerId: customer.id,
        name: customer.name || params.name,
        email: customer.email || params.email,
        cpfCnpj: params.cpfCnpj,
        externalReference: params.externalReference || customer.externalReference,
      });
    } catch {
      return customer;
    }
  }

  // If externalReference is set, try to find by it first (deduplication)
  if (params.externalReference) {
    const byRef = await findCustomerByExternalReference(params.externalReference);
    if (byRef) return ensureDocumentIfMissing(byRef);
  }

  // Fallback to email lookup
  const existing = await findCustomerByEmail(params.email);
  if (existing) {
    return ensureDocumentIfMissing(existing);
  }

  // CPF/CNPJ lookup as last duplicate check before creating
  if (params.cpfCnpj) {
    const byDoc = await findCustomerByCpfCnpj(params.cpfCnpj);
    if (byDoc) return ensureDocumentIfMissing(byDoc);
  }

  return createCustomer(params);
}

/**
 * List payments by externalReference (for duplicate charge protection).
 */
export async function listPaymentsByExternalReference(
  externalReference: string,
  limit = 5,
): Promise<AsaasPayment[]> {
  const data = await apiFetch<{
    object: "list";
    data: AsaasPayment[];
    totalCount: number;
  }>(`/payments?externalReference=${encodeURIComponent(externalReference)}&limit=${limit}`);

  return data.data;
}

/**
 * List payments created for a specific subscription.
 */
export async function listPaymentsBySubscription(
  subscriptionId: string,
  limit = 5,
): Promise<AsaasPayment[]> {
  const data = await apiFetch<{
    object: "list";
    data: AsaasPayment[];
    totalCount: number;
  }>(`/payments?subscription=${encodeURIComponent(subscriptionId)}&limit=${limit}`);

  return data.data;
}

function isConfirmedPaymentStatus(status: string | null | undefined): boolean {
  const normalized = (status ?? "").trim().toUpperCase();
  return normalized === "RECEIVED" || normalized === "CONFIRMED" || normalized === "RECEIVED_IN_CASH";
}

function isPendingPaymentStatus(status: string | null | undefined): boolean {
  const normalized = (status ?? "").trim().toUpperCase();
  return normalized === "PENDING" || normalized === "PARTIAL";
}

/**
 * For the hosted subscription checkout flow, the Asaas subscription can be ACTIVE
 * before the first credit-card payment is actually confirmed. We therefore derive
 * the usable app entitlement state from the linked payment statuses instead.
 */
export async function getHostedSubscriptionState(
  subscriptionId: string,
): Promise<{ state: AsaasHostedSubscriptionState; paymentStatus: string | null }> {
  const payments = await listPaymentsBySubscription(subscriptionId, 20);
  const latestStatus = payments[0]?.status ?? null;

  if (payments.some((payment) => isConfirmedPaymentStatus(payment.status))) {
    return { state: "active", paymentStatus: latestStatus };
  }

  if (payments.some((payment) => isPendingPaymentStatus(payment.status))) {
    return { state: "pending", paymentStatus: latestStatus };
  }

  return { state: "inactive", paymentStatus: latestStatus };
}

/**
 * Get single payment details by ID.
 * Full response includes nested `bankSlip` object for boleto payments.
 */
export async function getPayment(
  paymentId: string,
): Promise<AsaasPaymentDetail> {
  return apiFetch<AsaasPaymentDetail>(`/payments/${paymentId}`);
}

/**
 * Get payment status by ID.
 * Returns just the status string for lightweight checks.
 */
export async function getPaymentStatus(
  paymentId: string,
): Promise<AsaasPayment["status"]> {
  const payment = await getPayment(paymentId);
  return payment.status;
}

/**
 * Retrieve boleto details (bankSlip URL + identificationField) for a given payment.
 * Fetches the payment and extracts the nested `bankSlip` object.
 */
export async function getBoletoDetails(
  paymentId: string,
): Promise<{ bankSlipUrl: string; identificationField: string; barCode: string } | null> {
  const payment = await getPayment(paymentId);
  if (payment.bankSlip?.url) {
    return {
      bankSlipUrl: payment.bankSlip.url,
      identificationField: payment.bankSlip.identificationField,
      barCode: payment.bankSlip.barCode,
    };
  }
  // Fallback: top-level bankSlipUrl
  if (payment.bankSlipUrl) {
    return {
      bankSlipUrl: payment.bankSlipUrl,
      identificationField: "",
      barCode: "",
    };
  }
  return null;
}

/**
 * Create an Asaas subscription (recurring payment).
 *
 * For CREDIT_CARD billingType, use the hosted checkout flow (invoiceUrl will be returned
 * for the first payment). If using the direct API, you must also provide credit card holder info.
 *
 * The response `id` can be used to track subscription lifecycle events via webhooks.
 */
export async function createSubscription(
  params: CreateSubscriptionParams,
): Promise<AsaasSubscription> {
  const body: Record<string, unknown> = {
    customer: params.customerId,
    billingType: params.billingType,
    value: params.value,
    nextDueDate: params.nextDueDate,
    cycle: params.cycle,
    description: params.description || undefined,
    externalReference: params.externalReference || undefined,
    endDate: params.endDate || undefined,
    maxPayments: params.maxPayments || undefined,
  };

  if (params.creditCardHolderInfo) {
    body.creditCardHolderInfo = params.creditCardHolderInfo;
  }

  if (params.creditCard) {
    body.creditCard = params.creditCard;
  }

  if (params.creditCardToken) {
    body.creditCardToken = params.creditCardToken;
  }

  if (params.remoteIp) {
    body.remoteIp = params.remoteIp;
  }

  return apiFetch<AsaasSubscription>("/subscriptions", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export async function tokenizeCreditCard(
  params: TokenizeCreditCardParams,
): Promise<AsaasCreditCardTokenizationResult> {
  return apiFetch<AsaasCreditCardTokenizationResult>("/creditCard/tokenizeCreditCard", {
    method: "POST",
    body: JSON.stringify({
      customer: params.customerId,
      creditCard: params.creditCard,
      creditCardHolderInfo: params.creditCardHolderInfo,
      remoteIp: params.remoteIp,
    }),
  });
}

/**
 * Get a subscription by ID.
 */
export async function getSubscription(
  subscriptionId: string,
): Promise<AsaasSubscription> {
  return apiFetch<AsaasSubscription>(`/subscriptions/${subscriptionId}`);
}

export async function updateSubscription(
  params: UpdateSubscriptionParams,
): Promise<AsaasSubscription> {
  const body = JSON.stringify({
    billingType: params.billingType,
    value: params.value,
    nextDueDate: params.nextDueDate,
    cycle: params.cycle,
    description: params.description,
    externalReference: params.externalReference,
    endDate: params.endDate,
    maxPayments: params.maxPayments,
  });

  try {
    return await apiFetch<AsaasSubscription>(`/subscriptions/${params.subscriptionId}`, {
      method: "POST",
      body,
    });
  } catch {
    return apiFetch<AsaasSubscription>(`/subscriptions/${params.subscriptionId}`, {
      method: "PUT",
      body,
    });
  }
}

/**
 * Delete (cancel) a subscription by ID.
 */
export async function deleteSubscription(
  subscriptionId: string,
): Promise<void> {
  await apiFetch<Record<string, unknown>>(`/subscriptions/${subscriptionId}`, {
    method: "DELETE",
  });
}
