"use client";

import { useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Elements, ExpressCheckoutElement, PaymentElement, useElements, useStripe } from "@stripe/react-stripe-js";
import { loadStripe } from "@stripe/stripe-js";

type PaymentStepProps = {
  email: string;
  fullName: string;
  sex: string;
  healthCondition: string;
  dateOfBirth: string;
  visionLevel: string;
  dailyRoutine: string;
  techComfort: string;
  livingSituation: string;
  primaryConcern: string;
  medications: string;
  allergies: string;
  emergencyName: string;
  emergencyPhone: string;
  glassesColor: string;
  cpfCnpj?: string;
  purchaseType: "subscription" | "one_time" | "glasses_subscription";
  couponCode: string;
};

type ErrorState = {
  message: string;
  retryable: boolean;
};

type IntentResponse = {
  ok: boolean;
  draft?: boolean;
  clientSecret?: string;
  amountLabel?: string;
  successUrl?: string;
  message?: string;
};

type AsaasResponse = {
  ok: boolean;
  provider?: "asaas";
  paymentId?: string;
  subscriptionId?: string;
  localId?: string | null;
  status?: string;
  billingType?: string;
  invoiceUrl?: string | null;
  amountLabel?: string;
  pixEncodedImage?: string | null;
  pixCopyPaste?: string | null;
  bankSlipUrl?: string | null;
  identificationField?: string | null;
  installmentCount?: number | null;
  installmentValue?: number | null;
  isRecurring?: boolean;
  dueDate?: string;
  message?: string;
};

/* ------------------------------------------------------------------ */
/*  Provider detection                                                 */
/* ------------------------------------------------------------------ */

const PAYMENT_PROVIDER: "stripe" | "asaas" =
  process.env.NEXT_PUBLIC_PAYMENT_PROVIDER === "asaas" ? "asaas" : "stripe";

const stripePromise =
  PAYMENT_PROVIDER === "stripe" && process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY
    ? loadStripe(process.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY)
    : null;

/* ================================================================== */
/*  Stripe confirmation form (unchanged)                               */
/* ================================================================== */

function ConfirmPaymentForm({ successUrl }: { successUrl: string }) {
  const stripe = useStripe();
  const elements = useElements();
  const router = useRouter();
  const [error, setError] = useState<ErrorState | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function confirmWithElements() {
    if (!stripe || !elements) return;

    setIsSubmitting(true);
    setError(null);

    const { error: stripeError, paymentIntent } = await stripe.confirmPayment({
      elements,
      confirmParams: { return_url: successUrl },
      redirect: "if_required",
    });

    if (stripeError) {
      setError({
        message: stripeError.message ?? "Payment confirmation failed.",
        retryable:
          stripeError.type === "card_error" || stripeError.type === "invalid_request_error",
      });
      setIsSubmitting(false);
      return;
    }

    if (paymentIntent?.status === "succeeded") {
      router.push(successUrl);
      return;
    }

    setError({
      message: "Payment is still processing. Please wait a moment.",
      retryable: false,
    });
    setIsSubmitting(false);
  }

  return (
    <div className="space-y-4">
      <ExpressCheckoutElement
        options={{ buttonHeight: 44 }}
        onConfirm={confirmWithElements}
      />

      <div className="rounded-xl border border-white/65 bg-white/70 p-3">
        <PaymentElement options={{ layout: "tabs" }} />
      </div>

      {error ? (
        <div className="rounded-xl border border-rose-200 bg-rose-50 p-3">
          <p className="text-sm text-rose-700">{error.message}</p>
          <div className="mt-2 flex flex-wrap gap-2">
            {error.retryable && (
              <button
                type="button"
                onClick={confirmWithElements}
                disabled={!stripe || !elements || isSubmitting}
                className="btn-primary text-xs disabled:cursor-not-allowed disabled:opacity-60"
              >
                {isSubmitting ? "Processing..." : "Try again"}
              </button>
            )}
          </div>
          <a href="/support" className="mt-2 block text-xs text-rose-600 underline">
            Need help? Contact support
          </a>
        </div>
      ) : null}

      <button
        type="button"
        onClick={confirmWithElements}
        disabled={!stripe || !elements || isSubmitting}
        className="btn-primary w-full disabled:cursor-not-allowed disabled:opacity-60"
      >
        {isSubmitting ? "Processing..." : "Confirm payment"}
      </button>
    </div>
  );
}

/* ================================================================== */
/*  Asaas payment method selector                                      */
/* ================================================================== */

type PaymentMethod = "PIX" | "BOLETO" | "CREDIT_CARD";

const PAYMENT_METHODS: Array<{ value: PaymentMethod; label: string; description: string }> = [
  { value: "PIX", label: "PIX", description: "Instant payment with QR code or copy/paste code" },
  { value: "BOLETO", label: "Bank slip", description: "Boleto payment due within 2 days" },
  { value: "CREDIT_CARD", label: "Credit card", description: "Card payment through Asaas checkout" },
];

function AsaasMethodSelector({
  selected,
  onChange,
}: {
  selected: PaymentMethod;
  onChange: (method: PaymentMethod) => void;
}) {
  return (
    <div className="space-y-2">
      <p className="text-xs font-semibold uppercase tracking-[0.14em] text-slate-600">
        Payment method
      </p>
      <div className="grid gap-2 sm:grid-cols-3">
        {PAYMENT_METHODS.map((method) => (
          <button
            key={method.value}
            type="button"
            onClick={() => onChange(method.value)}
            className={`rounded-xl border p-3 text-left transition-all ${
              selected === method.value
                ? "border-brand-strong bg-brand-50 ring-1 ring-brand-strong"
                : "border-white/65 bg-white/60 hover:border-slate-300"
            }`}
          >
            <p className="text-sm font-semibold text-slate-900">{method.label}</p>
            <p className="mt-0.5 text-[10px] leading-tight text-slate-500">{method.description}</p>
          </button>
        ))}
      </div>
    </div>
  );
}

/* ================================================================== */
/*  Asaas PIX display                                                  */
/* ================================================================== */

function AsaasPixDisplay({
  data,
  purchaseType,
  onBack,
}: {
  data: AsaasResponse;
  purchaseType: string;
  onBack: () => void;
}) {
  const router = useRouter();
  const pixInputRef = useRef<HTMLInputElement>(null);
  const hasPaymentInstructions = Boolean(data.pixEncodedImage || data.pixCopyPaste || data.invoiceUrl);

  function handleCopyPix() {
    if (data.pixCopyPaste && pixInputRef.current) {
      pixInputRef.current.select();
      navigator.clipboard?.writeText(data.pixCopyPaste).catch(() => {});
    }
  }

  function handleConfirm() {
    router.push(`/success?purchase=${purchaseType}&asaas=1`);
  }

  return (
    <div className="space-y-4">
      <div className="rounded-xl border border-emerald-200 bg-emerald-50 p-4 text-center">
        <p className="text-xs uppercase tracking-[0.14em] text-emerald-700">
          PIX payment
        </p>
        <p className="mt-1 text-xl font-semibold text-emerald-900">
          {data.amountLabel ?? "Valor"}
        </p>
        {data.dueDate && (
          <p className="mt-1 text-xs text-emerald-600">
            Due date: {new Date(data.dueDate + "T23:59:59").toLocaleDateString("en-US")}
          </p>
        )}
        {purchaseType === "subscription" ? (
          <p className="mt-2 text-xs text-emerald-700">
            Payment for the current software subscription cycle.
          </p>
        ) : null}
      </div>

      {/* QR Code */}
      {data.pixEncodedImage ? (
        <div className="flex justify-center">
          <div className="rounded-xl border border-white/65 bg-white p-3">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={`data:image/png;base64,${data.pixEncodedImage}`}
              alt="PIX QR code"
              className="h-48 w-48"
            />
          </div>
        </div>
      ) : null}

      {/* Copy-paste PIX code */}
      {data.pixCopyPaste ? (
        <div className="space-y-2">
          <p className="text-xs font-semibold text-slate-600">
            PIX code (copy and paste):
          </p>
          <div className="flex gap-2">
            <input
              ref={pixInputRef}
              readOnly
              value={data.pixCopyPaste}
              className="flex-1 rounded-lg border border-white/65 bg-white/70 px-3 py-2 text-xs text-slate-800"
            />
            <button
              type="button"
              onClick={handleCopyPix}
              className="btn-secondary shrink-0 text-xs"
            >
              Copy
            </button>
          </div>
        </div>
      ) : null}

      {/* Invoice URL */}
      {data.invoiceUrl ? (
        <a
          href={data.invoiceUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="block text-center text-xs text-brand-strong underline"
        >
          View invoice
        </a>
      ) : null}

      {/* Confirm button */}
      {hasPaymentInstructions ? (
        <button
          type="button"
          onClick={handleConfirm}
          className="btn-primary w-full"
        >
          I already paid
        </button>
      ) : (
        <p className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-800">
          Payment instructions could not be loaded right now. Go back and try generating the PIX again.
        </p>
      )}

      <button
        type="button"
        onClick={onBack}
        className="block w-full text-center text-xs text-slate-500 underline"
      >
        Back
      </button>
    </div>
  );
}

/* ================================================================== */
/*  Asaas Boleto display                                               */
/* ================================================================== */

function AsaasBoletoDisplay({
  data,
  purchaseType,
  onBack,
}: {
  data: AsaasResponse;
  purchaseType: string;
  onBack: () => void;
}) {
  const router = useRouter();
  const boletoInputRef = useRef<HTMLInputElement>(null);
  const hasBoletoInfo = Boolean(data.bankSlipUrl);

  function handleCopyIdentField() {
    if (data.identificationField && boletoInputRef.current) {
      boletoInputRef.current.select();
      navigator.clipboard?.writeText(data.identificationField).catch(() => {});
    }
  }

  function handleConfirm() {
    router.push(`/success?purchase=${purchaseType}&asaas=1`);
  }

  return (
    <div className="space-y-4">
      <div className="rounded-xl border border-amber-200 bg-amber-50 p-4 text-center">
        <p className="text-xs uppercase tracking-[0.14em] text-amber-700">
          Bank slip
        </p>
        <p className="mt-1 text-xl font-semibold text-amber-900">
          {data.amountLabel ?? "Valor"}
        </p>
        {data.dueDate && (
          <p className="mt-1 text-xs text-amber-600">
            Due date: {new Date(data.dueDate + "T23:59:59").toLocaleDateString("en-US")}
          </p>
        )}
        {purchaseType === "subscription" ? (
          <p className="mt-2 text-xs text-amber-700">
            Payment for the current software subscription cycle.
          </p>
        ) : null}
      </div>

      {/* Bank slip URL */}
      {data.bankSlipUrl ? (
        <a
          href={data.bankSlipUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="btn-primary block w-full text-center"
        >
          View bank slip
        </a>
      ) : null}

      {/* Identification field (linha digitável) */}
      {data.identificationField ? (
        <div className="space-y-2">
            <p className="text-xs font-semibold text-slate-600">
            Payment line:
            </p>
          <div className="flex gap-2">
            <input
              ref={boletoInputRef}
              readOnly
              value={data.identificationField}
              className="flex-1 rounded-lg border border-white/65 bg-white/70 px-3 py-2 text-xs text-slate-800 font-mono"
            />
            <button
              type="button"
              onClick={handleCopyIdentField}
              className="btn-secondary shrink-0 text-xs"
            >
              Copy
            </button>
          </div>
        </div>
      ) : null}

      {data.invoiceUrl ? (
        <a
          href={data.invoiceUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="block text-center text-xs text-brand-strong underline"
        >
          View invoice
        </a>
      ) : null}

      {/* Confirm button */}
      {hasBoletoInfo ? (
        <button
          type="button"
          onClick={handleConfirm}
          className="btn-primary w-full"
        >
          I already paid
        </button>
      ) : (
        <p className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-800">
          The bank slip could not be generated right now. Please try again.
        </p>
      )}

      <button
        type="button"
        onClick={onBack}
        className="block w-full text-center text-xs text-slate-500 underline"
      >
        Back
      </button>
    </div>
  );
}

/* ================================================================== */
/*  Asaas Credit Card display (invoice URL redirect)                    */
/* ================================================================== */

function AsaasCreditCardDisplay({
  data,
  purchaseType,
  onBack,
}: {
  data: AsaasResponse;
  purchaseType: string;
  onBack: () => void;
}) {
  const router = useRouter();
  const hasInvoiceUrl = Boolean(data.invoiceUrl);

  function handleConfirm() {
    router.push(`/success?purchase=${purchaseType}&asaas=1`);
  }

  return (
    <div className="space-y-4">
      <div className="rounded-xl border border-blue-200 bg-blue-50 p-4 text-center">
        <p className="text-xs uppercase tracking-[0.14em] text-blue-700">
          Credit card
        </p>
        <p className="mt-1 text-xl font-semibold text-blue-900">
          {data.amountLabel ?? "Valor"}
        </p>
        {data.installmentCount && data.installmentCount > 1 ? (
          <p className="mt-1 text-xs text-blue-600">
            {data.installmentCount}x de R$ {data.installmentValue?.toFixed(2) ?? "—"}
          </p>
        ) : null}
      </div>

      <div className="rounded-xl border border-white/65 bg-white/70 p-4 text-sm text-slate-700">
        <p className="font-semibold text-slate-900">Secure payment through Asaas</p>
        <p className="mt-1 text-xs text-slate-500">
          You will be redirected to Asaas&apos;s secure checkout to enter your card
          details. We do not store full card data.
        </p>
      </div>

      {/* Invoice URL - redirect to Asaas hosted checkout */}
      {data.invoiceUrl ? (
        <a
          href={data.invoiceUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="btn-primary block w-full text-center"
        >
          Pay with credit card
        </a>
      ) : null}

      {/* Confirm for already-submitted scenarios */}
      {hasInvoiceUrl || data.isRecurring ? (
        <div className="space-y-2">
          <button
            type="button"
            onClick={handleConfirm}
            className="btn-secondary w-full"
          >
            I already completed payment
          </button>
        </div>
      ) : (
        <p className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-800">
          The credit-card invoice could not be generated right now.
        </p>
      )}

      {data.invoiceUrl ? (
        <a
          href={data.invoiceUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="block text-center text-xs text-brand-strong underline"
        >
          Open Asaas invoice
        </a>
      ) : null}

      <button
        type="button"
        onClick={onBack}
        className="block w-full text-center text-xs text-slate-500 underline"
      >
        Back
      </button>
    </div>
  );
}

/* ================================================================== */
/*  Main PaymentStep (Asaas mode)                                       */
/* ================================================================== */

function AsaasPaymentFlow(props: PaymentStepProps) {
  const [paymentMethod, setPaymentMethod] = useState<PaymentMethod>("PIX");
  const [installmentCount, setInstallmentCount] = useState<number>(props.purchaseType === "subscription" ? 6 : 1);
  const [asaasData, setAsaasData] = useState<AsaasResponse | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<ErrorState | null>(null);
  const [cpfCnpjInput, setCpfCnpjInput] = useState(props.cpfCnpj?.trim() ?? "");
  const normalizedCpfCnpj = cpfCnpjInput.replace(/\D/g, "");
  const hasValidCpfCnpj = normalizedCpfCnpj.length === 11 || normalizedCpfCnpj.length === 14;

  async function generatePayment() {
    if (!hasValidCpfCnpj) {
      setError({
        message: "Enter a valid CPF (11 digits) or CNPJ (14 digits) to generate payment.",
        retryable: true,
      });
      return;
    }

    setIsLoading(true);
    setError(null);

    const body: Record<string, string> = {
      ...props,
      billingType: paymentMethod,
      cpfCnpj: normalizedCpfCnpj,
    };

      // Add installment fields for parcelado (credit card only, >= 2x)
      if (paymentMethod === "CREDIT_CARD" && installmentCount >= 2) {
        body.installmentCount = String(installmentCount);
      }

    try {
      const response = await fetch("/api/payments/asaas", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });

      const payload = (await response.json()) as AsaasResponse;

      if (!payload.ok) {
        setError({
          message: payload.message ?? "Payment could not be generated. Please try again.",
          retryable: true,
        });
        setIsLoading(false);
        return;
      }

      setAsaasData(payload);
    } catch {
      setError({
        message: "Connection error. Check your internet and try again.",
        retryable: true,
      });
    } finally {
      setIsLoading(false);
    }
  }

  // Display method-specific UI after payment is generated
  if (asaasData && asaasData.provider === "asaas") {
    const billingType = asaasData.billingType ?? paymentMethod;

    if (billingType === "PIX") {
      return (
        <AsaasPixDisplay
          data={asaasData}
          purchaseType={props.purchaseType}
          onBack={() => {
            setAsaasData(null);
            setError(null);
          }}
        />
      );
    }

    if (billingType === "BOLETO") {
      return (
        <AsaasBoletoDisplay
          data={asaasData}
          purchaseType={props.purchaseType}
          onBack={() => {
            setAsaasData(null);
            setError(null);
          }}
        />
      );
    }

    // Default: credit card or subscription
    return (
      <AsaasCreditCardDisplay
        data={asaasData}
        purchaseType={props.purchaseType}
        onBack={() => {
          setAsaasData(null);
          setError(null);
        }}
      />
    );
  }

  return (
    <div className="space-y-4">
      {/* Payment method selector */}
      <AsaasMethodSelector
        selected={paymentMethod}
        onChange={setPaymentMethod}
      />

      {/* Installment option for CREDIT_CARD */}
      {paymentMethod === "CREDIT_CARD" && (
        <div className="space-y-2">
          <p className="text-xs font-semibold uppercase tracking-[0.14em] text-slate-600">
            Installments
          </p>
          <select
            value={installmentCount}
            onChange={(e) => setInstallmentCount(Number(e.target.value))}
            className="w-full rounded-lg border border-white/65 bg-white/70 px-3 py-2 text-sm text-slate-800"
          >
            <option value={1}>One-time payment</option>
            <option value={2}>2x</option>
            <option value={3}>3x</option>
            <option value={4}>4x</option>
            <option value={5}>5x</option>
            <option value={6}>6x</option>
          </select>
        </div>
      )}

      <div className="space-y-2">
        <p className="text-xs font-semibold uppercase tracking-[0.14em] text-slate-600">
          Payer CPF or CNPJ
        </p>
        <input
          type="text"
          inputMode="numeric"
          autoComplete="off"
          placeholder="Enter CPF or CNPJ"
          value={cpfCnpjInput}
          onChange={(e) => {
            setCpfCnpjInput(e.target.value);
            if (error) setError(null);
          }}
          className="w-full rounded-lg border border-white/65 bg-white/70 px-3 py-2 text-sm text-slate-800"
        />
        <p className="text-[11px] text-slate-500">
          Required for PIX, bank slip, and card payments in Asaas.
        </p>
      </div>

      <div className="rounded-xl border border-white/65 bg-white/60 p-3 text-sm text-slate-700">
        <p className="text-xs uppercase tracking-[0.14em] text-slate-600">Summary</p>
        <p className="mt-1 font-semibold text-slate-900">
          {props.purchaseType === "one_time" ? "One-time software access" : "Software subscription"}
        </p>
        <p className="text-xs text-slate-500">
          Payment via {PAYMENT_METHODS.find((m) => m.value === paymentMethod)?.label ?? paymentMethod}
        </p>
      </div>

      {error ? (
        <div className="rounded-xl border border-rose-200 bg-rose-50 p-3">
          <p className="text-sm text-rose-700">{error.message}</p>
          {error.retryable && (
            <div className="mt-3 flex flex-wrap gap-2">
              <button
                type="button"
                onClick={generatePayment}
                disabled={isLoading}
                className="btn-primary text-xs"
              >
                {isLoading ? "Trying again..." : "Try again"}
              </button>
            </div>
          )}
          <a href="/support" className="mt-2 block text-xs text-rose-600 underline">
            Need help? Contact support
          </a>
        </div>
      ) : (
        <button
          type="button"
          onClick={generatePayment}
          disabled={isLoading}
          className="btn-primary w-full disabled:cursor-not-allowed disabled:opacity-60"
        >
          {isLoading
            ? "Preparing checkout..."
            : `Pay with ${PAYMENT_METHODS.find((m) => m.value === paymentMethod)?.label ?? paymentMethod}`}
        </button>
      )}
    </div>
  );
}

/* ================================================================== */
/*  Main PaymentStep                                                   */
/* ================================================================== */

export function PaymentStep(props: PaymentStepProps) {
  const [intent, setIntent] = useState<IntentResponse | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<ErrorState | null>(null);
  const router = useRouter();

  const summaryLabel = useMemo(() => {
    return props.purchaseType === "one_time" ? "One-time software access" : "Software subscription";
  }, [props.purchaseType]);

  /* ---------- Stripe: create intent ---------- */

  async function startStripePayment() {
    setIsLoading(true);
    setError(null);

    try {
      const response = await fetch("/api/payments/intent", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(props),
      });

      const payload = (await response.json()) as IntentResponse;

      if (!payload.ok) {
        setError({
          message: payload.message ?? "Payment could not be started. Please try again.",
          retryable: true,
        });
        setIsLoading(false);
        return;
      }

      if (payload.draft && payload.successUrl) {
        router.push(payload.successUrl);
        return;
      }

      setIntent(payload);
    } catch {
      setError({
        message: "Connection error. Check your internet and try again.",
        retryable: true,
      });
    } finally {
      setIsLoading(false);
    }
  }

  /* ---------- Render ---------- */

  // Asaas flow
  if (PAYMENT_PROVIDER === "asaas") {
    return <AsaasPaymentFlow {...props} />;
  }

  // Stripe not configured
  if (!stripePromise) {
    return (
        <div className="rounded-xl border border-rose-200 bg-rose-50 p-3 text-sm text-rose-700">
          Configure `NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY` to enable card and digital-wallet payments.
        </div>
      );
  }

  return (
    <div className="space-y-4">
      <div className="rounded-xl border border-white/65 bg-white/60 p-3 text-sm text-slate-700">
        <p className="text-xs uppercase tracking-[0.14em] text-slate-600">Summary</p>
        <p className="mt-1 font-semibold text-slate-900">{summaryLabel}</p>
        <p className="text-xs text-slate-600">
          {intent?.amountLabel ?? "Amount will be calculated with coupon in the next step"}
        </p>
      </div>

      {error ? (
        <div className="rounded-xl border border-rose-200 bg-rose-50 p-3">
          <p className="text-sm text-rose-700">{error.message}</p>
          {error.retryable && (
            <div className="mt-3 flex flex-wrap gap-2">
              <button type="button" onClick={startStripePayment} disabled={isLoading} className="btn-primary text-xs">
                {isLoading ? "Trying again..." : "Try again"}
              </button>
            </div>
          )}
          <a href="/support" className="mt-2 block text-xs text-rose-600 underline">
            Need help? Contact support
          </a>
        </div>
      ) : !intent ? (
        <button type="button" onClick={startStripePayment} disabled={isLoading} className="btn-primary w-full">
          {isLoading ? "Preparing checkout..." : "Continue to payment"}
        </button>
      ) : null}

      {intent?.clientSecret && intent.successUrl ? (
        <Elements stripe={stripePromise} options={{ clientSecret: intent.clientSecret }}>
          <ConfirmPaymentForm successUrl={intent.successUrl} />
        </Elements>
      ) : null}
    </div>
  );
}
