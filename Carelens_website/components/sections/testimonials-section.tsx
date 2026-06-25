/**
 * TestimonialsSection - Family testimonials for CareLens
 *
 * Section 7 of 8:
 * - Family member testimonials
 * - Social proof for elderly care
 */
import { SectionHeading } from "@/components/ui/section-heading";
import { GlassCard } from "@/components/ui/glass-card";

const testimonials = [
  {
    name: "Maria S.",
    text: "A CareLens mudou a vida da minha mãe. Os lembretes de medicamentos são incríveis.",
    role: "Filha de Dona Elza, 78 anos",
  },
  {
    name: "João P.",
    text: "Agora consigo acompanhar meu pai à distância. A IA alerta sobre qualquer anomalia.",
    role: "Filho de Seu Carlos, 82 anos",
  },
  {
    name: "Ana R.",
    text: "Meu avô adora usar os óculos. Ele se sente mais seguro e conectado.",
    role: "Neta de Seu Miguel, 85 anos",
  },
];

export function TestimonialsSection() {
  return (
    <section id="depoimentos" className="relative py-section scroll-mt-20 sm:scroll-mt-24">
      <div className="container-width">
        <SectionHeading
          eyebrow="DEPOIMENTOS"
          title="O que as famílias estão dizendo"
          subtitle="Histórias reais de quem já transformou o cuidado com a CareLens."
          className="mb-12"
        />
        <div className="grid grid-cols-1 gap-6 md:grid-cols-3">
          {testimonials.map((item) => (
            <GlassCard key={item.name} variant="default" className="p-6">
              <blockquote className="text-sm italic leading-relaxed text-[#24393f]/90">
                &ldquo;{item.text}&rdquo;
              </blockquote>
              <div className="mt-4 border-t border-[#cdbe98]/35 pt-4">
                <p className="text-sm font-semibold text-[#24393f]">{item.name}</p>
                <p className="text-xs text-[#1f5b66]/70">{item.role}</p>
              </div>
            </GlassCard>
          ))}
        </div>
      </div>
    </section>
  );
}
