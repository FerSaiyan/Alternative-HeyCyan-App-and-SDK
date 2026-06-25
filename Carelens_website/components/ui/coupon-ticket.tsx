"use client";

import type { PointerEvent } from "react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import html2canvas from "html2canvas";

type CouponTicketProps = {
  formId: string;
  title: string;
  firstName?: string;
  fullName: string;
  email: string;
  journeyStartDate: string;
  basePriceLabel: string;
  discountedPriceLabel: string;
  installmentLabel: string;
  couponLabel: string;
  hasDiscount: boolean;
  couponDraft: string;
  couponStatus: "none" | "draft" | "invalid" | "applied";
  appliedCouponCode?: string;
  editHref: string;
  couponFormAction: string;
  couponFormHiddenFields: Array<{ name: string; value: string }>;
  isAffiliateCoupon?: boolean;
};

const INSTAGRAM_PROMO_CODE = "INSTAGRAM10";
const INSTAGRAM_PROMO_PENDING_KEY = "carelens_instagram_promo_pending";
const INSTAGRAM_PROMO_CLAIMED_KEY = "carelens_instagram_promo_claimed";

function vibrate(duration: number) {
  if (typeof navigator !== "undefined" && navigator.vibrate) {
    navigator.vibrate(duration);
  }
}

export function CouponTicket({
  formId,
  title,
  firstName,
  fullName,
  email,
  journeyStartDate,
  basePriceLabel,
  discountedPriceLabel,
  installmentLabel,
  couponLabel,
  hasDiscount,
  couponDraft,
  couponStatus,
  appliedCouponCode,
  editHref,
  couponFormAction,
  couponFormHiddenFields,
  isAffiliateCoupon,
}: CouponTicketProps) {
  const [progress, setProgress] = useState(0);
  const [isDone, setIsDone] = useState(false);
  const [printProgress, setPrintProgress] = useState(0);
  const [isPrinting, setIsPrinting] = useState(true);
  const [isDragging, setIsDragging] = useState(false);
  const [showAssistButton, setShowAssistButton] = useState(false);
  const [showInstagramPromo, setShowInstagramPromo] = useState(false);
  const [isLaunchingInstagram, setIsLaunchingInstagram] = useState(false);
  const [promoError, setPromoError] = useState<string | null>(null);
  const lineRef = useRef<HTMLDivElement | null>(null);
  const ticketCaptureRef = useRef<HTMLElement | null>(null);
  const couponFormRef = useRef<HTMLFormElement | null>(null);
  const hasUserGestureRef = useRef(false);
  const lastPulseAtRef = useRef(0);
  const assistTimeoutRef = useRef<number | null>(null);

  const canSubmit = progress >= 94 || isDone;

  const progressPct = useMemo(() => Math.max(0, Math.min(progress, 100)), [progress]);

  function triggerPrintVibration() {
    const pulses = [30, 50, 30, 50, 30];
    let delay = 0;
    pulses.forEach((p) => {
      setTimeout(() => vibrate(p), delay);
      delay += p + 40;
    });
  }

  const registerGesture = useCallback(() => {
    hasUserGestureRef.current = true;
  }, []);

  useEffect(() => {
    triggerPrintVibration();
    const printDuration = 2800;
    const start = performance.now();
    let rafId = 0;
    let completed = false;

    const failSafe = window.setTimeout(() => {
      if (completed) {
        return;
      }
      completed = true;
      setPrintProgress(100);
      setIsPrinting(false);
    }, 5000);

    const tick = (now: number) => {
      if (completed) {
        return;
      }

      const pct = Math.min(((now - start) / printDuration) * 100, 100);
      setPrintProgress(pct);

      if (pct > 8 && pct < 96 && hasUserGestureRef.current && now - lastPulseAtRef.current > 220) {
        lastPulseAtRef.current = now;
        vibrate(12);
      }

      if (pct >= 100) {
        completed = true;
        setIsPrinting(false);
        window.clearTimeout(failSafe);
        vibrate(60);
        return;
      }

      rafId = window.requestAnimationFrame(tick);
    };

    rafId = window.requestAnimationFrame(tick);

    return () => {
      completed = true;
      window.cancelAnimationFrame(rafId);
      window.clearTimeout(failSafe);
    };
  }, []);

  useEffect(() => {
    const markGesture = () => {
      if (hasUserGestureRef.current) {
        return;
      }

      hasUserGestureRef.current = true;
      if (isPrinting) {
        navigator.vibrate?.([14, 50, 14, 50, 18]);
      }
    };

    window.addEventListener("pointerdown", markGesture);
    window.addEventListener("keydown", markGesture);

    return () => {
      window.removeEventListener("pointerdown", markGesture);
      window.removeEventListener("keydown", markGesture);
    };
  }, [isPrinting]);

  const clearAssistTimer = useCallback(() => {
    if (assistTimeoutRef.current !== null) {
      window.clearTimeout(assistTimeoutRef.current);
      assistTimeoutRef.current = null;
    }
  }, []);

  const startAssistTimer = useCallback(() => {
    clearAssistTimer();
    assistTimeoutRef.current = window.setTimeout(() => {
      setShowAssistButton(true);
    }, 5000);
  }, [clearAssistTimer]);

  const submitPurchase = useCallback(() => {
    registerGesture();
    vibrate(24);
    clearAssistTimer();
    setShowAssistButton(false);

    const formEl = document.getElementById(formId) as HTMLFormElement | null;
    if (!formEl) {
      return;
    }
    const firstInvalid = formEl.querySelector(":invalid") as HTMLElement | null;
    if (firstInvalid) {
      firstInvalid.scrollIntoView({ behavior: "smooth", block: "center" });
      firstInvalid.focus();
      formEl.reportValidity();
      return;
    }
    setIsDone(true);
    formEl.requestSubmit();
  }, [clearAssistTimer, formId, registerGesture]);

  const applyInstagramCoupon = useCallback(() => {
    const formEl = couponFormRef.current;
    if (!formEl) {
      return;
    }
    const couponInput = formEl.querySelector("input[name='couponCode']") as HTMLInputElement | null;
    if (couponInput) {
      couponInput.value = INSTAGRAM_PROMO_CODE;
    }
    formEl.requestSubmit();
  }, []);

  const handleInstagramShare = useCallback(async () => {
    if (isLaunchingInstagram) {
      return;
    }

    registerGesture();
    setPromoError(null);
    setIsLaunchingInstagram(true);

    try {
      const ticketNode = ticketCaptureRef.current;
      if (!ticketNode) {
        throw new Error("ticket_not_found");
      }

      const canvas = await html2canvas(ticketNode, {
        backgroundColor: "#fafaf7",
        scale: Math.min(window.devicePixelRatio || 1, 2),
        useCORS: true,
      });

      const blob = await new Promise<Blob | null>((resolve) => {
        canvas.toBlob((result) => resolve(result), "image/png", 1);
      });

      if (!blob) {
        throw new Error("ticket_export_failed");
      }

      const file = new File([blob], "ticket-carelens.png", { type: "image/png" });
      const shareText = "Compartilhei meu ticket da CareLens @sejacarelens #sejacarelens";
      const canShareFile = typeof navigator !== "undefined" && "canShare" in navigator
        ? navigator.canShare({ files: [file] })
        : false;

      sessionStorage.setItem(INSTAGRAM_PROMO_PENDING_KEY, String(Date.now()));
      setShowInstagramPromo(false);

      if (canShareFile && navigator.share) {
        await navigator.share({
          title: "Meu ticket CareLens",
          text: shareText,
          files: [file],
        });
        sessionStorage.setItem(INSTAGRAM_PROMO_CLAIMED_KEY, "1");
        sessionStorage.removeItem(INSTAGRAM_PROMO_PENDING_KEY);
        applyInstagramCoupon();
      } else {
        const downloadUrl = URL.createObjectURL(blob);
        const downloadLink = document.createElement("a");
        downloadLink.href = downloadUrl;
        downloadLink.download = "ticket-carelens.png";
        document.body.appendChild(downloadLink);
        downloadLink.click();
        downloadLink.remove();
        URL.revokeObjectURL(downloadUrl);

        window.open("https://www.instagram.com/create/story/", "_blank", "noopener,noreferrer");
      }
    } catch {
      setPromoError("Não conseguimos abrir o Instagram agora. Tente novamente em alguns segundos.");
      setShowInstagramPromo(true);
      sessionStorage.removeItem(INSTAGRAM_PROMO_PENDING_KEY);
    } finally {
      setIsLaunchingInstagram(false);
    }
  }, [applyInstagramCoupon, isLaunchingInstagram, registerGesture]);

  const updateFromPointer = useCallback((clientX: number) => {
    const lineEl = lineRef.current;
    if (!lineEl) {
      return;
    }

    const bounds = lineEl.getBoundingClientRect();
    const next = ((clientX - bounds.left) / bounds.width) * 100;
    const clamped = Math.max(0, Math.min(next, 100));
    setProgress(clamped);

    if (clamped >= 94) {
      submitPurchase();
    }
  }, [submitPurchase]);

  function onPointerDown(event: PointerEvent<HTMLElement>) {
    registerGesture();
    vibrate(10);
    startAssistTimer();
    setIsDragging(true);
    updateFromPointer(event.clientX);
  }

  useEffect(() => {
    if (!isDragging) {
      return;
    }

    const handleMove = (event: globalThis.PointerEvent) => {
      updateFromPointer(event.clientX);
    };

    const handleEnd = () => {
      setIsDragging(false);
      if (!canSubmit) {
        setProgress(0);
      }
    };

    window.addEventListener("pointermove", handleMove);
    window.addEventListener("pointerup", handleEnd);
    window.addEventListener("pointercancel", handleEnd);

    return () => {
      window.removeEventListener("pointermove", handleMove);
      window.removeEventListener("pointerup", handleEnd);
      window.removeEventListener("pointercancel", handleEnd);
    };
  }, [canSubmit, isDragging, updateFromPointer]);

  useEffect(() => {
    return () => {
      if (assistTimeoutRef.current !== null) {
        window.clearTimeout(assistTimeoutRef.current);
      }
    };
  }, []);

  useEffect(() => {
    if (appliedCouponCode !== INSTAGRAM_PROMO_CODE) {
      return;
    }
    sessionStorage.setItem(INSTAGRAM_PROMO_CLAIMED_KEY, "1");
    sessionStorage.removeItem(INSTAGRAM_PROMO_PENDING_KEY);
  }, [appliedCouponCode]);

  useEffect(() => {
    if (isPrinting || isDone) {
      return;
    }
    if (appliedCouponCode === INSTAGRAM_PROMO_CODE) {
      return;
    }
    if (isAffiliateCoupon) {
      return;
    }

    const claimed = sessionStorage.getItem(INSTAGRAM_PROMO_CLAIMED_KEY) === "1";
    if (claimed) {
      return;
    }

    const showTimer = window.setTimeout(() => {
      setShowInstagramPromo(true);
    }, 450);

    return () => {
      window.clearTimeout(showTimer);
    };
  }, [appliedCouponCode, isAffiliateCoupon, isDone, isPrinting]);

  useEffect(() => {
    const maybeClaimInstagramCoupon = () => {
      if (document.visibilityState === "hidden") {
        return;
      }
      if (sessionStorage.getItem(INSTAGRAM_PROMO_CLAIMED_KEY) === "1") {
        return;
      }

      const pendingAt = Number(sessionStorage.getItem(INSTAGRAM_PROMO_PENDING_KEY) ?? "0");
      if (!pendingAt || Date.now() - pendingAt < 1200) {
        return;
      }

      sessionStorage.setItem(INSTAGRAM_PROMO_CLAIMED_KEY, "1");
      sessionStorage.removeItem(INSTAGRAM_PROMO_PENDING_KEY);
      applyInstagramCoupon();
    };

    window.addEventListener("focus", maybeClaimInstagramCoupon);
    document.addEventListener("visibilitychange", maybeClaimInstagramCoupon);

    return () => {
      window.removeEventListener("focus", maybeClaimInstagramCoupon);
      document.removeEventListener("visibilitychange", maybeClaimInstagramCoupon);
    };
  }, [applyInstagramCoupon]);

  return (
    <div className="relative flex flex-col items-center pb-2">
      <div
        data-testid="coupon-paper"
        className="relative w-full max-w-md overflow-hidden"
        style={{
          transform: isPrinting ? `translateY(${Math.max(0, 112 - printProgress * 1.12)}px)` : "translateY(0)",
          clipPath: isPrinting
            ? `inset(${Math.max(0, 100 - printProgress)}% 0 0 0 round 1rem)`
            : "inset(0 0 0 0 round 1rem)",
          transition: "transform 0.04s linear, clip-path 0.04s linear",
        }}
      >
        <article
          ref={ticketCaptureRef}
          className={`relative overflow-hidden rounded-2xl border border-white/70 bg-white/85 p-5 shadow-lg backdrop-blur transition-all duration-500 ${
            isDone ? "translate-y-1 opacity-80" : ""
          }`}
        >
          <div className="absolute -left-3 top-1/2 h-6 w-6 -translate-y-1/2 rounded-full border border-white/70 bg-[var(--canvas)]" />
          <div className="absolute -right-3 top-1/2 h-6 w-6 -translate-y-1/2 rounded-full border border-white/70 bg-[var(--canvas)]" />

          <h2 className="mt-2 text-2xl font-semibold tracking-tight text-slate-900">{title}</h2>
          <p className="mt-1 text-sm text-slate-600">
            {firstName ? `Olá, ${firstName}. ` : ""}
            Confirme as suas informações no ticket.
          </p>

          <form ref={couponFormRef} action={couponFormAction} method="get" className="mt-4 space-y-2">
            {couponFormHiddenFields.map((field) => (
              <input key={field.name} type="hidden" name={field.name} value={field.value} />
            ))}
            <label className="block text-xs text-slate-600" htmlFor="coupon-code-input">
              Código do cupom se houver
            </label>
            <div className="flex gap-2">
              <input
                id="coupon-code-input"
                name="couponCode"
                defaultValue={couponDraft}
                placeholder="Digite aqui o código do seu cupom"
                className="w-full rounded-xl border border-white/60 bg-white/80 px-4 py-2.5 text-sm text-slate-800 outline-none ring-[var(--brand)] transition focus:ring-2"
              />
              <button type="submit" className="btn-secondary whitespace-nowrap px-4 py-2.5 text-sm">
                Aplicar
              </button>
            </div>
          </form>

          {couponStatus === "invalid" ? (
            <p className="mt-2 text-xs text-rose-700">Cupom não encontrado. Seguiremos com valor cheio.</p>
          ) : null}
          {couponStatus === "draft" ? (
            <p className="mt-2 text-xs text-slate-600">Cupom preenchido. Clique em Aplicar para confirmar o desconto.</p>
          ) : null}

          <div className="mt-4 rounded-xl border border-indigo-200 bg-indigo-50 p-3">
            {couponLabel ? (
              <p className="text-xs font-mono uppercase tracking-[0.14em] text-indigo-700">{couponLabel}</p>
            ) : null}
            {hasDiscount ? (
              <p className="mt-1 text-sm text-slate-700 line-through">Valor final sem cupom: {basePriceLabel}</p>
            ) : (
              <p className="mt-1 text-sm text-slate-700">Valor das parcelas: {installmentLabel}</p>
            )}
            {hasDiscount ? <p className="mt-1 text-sm text-slate-700">Valor das parcelas: {installmentLabel}</p> : null}
            <p className="mt-1 text-sm text-slate-700">Valor final: {discountedPriceLabel}</p>
          </div>

          <div className="mt-4 rounded-xl border border-white/70 bg-white/65 p-3 text-sm text-slate-800">
            <p><span className="font-semibold">Nome:</span> {fullName || "Não informado"}</p>
            <p className="mt-1"><span className="font-semibold">E-mail:</span> {email || "Não informado"}</p>
            <p className="mt-1"><span className="font-semibold">Data de início da sua jornada:</span> {journeyStartDate}</p>
          </div>

          <div className="mt-5 border-t-2 border-dashed border-slate-300 pt-4">
            <p className="text-xs uppercase tracking-[0.14em] text-slate-600">Deslize para cortar o ticket</p>

            <div
              ref={lineRef}
              onPointerDown={onPointerDown}
              className="mt-3 relative h-11 touch-none rounded-full border border-white/70 bg-white/80 px-1"
            >
              <div
                className="absolute inset-y-1 left-1 rounded-full bg-indigo-100 transition-all"
                style={{ width: `${Math.max(progressPct, 8)}%` }}
              />
              <button
                type="button"
                onPointerDown={onPointerDown}
                className="absolute top-1 h-9 w-9 touch-none rounded-full bg-indigo-600 text-xs font-semibold text-white shadow"
                style={{ left: `calc(${progressPct}% - 18px)` }}
                aria-label="Deslizar para cortar ticket"
              >
                ||
              </button>
            </div>

            {showAssistButton && !canSubmit && !isDone ? (
              <button
                type="button"
                onClick={submitPurchase}
                className="btn-primary mt-3 w-full text-sm"
              >
                Cortar ticket e iniciar jornada
              </button>
            ) : null}
          </div>
        </article>
      </div>

      <div
        data-testid="printer-shell"
        className={`pointer-events-none relative flex items-end justify-center pt-2 transition-all duration-500 ${
          isPrinting ? "translate-y-0 opacity-100" : "translate-y-3 opacity-0"
        }`}
      >
        <div
          className={`transition-transform duration-300 ${
            isPrinting ? "-translate-y-1" : "translate-y-0"
          }`}
        >
          <div className="flex items-end justify-center">
            <div className="relative">
              <div className="flex h-16 w-56 items-end justify-center rounded-t-2xl bg-gradient-to-b from-slate-700 via-slate-800 to-slate-900 shadow-2xl">
                <div className="mb-2 flex gap-1">
                  {isPrinting && (
                    <>
                      <div
                        data-testid="printer-running-indicator"
                        className="h-1.5 w-1.5 animate-pulse rounded-full bg-green-400"
                        style={{ animationDelay: "0ms" }}
                      />
                      <div
                        className="h-1.5 w-1.5 animate-pulse rounded-full bg-green-400"
                        style={{ animationDelay: "150ms" }}
                      />
                      <div
                        className="h-1.5 w-1.5 animate-pulse rounded-full bg-green-400"
                        style={{ animationDelay: "300ms" }}
                      />
                    </>
                  )}
                  {!isPrinting && <div data-testid="printer-idle-indicator" className="h-1.5 w-1.5 rounded-full bg-slate-500" />}
                </div>
              </div>
              <div className="absolute -bottom-1 left-1/2 h-3 w-64 -translate-x-1/2 rounded-b-xl bg-slate-800" />
              <div className="absolute -bottom-2 left-1/2 h-2 w-72 -translate-x-1/2 rounded-b-xl bg-slate-950" />
              <div className="absolute left-1/2 top-2 h-1.5 w-40 -translate-x-1/2 rounded-full bg-black/35" />
            </div>
          </div>
        </div>

        {isPrinting && (
          <div className="absolute left-1/2 top-0 h-2 w-32 -translate-x-1/2 -translate-y-full animate-pulse bg-slate-600/50 blur-sm" />
        )}
      </div>

      {!isPrinting ? (
        <div className="mt-5 flex justify-center">
          <a href={editHref} className="btn-secondary">
            Alterar informações
          </a>
        </div>
      ) : null}

      {showInstagramPromo ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-[#24393f]/45 p-4 backdrop-blur-sm">
          <div className="w-full max-w-md rounded-2xl border border-[#cdbe98]/45 bg-white p-5 shadow-[0_24px_80px_rgba(36,57,63,0.28)]">
            <p className="text-xs font-semibold uppercase tracking-[0.14em] text-[#1f5b66]">Bônus exclusivo</p>
            <h3 className="mt-2 text-xl font-semibold text-[#24393f]">Ganhe 10% de desconto</h3>
            <p className="mt-2 text-sm leading-relaxed text-[#24393f]/88">
              Compartilhe este ticket no Instagram marcando <strong>@sejacarelens</strong> e usando
              <strong> #sejacarelens</strong>. Ao voltar para cá, aplicamos automaticamente o cupom
              <strong> Instagram10</strong>.
            </p>

            {promoError ? (
              <p className="mt-3 rounded-lg bg-rose-50 px-3 py-2 text-xs text-rose-700">{promoError}</p>
            ) : null}

            <div className="mt-4 space-y-2">
              <button
                type="button"
                onClick={handleInstagramShare}
                disabled={isLaunchingInstagram}
                className="btn-primary w-full disabled:cursor-not-allowed disabled:opacity-70"
              >
                {isLaunchingInstagram ? "Preparando ticket..." : "Compartilhar no Instagram"}
              </button>
              <button
                type="button"
                onClick={() => {
                  setPromoError(null);
                  setShowInstagramPromo(false);
                }}
                className="btn-secondary w-full"
              >
                Agora não
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}
