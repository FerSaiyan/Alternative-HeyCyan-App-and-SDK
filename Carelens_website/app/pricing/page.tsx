import { SiteFooter } from "@/components/sections/site-footer";
import { SiteHeader } from "@/components/sections/site-header";
import { GlassCard } from "@/components/ui/glass-card";
import Link from "next/link";

const plans = [
  {
    id: "cheap",
    name: "Cheap",
    price: 1,
    description:
      "For light smart-glasses AI usage and testing.",
    features: [
      "Access to lower-cost AI models",
      "Up to 3,500,000 reference tokens/month",
      "Standard support",
    ],
    cta: "Subscribe — $1/mo",
    href: "/web-subscribe?plan=cheap",
  },
  {
    id: "standard",
    name: "Standard",
    price: 5,
    description:
      "For regular smart-glasses workflows that need stable AI access.",
    features: [
      "Curated AI models for chat, voice, and image workflows",
      "Up to 18,500,000 reference tokens/month",
      "Priority support",
    ],
    cta: "Subscribe — $5/mo",
    href: "/web-subscribe?plan=standard",
    recommended: true,
  },
  {
    id: "max",
    name: "Max",
    price: 20,
    description:
      "For heavier usage, automation, and team-style workflows.",
    features: [
      "All curated AI models with the highest quota",
      "Up to 74,000,000 reference tokens/month",
      "Priority support",
      "Early access to new models",
    ],
    cta: "Subscribe — $20/mo",
    href: "/web-subscribe?plan=max",
  },
];

export default function PricingPage() {
  return (
    <div className="pb-10">
      <div className="carelens-ambient" />
      <SiteHeader />
      <main className="container-width pt-8 sm:pt-12">
        <section className="mx-auto max-w-6xl space-y-12">
          <GlassCard className="glass-card-strong p-6 sm:p-7 text-center">
            <p className="pill-eyebrow mx-auto w-fit">Pricing</p>
            <h1 className="mt-3 text-3xl font-semibold tracking-tight text-slate-900">
              CyanBridge Pro Plans
            </h1>
            <p className="mt-2 max-w-xl mx-auto text-sm text-slate-500">
              Choose the plan that fits your usage. All plans are billed
              monthly. Cancel anytime. This website sells software access only,
              not smart-glasses hardware.
            </p>
          </GlassCard>

          <div className="grid gap-8 md:grid-cols-3 items-start">
            {plans.map((plan) => (
              <div key={plan.id} className="relative">
                {plan.recommended && (
                  <div className="absolute -top-3 left-1/2 -translate-x-1/2 z-10">
                    <span className="inline-flex items-center rounded-full bg-[#1f5b66] px-4 py-1 text-xs font-semibold text-[#fafaf7] shadow-sm">
                      Recommended
                    </span>
                  </div>
                )}
                <GlassCard
                  variant={plan.recommended ? "hero" : "default"}
                  className={`flex flex-col p-8 text-center md:p-10 ${
                    plan.recommended
                      ? "relative"
                      : "border-[#cdbe98]/45 bg-white/90 shadow-card"
                  }`}
                >
                  <p className="mt-2 text-sm font-medium uppercase tracking-widest text-[#1f5b66]">
                    {plan.name}
                  </p>

                  <p className="mt-6">
                    <span className="text-5xl font-bold text-[#24393f]">
                      ${plan.price}
                    </span>
                    <span className="text-xl font-medium text-[#1f5b66]/80">
                      /month
                    </span>
                  </p>

                  <p className="mt-3 text-sm text-[#1f5b66]/80 leading-relaxed">
                    {plan.description}
                  </p>

                  <div className="mt-8 text-left flex-grow">
                    <p className="mb-4 text-sm font-medium uppercase tracking-wider text-[#24393f]">
                      What&rsquo;s included
                    </p>
                    <ul className="space-y-3">
                      {plan.features.map((feature) => (
                        <li
                          key={feature}
                          className="flex gap-3 text-sm text-[#24393f]"
                        >
                          <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-[#95b6a5]/28 text-xs font-bold text-[#1f5b66]">
                            ✓
                          </span>
                          {feature}
                        </li>
                      ))}
                    </ul>
                  </div>

                  <Link
                    href={plan.href}
                    className="btn-primary mt-8 block w-full py-4 text-base text-center focus:outline-none focus:ring-2 focus:ring-[#24393f]/50 focus:ring-offset-2"
                    style={{
                      background:
                        "linear-gradient(145deg, #24393f, #1f5b66)",
                      boxShadow: "0 4px 14px rgba(36, 57, 63, 0.25)",
                    }}
                  >
                    {plan.cta}
                  </Link>
                </GlassCard>
              </div>
            ))}
          </div>

          <GlassCard className="glass-card-strong p-6 sm:p-7 text-center">
            <h2 className="text-xl font-semibold text-slate-900">
              Free Trial
            </h2>
            <p className="mt-2 text-sm text-slate-500 max-w-lg mx-auto">
              New users get a 30-day free trial with access to lower-cost AI
              models for supported workflows. No payment method required.
            </p>
            <Link
              href="/pro/activate-trial"
              className="btn-primary mt-5 inline-block py-3 px-8 text-sm focus:outline-none focus:ring-2 focus:ring-[#24393f]/50 focus:ring-offset-2"
              style={{
                background:
                  "linear-gradient(145deg, #95b6a5, #1f5b66)",
                boxShadow: "0 4px 14px rgba(31, 91, 102, 0.25)",
              }}
            >
              Start Free Trial
            </Link>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7 text-center">
            <p className="text-xs text-slate-500">
              All plans are billed monthly in USD through Asaas or Stripe.
              Prices exclude applicable taxes. CyanBridge is a software
              subscription for smart-glasses AI access and does not sell
              hardware on this domain. See our{" "}
              <Link href="/terms" className="text-brand underline">
                Terms of Use
              </Link>{" "}
              and{" "}
              <Link href="/refund-policy" className="text-brand underline">
                Refund Policy
              </Link>{" "}
              for details.
            </p>
          </GlassCard>
        </section>
      </main>
      <SiteFooter />
    </div>
  );
}
