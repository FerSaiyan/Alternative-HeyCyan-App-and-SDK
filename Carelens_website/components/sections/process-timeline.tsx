import { GlassCard } from "@/components/ui/glass-card";

const CARELENS_PROCESS_STEPS = [
  {
    title: "Escolha os óculos",
    description:
      "Selecione a cor (branco ou preto) e faça a compra. Receba em casa em até 10 dias.",
  },
  {
    title: "Configure a IA",
    description:
      "Crie o perfil de saúde do idoso, cadastre medicamentos, rotina e contatos de emergência. A IA aprende e se adapta.",
  },
  {
    title: "Monitore à distância",
    description:
      "Acesse o painel familiar para acompanhar atividade, lembretes, interações da IA e alertas em tempo real.",
  },
  {
    title: "Suporte contínuo",
    description:
      "Receba atualizações constantes da IA, novos recursos e suporte técnico dedicado sempre que precisar.",
  },
];

export function ProcessTimeline() {
  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
      {CARELENS_PROCESS_STEPS.map((step, index) => {
        return (
          <GlassCard key={step.title} variant="minimal" className="p-5">
            <p className="font-mono text-xs uppercase tracking-[0.14em] text-[#1f5b66]/88">Etapa 0{index + 1}</p>
            <h3 className="mt-2 text-base font-semibold text-[#24393f]">{step.title}</h3>
            <p className="mt-2 text-sm leading-relaxed text-muted">{step.description}</p>
          </GlassCard>
        );
      })}
    </div>
  );
}
