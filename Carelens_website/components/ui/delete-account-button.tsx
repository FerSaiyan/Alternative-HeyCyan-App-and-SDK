"use client";

import { useState, useCallback, useRef } from "react";

/**
 * DeleteAccountButton — "Excluir minha conta e dados" UI with two-step confirmation.
 *
 * 1. First click: open confirmation dialog.
 * 2. User types "EXCLUIR" in a confirmation field.
 * 3. Submit sends POST /api/account/delete.
 * 4. On success: redirect to home after cookie clear.
 */
export function DeleteAccountButton() {
  const [step, setStep] = useState<"idle" | "confirm" | "submitting" | "error" | "done">("idle");
  const [confirmationText, setConfirmationText] = useState("");
  const [errorMsg, setErrorMsg] = useState("");
  const dialogRef = useRef<HTMLDivElement>(null);

  const handleStart = useCallback(() => {
    setStep("confirm");
    setConfirmationText("");
    setErrorMsg("");
  }, []);

  const handleCancel = useCallback(() => {
    setStep("idle");
    setConfirmationText("");
    setErrorMsg("");
  }, []);

  const handleSubmit = useCallback(async () => {
    if (confirmationText !== "EXCLUIR") {
      setErrorMsg('Digite "EXCLUIR" para confirmar.');
      return;
    }

    setStep("submitting");
    setErrorMsg("");

    try {
      const res = await fetch("/api/account/delete", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ confirmation: "EXCLUIR" }),
      });

      const data = await res.json();

      if (!res.ok || !data.ok) {
        setErrorMsg(data.message || "Erro ao excluir conta. Tente novamente.");
        setStep("error");
        return;
      }

      setStep("done");

      // Redirect after brief delay (cookies are cleared by server response)
      setTimeout(() => {
        window.location.href = data.redirectTo || "/";
      }, 2000);
    } catch {
      setErrorMsg("Erro de conexão. Verifique sua internet e tente novamente.");
      setStep("error");
    }
  }, [confirmationText]);

  const isSubmitting = step === "submitting" || step === "done";

  if (step === "done") {
    return (
      <div className="rounded-xl border border-green-200 bg-green-50 p-4 text-sm text-green-800">
        Conta excluída com sucesso. Redirecionando...
      </div>
    );
  }

  return (
    <div>
      {step === "idle" && (
        <button
          onClick={handleStart}
          className="rounded-full border border-red-300 bg-white px-4 py-2 text-xs font-medium text-red-600 transition-colors hover:bg-red-50 focus:outline-none focus:ring-2 focus:ring-red-300"
        >
          Excluir minha conta e dados
        </button>
      )}

      {(step === "confirm" || step === "error") && (
        <div
          ref={dialogRef}
          role="dialog"
          aria-modal="true"
          aria-labelledby="delete-account-title"
          className="rounded-xl border border-red-200 bg-red-50 p-4 space-y-3"
        >
          <h3 id="delete-account-title" className="text-sm font-semibold text-red-800">
            Excluir conta e dados pessoais
          </h3>
          <p className="text-xs text-red-700 leading-relaxed">
            Esta ação <strong>não pode ser desfeita</strong>. Todos os seus
            dados pessoais, perfil, assinaturas, pagamentos e agendamentos
            serão removidos da base ativa, respeitadas eventuais obrigações
            legais de retenção.
          </p>
          <p className="text-xs text-red-700">
            Para confirmar, digite <strong>EXCLUIR</strong> no campo abaixo:
          </p>
          <input
            type="text"
            value={confirmationText}
            onChange={(e) => {
              setConfirmationText(e.target.value);
              setErrorMsg("");
            }}
            placeholder="Digite EXCLUIR"
            className="w-full rounded-lg border border-red-300 bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-red-400"
            disabled={isSubmitting}
            autoFocus={step === "confirm"}
          />
          {errorMsg && (
            <p className="text-xs text-red-600 font-medium">{errorMsg}</p>
          )}
          <div className="flex flex-wrap gap-2">
            <button
              onClick={handleSubmit}
              disabled={isSubmitting}
              className="rounded-full bg-red-600 px-5 py-2 text-xs font-medium text-white transition-colors hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-red-400 disabled:opacity-50"
            >
              {isSubmitting ? "Excluindo..." : "Confirmar exclusão"}
            </button>
            <button
              onClick={handleCancel}
              disabled={isSubmitting}
              className="rounded-full border border-slate-300 bg-white px-5 py-2 text-xs font-medium text-slate-700 transition-colors hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-slate-300 disabled:opacity-50"
            >
              Cancelar
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
