import { SiteFooter } from "@/components/sections/site-footer";
import { SiteHeader } from "@/components/sections/site-header";
import { GlassCard } from "@/components/ui/glass-card";
import { REFUND_RULES } from "@/lib/business";

export default function RefundPage() {
  return (
    <div className="pb-10">
      <div className="carelens-ambient" />
      <SiteHeader />
      <main className="container-width pt-8 sm:pt-12">
        <section className="mx-auto max-w-4xl space-y-5">
          <GlassCard className="glass-card-strong p-6 sm:p-7">
            <p className="pill-eyebrow">Política de reembolso</p>
            <h1 className="mt-3 text-3xl font-semibold tracking-tight text-slate-900">Política de Reembolso CareLens AI</h1>
            <p className="mt-3 text-sm text-muted">
              Garantia de 30 dias para o óculos e condições de cancelamento da assinatura.
            </p>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <ul className="space-y-2 text-sm text-slate-800">
              <li>- Óculos inteligente: R$ {REFUND_RULES.glassesPriceBrl} com garantia de {REFUND_RULES.refundWindowDays} dias de devolução do valor</li>
              <li>- Assinatura mensal: R$ 50,00 — cancelável após {REFUND_RULES.subscriptionMinMonths} meses do primeiro pagamento</li>
              <li>- Reembolso do óculos elegível dentro de {REFUND_RULES.refundWindowDays} dias da compra, desde que o dispositivo esteja em boas condições</li>
              <li>- Assinatura pode ser cancelada a qualquer momento após o período mínimo de {REFUND_RULES.subscriptionMinMonths} meses</li>
              <li>- Em caso de cancelamento dentro do período de garantia, o valor do óculos é reembolsado integralmente</li>
            </ul>
          </GlassCard>
        </section>
      </main>
      <SiteFooter />
    </div>
  );
}
