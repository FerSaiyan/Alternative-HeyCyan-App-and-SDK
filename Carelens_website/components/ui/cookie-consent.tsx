"use client";

import { useState, useCallback, useSyncExternalStore } from "react";

/* ------------------------------------------------------------------ */
/*  Constants                                                          */
/* ------------------------------------------------------------------ */

const COOKIE_CONSENT_KEY = "carelens_cookie_consent";
const CONSENT_ACCEPTED = "accepted";
const CONSENT_REJECTED = "rejected";
type ConsentValue = typeof CONSENT_ACCEPTED | typeof CONSENT_REJECTED | null;

const consentListeners = new Set<() => void>();

function subscribeConsentStore(listener: () => void): () => void {
  consentListeners.add(listener);
  return () => {
    consentListeners.delete(listener);
  };
}

function emitConsentChange(): void {
  consentListeners.forEach((listener) => listener());
}

function readConsentSnapshot(): ConsentValue {
  if (typeof window === "undefined") return null;
  try {
    const saved = localStorage.getItem(COOKIE_CONSENT_KEY);
    if (saved === CONSENT_ACCEPTED || saved === CONSENT_REJECTED) {
      return saved;
    }
  } catch {
    // localStorage unavailable (disabled or private mode)
  }
  return null;
}

/** Essential cookies used by the platform (no consent required). */
const ESSENTIAL_COOKIES = [
  { name: "carelens_user_id", purpose: "Identificador de sessão do usuário autenticado" },
  { name: "carelens_booking_id", purpose: "Identificador de reserva de agendamento em andamento" },
];

/* ------------------------------------------------------------------ */
/*  Component                                                          */
/* ------------------------------------------------------------------ */

/**
 * CookieConsent – LGPD-compliant consent banner.
 *
 * - Blocks non-essential cookies until user opts in.
 * - Shows explicit Accept / Reject buttons.
 * - Persists preference in localStorage.
 * - Essential (session) cookies are always active.
 * - Uses useSyncExternalStore to read consent from localStorage after hydration,
 *   and renders nothing during SSR to avoid the banner flashing on
 *   page loads where consent was already given.
 */
export function CookieConsent() {
  const [showSettings, setShowSettings] = useState(false);
  const hydrated = useSyncExternalStore(
    () => () => {},
    () => true,
    () => false,
  );
  const consent = useSyncExternalStore(
    subscribeConsentStore,
    readConsentSnapshot,
    () => null,
  );

  const handleAccept = useCallback(() => {
    try {
      localStorage.setItem(COOKIE_CONSENT_KEY, CONSENT_ACCEPTED);
    } catch {
      // localStorage unavailable — non-essential cookies still blocked
    }
    emitConsentChange();
  }, []);

  const handleReject = useCallback(() => {
    try {
      localStorage.setItem(COOKIE_CONSENT_KEY, CONSENT_REJECTED);
    } catch {
      // noop
    }
    emitConsentChange();
  }, []);

  const handleRevoke = useCallback(() => {
    try {
      localStorage.removeItem(COOKIE_CONSENT_KEY);
    } catch {
      // noop
    }
    emitConsentChange();
    setShowSettings(false);
  }, []);

  // During SSR / before hydration, render nothing to avoid the banner
  // flashing on page loads where consent was already given.
  if (!hydrated) return null;
  // Banner not needed once user has decided (and settings not open)
  if (consent !== null && !showSettings) {
    return null;
  }

  return (
    <div
      className="fixed bottom-0 left-0 right-0 z-[100] p-4"
      role="dialog"
      aria-label="Aviso de cookies"
    >
      <div
        className={`mx-auto max-w-2xl rounded-2xl border border-white/70 bg-white/95 p-5 shadow-xl backdrop-blur-md transition-all ${
          showSettings ? "scale-100 opacity-100" : "scale-100 opacity-100"
        }`}
      >
        {showSettings ? (
          /* --- Settings panel --- */
          <div>
            <h3 className="text-base font-semibold text-slate-900">
              Preferências de Cookies
            </h3>
            <p className="mt-1 text-xs text-slate-600">
              Gerencie suas preferências de cookies. Cookies essenciais são
              sempre necessários para o funcionamento da plataforma.
            </p>

            <div className="mt-4 space-y-3">
              {/* Essential — always on */}
              <div className="rounded-lg border border-[#cdbe98]/50 bg-white/70 p-3">
                <div className="flex items-center justify-between">
                  <div>
                    <p className="text-sm font-medium text-slate-900">
                      Cookies Essenciais
                    </p>
                    <p className="text-xs text-slate-500">
                      Sempre ativos — necessários para o funcionamento da plataforma.
                    </p>
                  </div>
                  <span className="rounded bg-[#95b6a5]/30 px-2 py-0.5 text-xs font-medium text-slate-700">
                    Sempre ativos
                  </span>
                </div>
                <details className="mt-2">
                  <summary className="cursor-pointer text-xs text-slate-500 hover:text-slate-700">
                    Ver cookies essenciais
                  </summary>
                  <ul className="mt-1 space-y-1 pl-2">
                    {ESSENTIAL_COOKIES.map((c) => (
                      <li key={c.name} className="text-xs text-slate-600">
                        <code className="rounded bg-slate-100 px-1">{c.name}</code>
                        {" — "}
                        {c.purpose}
                      </li>
                    ))}
                  </ul>
                </details>
              </div>

              {/* Non-essential — controlled by consent */}
              <div className="rounded-lg border border-[#cdbe98]/50 bg-white/70 p-3">
                <div className="flex items-center justify-between">
                  <div>
                    <p className="text-sm font-medium text-slate-900">
                      Cookies Não Essenciais
                    </p>
                    <p className="text-xs text-slate-500">
                      Analytics, preferências e funcionalidades opcionais.
                    </p>
                  </div>
                  <span
                    className={`rounded px-2 py-0.5 text-xs font-medium ${
                      consent === CONSENT_ACCEPTED
                        ? "bg-green-100 text-green-700"
                        : "bg-slate-100 text-slate-500"
                    }`}
                  >
                    {consent === CONSENT_ACCEPTED ? "Ativos" : "Bloqueados"}
                  </span>
                </div>
                <p className="mt-1 text-xs text-slate-500">
                  {consent === CONSENT_ACCEPTED
                    ? "Você aceitou cookies não essenciais. Eles podem ser usados para analytics e melhorias na experiência."
                    : "Cookies não essenciais estão bloqueados até que você aceite."}
                </p>
              </div>
            </div>

            <div className="mt-4 flex flex-wrap gap-2">
              <button
                onClick={() => setShowSettings(false)}
                className="rounded-full border border-[#cdbe98]/60 bg-white px-4 py-2 text-xs font-medium text-slate-700 transition-colors hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-brand/50"
              >
                Fechar
              </button>
              {consent !== CONSENT_ACCEPTED ? (
                <button
                  onClick={() => { handleAccept(); setShowSettings(false); }}
                  className="rounded-full bg-brand px-4 py-2 text-xs font-medium text-white transition-colors hover:opacity-90 focus:outline-none focus:ring-2 focus:ring-brand/50"
                >
                  Aceitar cookies
                </button>
              ) : (
                <button
                  onClick={handleRevoke}
                  className="rounded-full border border-red-300 bg-white px-4 py-2 text-xs font-medium text-red-600 transition-colors hover:bg-red-50 focus:outline-none focus:ring-2 focus:ring-red-300"
                >
                  Revogar consentimento
                </button>
              )}
            </div>
          </div>
        ) : (
          /* --- Initial banner --- */
          <div>
            <div className="flex items-start gap-3">
              <div className="flex-1">
                <h3 className="text-sm font-semibold text-slate-900">
                  Aviso de Cookies
                </h3>
                <p className="mt-1 text-xs leading-relaxed text-slate-600">
                  Utilizamos cookies essenciais para o funcionamento da
                  plataforma. Outros cookies (analytics, preferências) são
                  opcionais. Você pode aceitar ou rejeitar cookies não
                  essenciais. Consulte nossa{" "}
                  <a
                    href="/privacidade"
                    className="text-brand underline hover:no-underline"
                  >
                    Política de Privacidade
                  </a>{" "}
                  para mais informações.
                </p>
              </div>
            </div>

            <div className="mt-4 flex flex-wrap items-center gap-2">
              <button
                onClick={handleAccept}
                className="rounded-full bg-brand px-5 py-2 text-xs font-medium text-white transition-colors hover:opacity-90 focus:outline-none focus:ring-2 focus:ring-brand/50"
              >
                Aceitar todos
              </button>
              <button
                onClick={handleReject}
                className="rounded-full border border-[#cdbe98]/60 bg-white px-5 py-2 text-xs font-medium text-slate-700 transition-colors hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-brand/50"
              >
                Rejeitar não essenciais
              </button>
              <button
                onClick={() => setShowSettings(true)}
                className="rounded-full px-3 py-2 text-xs font-medium text-slate-500 transition-colors hover:text-slate-700 focus:outline-none focus:ring-2 focus:ring-brand/50"
              >
                Configurações
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * Check if the user has accepted non-essential cookies.
 * Can be used by other client components to gate optional features.
 */
export function hasCookieConsent(): boolean {
  if (typeof window === "undefined") return false;
  try {
    return localStorage.getItem(COOKIE_CONSENT_KEY) === CONSENT_ACCEPTED;
  } catch {
    return false;
  }
}
