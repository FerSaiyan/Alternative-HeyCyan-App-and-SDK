"use client";

import Link from "next/link";
import { useRef, useState, useEffect } from "react";
import { useGSAP } from "@gsap/react";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { GlassCard } from "@/components/ui/glass-card";

gsap.registerPlugin(ScrollTrigger);

const plans = [
  {
    name: "Cheap",
    price: "$1",
    description: "For light smart-glasses AI usage and testing.",
    features: [
      "Managed access to lower-cost models",
      "3.5M reference tokens per month",
      "Standard billing support",
    ],
    href: "/web-subscribe?plan=cheap",
    cta: "Subscribe to Cheap",
  },
  {
    name: "Standard",
    price: "$5",
    description: "Recommended for everyday chat, voice, and image workflows.",
    features: [
      "Curated premium model access",
      "18.5M reference tokens per month",
      "Priority billing and access support",
    ],
    href: "/web-subscribe?plan=standard",
    cta: "Subscribe to Standard",
    recommended: true,
  },
  {
    name: "Max",
    price: "$20",
    description: "For heavier usage, automation, and team-style workflows.",
    features: [
      "Highest monthly quota",
      "74M reference tokens per month",
      "Priority support and faster scaling",
    ],
    href: "/web-subscribe?plan=max",
    cta: "Subscribe to Max",
  },
];

export function PricingSection() {
  const sectionRef = useRef<HTMLElement>(null);
  const headerRef = useRef<HTMLDivElement>(null);
  const cardsRef = useRef<HTMLDivElement>(null);
  const [isReducedMotion, setIsReducedMotion] = useState(false);

  useEffect(() => {
    const mediaQuery = window.matchMedia("(prefers-reduced-motion: reduce)");
    const onChange = () => setIsReducedMotion(mediaQuery.matches);
    onChange();
    mediaQuery.addEventListener("change", onChange);
    return () => mediaQuery.removeEventListener("change", onChange);
  }, []);

  useGSAP(
    () => {
      if (isReducedMotion) return;

      const ctx = gsap.context(() => {
        gsap.fromTo(
          headerRef.current,
          { opacity: 0, y: 32 },
          {
            opacity: 1,
            y: 0,
            duration: 0.8,
            ease: "power2.out",
            scrollTrigger: {
              trigger: headerRef.current,
              start: "top 85%",
              toggleActions: "play none none reverse",
            },
          },
        );

        gsap.fromTo(
          cardsRef.current?.querySelectorAll(".pricing-card") || [],
          { opacity: 0, y: 40, scale: 0.96 },
          {
            opacity: 1,
            y: 0,
            scale: 1,
            duration: 0.7,
            stagger: 0.15,
            ease: "power2.out",
            scrollTrigger: {
              trigger: cardsRef.current,
              start: "top 80%",
              toggleActions: "play none none reverse",
            },
          },
        );
      }, sectionRef);

      return () => ctx.revert();
    },
    { scope: sectionRef, dependencies: [isReducedMotion] },
  );

  return (
    <section id="precos" ref={sectionRef} className="relative py-section scroll-mt-20 sm:scroll-mt-24">
      <div className="absolute inset-0 -z-10 overflow-hidden">
        <div className="absolute inset-0 bg-gradient-to-b from-[#fafaf7] via-[#fafaf7]/80 to-[#fafaf7]" />
        <div
          className="absolute inset-0 opacity-70"
          style={{
            backgroundImage: `
              radial-gradient(circle at 15% 20%, rgba(31, 91, 102, 0.20) 0%, transparent 55%),
              radial-gradient(circle at 85% 15%, rgba(149, 182, 165, 0.22) 0%, transparent 50%),
              radial-gradient(circle at 50% 85%, rgba(205, 190, 152, 0.18) 0%, transparent 45%),
              radial-gradient(circle at 30% 60%, rgba(250, 250, 247, 0.28) 0%, transparent 40%)
            `,
          }}
        />
        <div className="pointer-events-none absolute top-24 right-[15%] h-[380px] w-[380px] rounded-full bg-[#95b6a5]/22 blur-[140px]" aria-hidden="true" />
        <div className="pointer-events-none absolute bottom-10 left-[5%] h-[280px] w-[280px] rounded-full bg-[#1f5b66]/16 blur-[100px]" aria-hidden="true" />
      </div>

      <div className="container-width">
        <div ref={headerRef} className="mx-auto mb-14 max-w-3xl text-center">
          <p className="pill-eyebrow mx-auto mb-6 w-fit">PRICING</p>
          <h2 className="text-3xl font-semibold tracking-tight text-[#24393f] sm:text-4xl">
            Software plans for smart-glasses AI access
          </h2>
          <p className="mt-4 text-sm leading-relaxed text-[#1f5b66]/80 sm:text-base">
            Choose a monthly plan for managed AI access. This domain sells
            software subscriptions only and does not include hardware.
          </p>
        </div>

        <div ref={cardsRef} className="mx-auto grid items-start gap-8 md:max-w-5xl md:grid-cols-3">
          {plans.map((plan) => (
            <div key={plan.name} className="pricing-card relative">
              {plan.recommended ? (
                <div className="absolute -top-3 left-1/2 z-10 -translate-x-1/2">
                  <span className="inline-flex items-center rounded-full bg-[#1f5b66] px-4 py-1 text-xs font-semibold text-[#fafaf7] shadow-sm">
                    Recommended
                  </span>
                </div>
              ) : null}
              <GlassCard
                variant={plan.recommended ? "hero" : "default"}
                className={`flex h-full flex-col p-8 text-center md:p-10 ${
                  plan.recommended ? "relative" : "border-[#cdbe98]/45 bg-white/90 shadow-card"
                }`}
              >
                <p className="mt-2 text-sm font-medium uppercase tracking-widest text-[#1f5b66]">
                  {plan.name}
                </p>

                <p className="mt-6">
                  <span className="text-5xl font-bold text-[#24393f]">{plan.price}</span>
                  <span className="text-xl font-medium text-[#1f5b66]/80">/month</span>
                </p>

                <p className="mt-3 text-sm leading-relaxed text-[#1f5b66]/80">
                  {plan.description}
                </p>

                <div className="mt-8 flex-grow text-left">
                  <p className="mb-4 text-sm font-medium uppercase tracking-wider text-[#24393f]">
                    Included
                  </p>
                  <ul className="space-y-3">
                    {plan.features.map((feature) => (
                      <li key={feature} className="flex gap-3 text-sm text-[#24393f]">
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
                  className="btn-primary mt-8 block w-full py-4 text-center text-base focus:outline-none focus:ring-2 focus:ring-[#24393f]/50 focus:ring-offset-2"
                  style={{
                    background: "linear-gradient(145deg, #24393f, #1f5b66)",
                    boxShadow: "0 4px 14px rgba(36, 57, 63, 0.25)",
                  }}
                >
                  {plan.cta}
                </Link>
              </GlassCard>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
