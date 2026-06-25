import { SiteFooter } from "@/components/sections/site-footer";
import { SiteHeader } from "@/components/sections/site-header";
import { GlassCard } from "@/components/ui/glass-card";
import { SignInAuthSwitcher } from "@/components/auth/signin-auth-switcher";

type SignInPageProps = {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

const reasonMessages: Record<string, string> = {
  role: "Seu perfil atual não tem permissão para acessar esta área.",
  google_config: "Login com Google indisponível no momento.",
  google_state: "Não foi possível validar sua sessão Google. Tente novamente.",
  google_failed: "Não foi possível concluir o login com Google. Tente novamente.",
  google_email_conflict: "Este e-mail já está cadastrado com senha. Entre com e-mail e senha.",
  password_missing_fields: "Preencha nome, e-mail, data de nascimento e senha para criar sua conta.",
  password_weak: "Sua senha precisa ter pelo menos 8 caracteres.",
  password_invalid_birthdate: "Informe uma data de nascimento válida.",
  password_email_exists: "Este e-mail já está em uso. Faça login ou use Entrar com Google se for o seu cadastro.",
  password_signup_failed: "Não foi possível criar sua conta agora. Tente novamente.",
  password_missing_login: "Preencha e-mail e senha para entrar.",
  password_google_account: "Este e-mail foi cadastrado com Google. Use Entrar com Google.",
  password_not_configured: "Esta conta não possui senha. Use outro método de acesso.",
  password_invalid_credentials: "E-mail ou senha inválidos.",
  verify: "Não foi possível validar o link mágico.",
};

const signInReasons = new Set([
  "password_missing_login",
  "password_google_account",
  "password_not_configured",
  "password_invalid_credentials",
  "google_email_conflict",
]);

const signUpReasons = new Set([
  "password_missing_fields",
  "password_weak",
  "password_invalid_birthdate",
  "password_email_exists",
  "password_signup_failed",
]);

export default async function SignInPage({ searchParams }: SignInPageProps) {
  const params = await searchParams;
  const nextParamRaw = String(params.next ?? "/sub_onboarding").trim();
  const nextParam = nextParamRaw.startsWith("/") ? nextParamRaw : "/sub_onboarding";
  const authRequired = String(params.auth ?? "") === "required";
  const reason = String(params.reason ?? "").trim();
  const reasonMessage = reason ? reasonMessages[reason] : "";
  const modeParam = String(params.mode ?? "").trim().toLowerCase();

  const initialMode = modeParam === "signin" || modeParam === "login"
    ? "signin"
    : modeParam === "signup"
      ? "signup"
      : signInReasons.has(reason)
        ? "signin"
        : signUpReasons.has(reason)
          ? "signup"
          : "signin";

  return (
    <div className="pb-10">
      <div className="carelens-ambient" />
      <SiteHeader />
      <main className="container-width pt-8 sm:pt-12">
        <section className="mx-auto max-w-3xl space-y-5">
          <GlassCard className="glass-card-strong p-6 sm:p-7">
            <p className="pill-eyebrow">Acesso</p>
            <h1 className="mt-3 text-3xl font-semibold tracking-tight text-slate-900">Entrar ou criar conta</h1>
            <p className="mt-2 text-sm text-muted">
              Antes da triagem, confirme seu acesso com Google ou com e-mail, senha e data de nascimento.
            </p>
            <p className="mt-2 text-xs text-slate-500">
              Médicos e farmácias: utilizem seu nome de usuário para entrar.
            </p>

            {authRequired ? (
              <p className="mt-4 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
                Faça login para continuar.
              </p>
            ) : null}

            {reasonMessage ? (
              <p className="mt-3 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-900">{reasonMessage}</p>
            ) : null}

            <a
              href={`/api/auth/google/start?next=${encodeURIComponent(nextParam)}`}
              className="btn-secondary mt-5 flex w-full items-center justify-center gap-2"
            >
              <svg viewBox="0 0 24 24" className="h-4 w-4" aria-hidden="true">
                <path
                  fill="currentColor"
                  d="M12.24 10.285v3.817h5.445c-.24 1.285-.96 2.37-2.04 3.098l3.298 2.56c1.92-1.77 3.03-4.38 3.03-7.5 0-.728-.067-1.427-.19-2.098h-9.543z"
                />
                <path
                  fill="currentColor"
                  d="M12 22c2.7 0 4.965-.893 6.62-2.422l-3.298-2.56c-.915.615-2.085.982-3.322.982-2.552 0-4.714-1.725-5.488-4.042H3.102v2.54A9.997 9.997 0 0012 22z"
                />
                <path
                  fill="currentColor"
                  d="M6.512 13.958A5.996 5.996 0 016.2 12c0-.68.117-1.34.312-1.958V7.502H3.102A9.997 9.997 0 002 12c0 1.61.385 3.13 1.102 4.498l3.41-2.54z"
                />
                <path
                  fill="currentColor"
                  d="M12 6.76c1.47 0 2.79.506 3.83 1.5l2.87-2.87C16.96 3.77 14.7 3 12 3A9.997 9.997 0 003.102 7.502l3.41 2.54C7.286 8.485 9.448 6.76 12 6.76z"
                />
              </svg>
              Entrar com Google
            </a>

            <SignInAuthSwitcher nextPath={nextParam} initialMode={initialMode} />

          </GlassCard>
        </section>
      </main>
      <SiteFooter />
    </div>
  );
}
