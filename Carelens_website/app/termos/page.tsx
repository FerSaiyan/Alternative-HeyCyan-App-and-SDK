import { SiteFooter } from "@/components/sections/site-footer";
import { SiteHeader } from "@/components/sections/site-header";
import { GlassCard } from "@/components/ui/glass-card";

export default function TermsPage() {
  return (
    <div className="pb-10">
      <div className="carelens-ambient" />
      <SiteHeader />
      <main className="container-width pt-8 sm:pt-12">
        <section className="mx-auto max-w-4xl space-y-5">
          <GlassCard className="glass-card-strong p-6 sm:p-7">
            <p className="pill-eyebrow">Termos de uso</p>
            <h1 className="mt-3 text-3xl font-semibold tracking-tight text-slate-900">Termos em rascunho para validação jurídica</h1>
            <p className="mt-3 text-sm text-muted">
              Este conteúdo é preliminar e será revisado pela assessoria jurídica antes da publicação oficial.
            </p>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <ol className="list-decimal space-y-3 pl-5 text-sm text-slate-800">
              <li>A CareLens AI fornece tecnologia assistiva por meio de óculos inteligentes e não substitui cuidados médicos ou supervisão profissional.</li>
              <li>A assinatura mensal de R$ 50,00 habilita os serviços de IA, incluindo assistência por voz, lembretes e monitoramento.</li>
              <li>O óculos inteligente tem garantia de 30 dias para devolução. A assinatura pode ser cancelada após 3 meses do primeiro pagamento.</li>
              <li>Conflitos de pagamento e reembolso seguem a política de reembolso vigente.</li>
            </ol>
          </GlassCard>
        </section>
      </main>
      <SiteFooter />
    </div>
  );
}
