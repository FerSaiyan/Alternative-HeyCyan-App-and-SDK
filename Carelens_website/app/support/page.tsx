import Link from "next/link";
import { SiteFooter } from "@/components/sections/site-footer";
import { SiteHeader } from "@/components/sections/site-header";
import { GlassCard } from "@/components/ui/glass-card";

export default function SupportPage() {
  return (
    <div className="pb-10">
      <div className="carelens-ambient" />
      <SiteHeader />
      <main className="container-width pt-8 sm:pt-12">
        <section className="mx-auto grid max-w-4xl gap-5 lg:grid-cols-[1fr,1fr]">
          <GlassCard className="glass-card-strong p-6 sm:p-7">
            <p className="pill-eyebrow">Suporte CareLens</p>
            <h1 className="mt-3 text-3xl font-semibold tracking-tight text-slate-900">Precisa de ajuda agora?</h1>
            <p className="mt-3 text-sm leading-relaxed text-muted">
              Fale com a gente sobre compra, assinatura, suporte técnico dos óculos e reembolso.
            </p>
            <div className="mt-6 space-y-2 text-sm text-slate-800">
              <p>- WhatsApp: +55 11 99999-0000</p>
              <p>- E-mail: contato@carelens.com.br</p>
              <p>- Horário: segunda a sexta, 8h às 18h</p>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-lg font-semibold text-slate-900">Atalhos rápidos</h2>
            <div className="mt-4 grid gap-3">
              <Link href="/account" className="btn-secondary text-center">
                Meu painel
              </Link>
              <Link href="/refund" className="btn-secondary text-center">
                Política de reembolso
              </Link>
              <Link href="/support" className="btn-secondary text-center">
                Falar com suporte
              </Link>
            </div>
          </GlassCard>
        </section>
      </main>
      <SiteFooter />
    </div>
  );
}
