/**
 * EditorialReassuranceSection - Trust cards for CareLens
 *
 * Section 6 of 8:
 * - 4 trust-value cards
 * - Highlights key benefits of CareLens
 */
import { GlassCard } from "@/components/ui/glass-card";
import { SectionHeading } from "@/components/ui/section-heading";

const trustCards = [
  {
    title: "Desenvolvido para idosos",
    description:
      "Interface pensada para a terceira idade: comandos de voz, botões grandes e experiência simplificada. Seu familiar não precisa lidar com tecnologia complexa.",
    icon: "👴",
  },
  {
    title: "IA que aprende",
    description:
      "A inteligência artificial se adapta à rotina de cada idoso. Com o tempo, ela reconhece padrões, antecipa necessidades e oferece um cuidado cada vez mais personalizado.",
    icon: "🧠",
  },
  {
    title: "Família conectada",
    description:
      "Monitoramento em tempo real para tranquilidade da família. Receba notificações sobre atividade, lembretes e alertas diretamente no seu celular.",
    icon: "👨‍👩‍👧‍👦",
  },
  {
    title: "Suporte dedicado",
    description:
      "Equipe de suporte especializada em tecnologia assistiva. Ajudamos na configuração inicial, no uso diário e na resolução de qualquer dúvida.",
    icon: "💬",
  },
];

export function EditorialReassuranceSection() {
  return (
    <section id="confianca" className="relative py-section scroll-mt-20 sm:scroll-mt-24">
      <div className="container-width">
        <SectionHeading
          eyebrow="POR QUE ESCOLHER A CARELENS"
          title="Tecnologia que cuida de quem você ama"
          subtitle="Transparência, segurança e carinho em cada detalhe."
          className="mb-10"
        />

        <div className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-4">
          {trustCards.map((card) => (
            <GlassCard key={card.title} variant="minimal" className="p-6">
              <span className="text-3xl" aria-hidden="true">{card.icon}</span>
              <h3 className="mt-4 text-base font-semibold text-[#24393f]">{card.title}</h3>
              <p className="mt-2 text-sm leading-relaxed text-[#1f5b66]/88">
                {card.description}
              </p>
            </GlassCard>
          ))}
        </div>
      </div>
    </section>
  );
}
