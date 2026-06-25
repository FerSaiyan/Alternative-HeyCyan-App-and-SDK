"use client";

import { useMemo, useRef, useState } from "react";

type CouponCheckoutFieldsProps = {
  fullName: string;
  email: string;
  sex: string;
  heightCm: string;
  weightKg: string;
  healthCondition: string;
};

export function CouponCheckoutFields({
  fullName,
  email,
  sex,
  heightCm,
  weightKg,
  healthCondition,
}: CouponCheckoutFieldsProps) {
  const [isEditing, setIsEditing] = useState(() => !(fullName && email && sex));
  const [formValues, setFormValues] = useState({
    fullName,
    email,
    sex,
    heightCm,
    weightKg,
    healthCondition,
  });

  const detailsRef = useRef<HTMLDivElement | null>(null);
  const fullNameRef = useRef<HTMLInputElement | null>(null);
  const emailRef = useRef<HTMLInputElement | null>(null);
  const sexRef = useRef<HTMLSelectElement | null>(null);

  const summaryLabel = useMemo(() => {
    if (!formValues.fullName || !formValues.email || !formValues.sex) {
      return "Informações incompletas";
    }

    return `${formValues.fullName} · ${formValues.email}`;
  }, [formValues.email, formValues.fullName, formValues.sex]);

  function openEditorAndGuide() {
    setIsEditing(true);

    requestAnimationFrame(() => {
      detailsRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });

      if (!formValues.fullName) {
        fullNameRef.current?.focus();
        return;
      }
      if (!formValues.email) {
        emailRef.current?.focus();
        return;
      }

      sexRef.current?.focus();
    });
  }

  return (
    <div ref={detailsRef} className="space-y-3">
      {!isEditing ? (
        <div className="rounded-xl border border-white/65 bg-white/60 p-3 text-sm text-slate-700">
          <p className="text-xs uppercase tracking-[0.14em] text-slate-600">Dados para pagamento</p>
          <p className="mt-1 text-sm text-slate-900">{summaryLabel}</p>
          <button
            type="button"
            onClick={openEditorAndGuide}
            className="mt-3 text-sm font-semibold text-[var(--brand-strong)] underline underline-offset-4"
          >
            Alterar informações
          </button>

          <input type="hidden" name="fullName" value={formValues.fullName} />
          <input type="hidden" name="email" value={formValues.email} />
          <input type="hidden" name="sex" value={formValues.sex} />
          <input type="hidden" name="heightCm" value={formValues.heightCm} />
          <input type="hidden" name="weightKg" value={formValues.weightKg} />
          <input type="hidden" name="healthCondition" value={formValues.healthCondition} />
        </div>
      ) : (
        <>
          <label className="block text-sm text-slate-700" htmlFor="coupon-fullName">
            Nome completo
          </label>
          <input
            ref={fullNameRef}
            id="coupon-fullName"
            name="fullName"
            required
            value={formValues.fullName}
            onChange={(event) => setFormValues((prev) => ({ ...prev, fullName: event.target.value }))}
            placeholder="Seu nome completo"
            className="w-full rounded-xl border border-white/60 bg-white/70 px-4 py-3 text-sm text-slate-800 outline-none ring-[var(--brand)] transition focus:ring-2"
          />

          <label className="block text-sm text-slate-700" htmlFor="coupon-email">
            E-mail
          </label>
          <input
            ref={emailRef}
            id="coupon-email"
            name="email"
            type="email"
            required
            value={formValues.email}
            onChange={(event) => setFormValues((prev) => ({ ...prev, email: event.target.value }))}
            placeholder="seuemail@exemplo.com"
            className="w-full rounded-xl border border-white/60 bg-white/70 px-4 py-3 text-sm text-slate-800 outline-none ring-[var(--brand)] transition focus:ring-2"
          />

          <label className="block text-sm text-slate-700" htmlFor="coupon-sex">
            Sexo
          </label>
          <select
            ref={sexRef}
            id="coupon-sex"
            name="sex"
            required
            value={formValues.sex}
            onChange={(event) => setFormValues((prev) => ({ ...prev, sex: event.target.value }))}
            className="w-full rounded-xl border border-white/60 bg-white/70 px-4 py-3 text-sm text-slate-800 outline-none ring-[var(--brand)] transition focus:ring-2"
          >
            <option value="" disabled>
              Selecione
            </option>
            <option value="MALE">Masculino</option>
            <option value="FEMALE">Feminino</option>
            <option value="PREFER_NOT_SAY">Prefiro não dizer</option>
          </select>

          <div className="grid gap-3 sm:grid-cols-2">
            <label className="block text-sm text-slate-700" htmlFor="coupon-heightCm">
              Altura (cm) - opcional
              <input
                id="coupon-heightCm"
                name="heightCm"
                inputMode="numeric"
                value={formValues.heightCm}
                onChange={(event) => setFormValues((prev) => ({ ...prev, heightCm: event.target.value }))}
                placeholder="Ex.: 172"
                className="mt-1 w-full rounded-xl border border-white/60 bg-white/70 px-4 py-3 text-sm text-slate-800 outline-none ring-[var(--brand)] transition focus:ring-2"
              />
            </label>
            <label className="block text-sm text-slate-700" htmlFor="coupon-weightKg">
              Peso (kg) - opcional
              <input
                id="coupon-weightKg"
                name="weightKg"
                inputMode="decimal"
                value={formValues.weightKg}
                onChange={(event) => setFormValues((prev) => ({ ...prev, weightKg: event.target.value }))}
                placeholder="Ex.: 84,5"
                className="mt-1 w-full rounded-xl border border-white/60 bg-white/70 px-4 py-3 text-sm text-slate-800 outline-none ring-[var(--brand)] transition focus:ring-2"
              />
            </label>
            <label className="block text-sm text-slate-700 sm:col-span-2" htmlFor="coupon-healthCondition">
              Alguma condição de saúde? Se sim, qual?
              <input
                id="coupon-healthCondition"
                name="healthCondition"
                value={formValues.healthCondition}
                onChange={(event) => setFormValues((prev) => ({ ...prev, healthCondition: event.target.value }))}
                placeholder="Ex.: hipertensão, diabetes, tireoide, etc."
                className="mt-1 w-full rounded-xl border border-white/60 bg-white/70 px-4 py-3 text-sm text-slate-800 outline-none ring-[var(--brand)] transition focus:ring-2"
              />
            </label>
          </div>
        </>
      )}
    </div>
  );
}
