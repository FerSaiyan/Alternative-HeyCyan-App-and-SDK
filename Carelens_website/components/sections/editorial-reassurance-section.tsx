import { GlassCard } from "@/components/ui/glass-card";
import { SectionHeading } from "@/components/ui/section-heading";

const trustCards = [
  {
    title: "Software only",
    description:
      "CyanBridge sells AI software subscriptions on this domain. Supported smart-glasses hardware is not sold on this website.",
    icon: "💻",
  },
  {
    title: "Built for smart-glasses workflows",
    description:
      "Use one managed subscription to route compatible smart-glasses requests to chat, voice, and image AI models.",
    icon: "🧠",
  },
  {
    title: "Transparent quotas",
    description:
      "Each plan includes clear monthly token allowances so billing stays predictable as usage grows.",
    icon: "📊",
  },
  {
    title: "No medical claims",
    description:
      "CyanBridge is not a medical service and should not be used to make healthcare or other high-stakes personal decisions.",
    icon: "💬",
  },
];

export function EditorialReassuranceSection() {
  return (
    <section id="confianca" className="relative py-section scroll-mt-20 sm:scroll-mt-24">
      <div className="container-width">
        <SectionHeading
          eyebrow="WHY CYANBRIDGE"
          title="A clearer way to sell AI access for smart glasses"
          subtitle="Software subscription messaging that is transparent, specific, and easier for payment review teams to understand."
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
