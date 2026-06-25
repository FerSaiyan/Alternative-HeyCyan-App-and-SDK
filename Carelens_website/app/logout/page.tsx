import Link from "next/link";
import { SiteHeader } from "@/components/sections/site-header";
import { GlassCard } from "@/components/ui/glass-card";

export default function LogoutPage() {
  return (
    <div className="pb-10">
      <div className="carelens-ambient" />
      <SiteHeader />
      <main className="container-width pt-8 sm:pt-12">
        <section className="mx-auto max-w-xl">
          <GlassCard className="glass-card-strong p-6 sm:p-7">
            <p className="pill-eyebrow">Sessão</p>
            <h1 className="mt-3 text-3xl font-semibold tracking-tight text-slate-900">Encerrar sessão</h1>
            <p className="mt-2 text-sm text-muted">Você sairá da sua conta neste dispositivo.</p>

            <div className="mt-6 flex flex-wrap gap-3">
              <form action="/api/auth/signout" method="post">
                <button type="submit" className="btn-primary">
                  Confirmar saída
                </button>
              </form>
              <Link href="/account" className="btn-secondary">
                Voltar
              </Link>
            </div>
          </GlassCard>
        </section>
      </main>
    </div>
  );
}
