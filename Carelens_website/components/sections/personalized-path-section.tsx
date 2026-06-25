/**
 * PersonalizedPathSection - Jornada do cuidado ao idoso
 *
 * Section 5 of 8:
 * - 4 path cards for elderly care journey
 * - Step-by-step flow
 */
import { GlassCard } from "@/components/ui/glass-card";
import { SectionHeading } from "@/components/ui/section-heading";

const pathSteps = [
  {
    title: "Configuração Inicial",
    description:
      "Personalize a IA para o idoso: cadastre medicamentos, rotina, contatos de emergência e preferências. O familiar configura tudo durante o onboarding.",
  },
  {
    title: "Monitoramento Diário",
    description:
      "A IA acompanha a rotina diária do idoso através dos óculos: horários de refeições, atividades, sono e interações sociais.",
  },
  {
    title: "Alertas Inteligentes",
    description:
      "Detecção de quedas, lembretes de medicamentos, avisos de horários e alertas de comportamento fora do padrão. Tudo em tempo real.",
  },
  {
    title: "Comunicação Familiar",
    description:
      "Conexão fácil entre idoso e familiares. O idoso pode iniciar chamadas com comandos de voz, e a família recebe atualizações no painel.",
  },
];

export function PersonalizedPathSection() {
  return (
    <section id="caminho" className="relative py-section scroll-mt-20 sm:scroll-mt-24">
      {/* Subtle atmospheric depth behind cards */}
      <div className="pointer-events-none absolute -top-10 right-[5%] h-[350px] w-[350px] rounded-full bg-[#95b6a5]/14 blur-[110px]" aria-hidden="true" />
      <div className="container-width">
        <SectionHeading
          eyebrow="JORNADA DO CUIDADO"
          title="Como a CareLens acompanha o dia a dia:"
          className="mb-10"
        />

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          {pathSteps.map((step, index) => (
            <GlassCard key={step.title} variant="minimal" className="p-5">
              <p className="font-mono text-xs uppercase tracking-[0.14em] text-[#1f5b66]/88">
                Etapa 0{index + 1}
              </p>
              <h3 className="mt-2 text-base font-semibold text-[#24393f]">{step.title}</h3>
              <p className="mt-2 text-sm leading-relaxed text-muted">{step.description}</p>
            </GlassCard>
          ))}
        </div>
      </div>
    </section>
  );
}
