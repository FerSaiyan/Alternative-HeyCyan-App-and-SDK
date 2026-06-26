import { GlassCard } from "@/components/ui/glass-card";
import { SectionHeading } from "@/components/ui/section-heading";

const pathSteps = [
  {
    title: "Create your workspace",
    description:
      "Set up your account, review available plans, and activate the software subscription that matches your usage.",
  },
  {
    title: "Connect compatible smart glasses",
    description:
      "Use the CyanBridge companion app to connect supported smart-glasses workflows and route requests through the relay.",
  },
  {
    title: "Use multiple AI modes",
    description:
      "Send chat, voice, and image requests from one account with clear monthly quotas and plan limits.",
  },
  {
    title: "Scale when you need more",
    description:
      "Upgrade, downgrade, or cancel based on usage without changing your app workflow or account setup.",
  },
];

export function PersonalizedPathSection() {
  return (
    <section id="caminho" className="relative py-section scroll-mt-20 sm:scroll-mt-24">
      <div
        className="pointer-events-none absolute -top-10 right-[5%] h-[350px] w-[350px] rounded-full bg-[#95b6a5]/14 blur-[110px]"
        aria-hidden="true"
      />
      <div className="container-width">
        <SectionHeading
          eyebrow="WORKFLOW"
          title="How CyanBridge fits into your setup"
          className="mb-10"
        />

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          {pathSteps.map((step, index) => (
            <GlassCard key={step.title} variant="minimal" className="p-5">
              <p className="font-mono text-xs uppercase tracking-[0.14em] text-[#1f5b66]/88">
                Step 0{index + 1}
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
