"use client";

import { useMemo, useState } from "react";

type AuthMode = "signup" | "signin";

type SignInAuthSwitcherProps = {
  nextPath: string;
  initialMode?: AuthMode;
};

export function SignInAuthSwitcher({ nextPath, initialMode = "signup" }: SignInAuthSwitcherProps) {
  const safeInitialMode = initialMode === "signin" ? "signin" : "signup";
  const [mode, setMode] = useState<AuthMode>(safeInitialMode);
  const maxBirthDate = useMemo(() => new Date().toISOString().slice(0, 10), []);
  const isSignInMode = mode === "signin";

  return (
    <div className="mt-6">
      {isSignInMode ? (
        <form className="space-y-3 rounded-xl border border-white/60 bg-white/60 p-4" action="/api/auth/password/signin" method="post">
          <p className="text-sm font-semibold text-slate-900">Entrar com e-mail e senha</p>
          <input type="hidden" name="next" value={nextPath} />
          <label className="block text-sm text-slate-700" htmlFor="signin-identifier">
            E-mail ou nome de usuário
          </label>
          <input
            id="signin-identifier"
            name="identifier"
            type="text"
            required
            placeholder="seu@email.com ou nome de usuário"
            autoComplete="username"
            className="w-full rounded-xl border border-white/60 bg-white/70 px-4 py-3 text-sm outline-none ring-[var(--brand)] transition focus:ring-2"
          />
          <label className="block text-sm text-slate-700" htmlFor="signin-password">
            Senha
          </label>
          <input
            id="signin-password"
            name="password"
            type="password"
            required
            minLength={8}
            placeholder="Sua senha"
            autoComplete="current-password"
            className="w-full rounded-xl border border-white/60 bg-white/70 px-4 py-3 text-sm outline-none ring-[var(--brand)] transition focus:ring-2"
          />
          <button type="submit" className="btn-primary mt-2 w-full">
            Entrar
          </button>
          <button type="button" onClick={() => setMode("signup")} className="btn-secondary w-full">
            Criar conta
          </button>
        </form>
      ) : (
        <form className="space-y-3 rounded-xl border border-white/60 bg-white/60 p-4" action="/api/auth/password/signup" method="post">
          <p className="text-sm font-semibold text-slate-900">Criar conta com e-mail e senha</p>
          <input type="hidden" name="next" value={nextPath} />
          <label className="block text-sm text-slate-700" htmlFor="signup-fullName">
            Nome completo
          </label>
          <input
            id="signup-fullName"
            name="fullName"
            required
            placeholder="Como você prefere ser chamado(a)"
            autoComplete="name"
            className="w-full rounded-xl border border-white/60 bg-white/70 px-4 py-3 text-sm outline-none ring-[var(--brand)] transition focus:ring-2"
          />
          <label className="block text-sm text-slate-700" htmlFor="signup-email">
            E-mail
          </label>
          <input
            id="signup-email"
            name="email"
            type="email"
            required
            placeholder="seuemail@exemplo.com"
            autoComplete="email"
            className="w-full rounded-xl border border-white/60 bg-white/70 px-4 py-3 text-sm outline-none ring-[var(--brand)] transition focus:ring-2"
          />
          <label className="block text-sm text-slate-700" htmlFor="signup-dateOfBirth">
            Data de nascimento
          </label>
          <input
            id="signup-dateOfBirth"
            name="dateOfBirth"
            type="date"
            required
            max={maxBirthDate}
            autoComplete="bday"
            className="w-full rounded-xl border border-white/60 bg-white/70 px-4 py-3 text-sm outline-none ring-[var(--brand)] transition focus:ring-2"
          />
          <label className="block text-sm text-slate-700" htmlFor="signup-password">
            Senha (min. 8 caracteres)
          </label>
          <input
            id="signup-password"
            name="password"
            type="password"
            minLength={8}
            required
            placeholder="Crie uma senha"
            autoComplete="new-password"
            className="w-full rounded-xl border border-white/60 bg-white/70 px-4 py-3 text-sm outline-none ring-[var(--brand)] transition focus:ring-2"
          />
          <button type="submit" className="btn-primary mt-2 w-full">
            Criar conta e continuar
          </button>
          <button type="button" onClick={() => setMode("signin")} className="btn-secondary w-full">
            Já tenho uma conta
          </button>
        </form>
      )}
    </div>
  );
}
