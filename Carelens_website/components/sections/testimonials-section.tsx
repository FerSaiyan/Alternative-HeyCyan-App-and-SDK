import { SectionHeading } from "@/components/ui/section-heading";
import { GlassCard } from "@/components/ui/glass-card";

const useCases = [
  {
    name: "Creators",
    text: "Use smart glasses as a hands-free way to capture context and send prompts without switching devices.",
    role: "POV capture, quick questions, and voice interactions",
  },
  {
    name: "Field teams",
    text: "Route on-the-go audio, images, and short queries through one managed subscription with predictable limits.",
    role: "Operations, inspections, and mobile workflows",
  },
  {
    name: "Developers",
    text: "Prototype smart-glasses AI experiences faster without having to manage separate billing for every model provider.",
    role: "Companion apps, integrations, and experiments",
  },
];

export function TestimonialsSection() {
  return (
    <section id="depoimentos" className="relative py-section scroll-mt-20 sm:scroll-mt-24">
      <div className="container-width">
        <SectionHeading
          eyebrow="USE CASES"
          title="Who CyanBridge is built for"
          subtitle="Common software workflows for smart-glasses AI access."
          className="mb-12"
        />
        <div className="grid grid-cols-1 gap-6 md:grid-cols-3">
          {useCases.map((item) => (
            <GlassCard key={item.name} variant="default" className="p-6">
              <p className="text-sm leading-relaxed text-[#24393f]/90">{item.text}</p>
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
