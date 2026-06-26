"use client";

import { useRef, useState, useEffect } from "react";
import { useGSAP } from "@gsap/react";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { SectionHeading } from "@/components/ui/section-heading";

gsap.registerPlugin(ScrollTrigger);

const services = [
  {
    label: "Step 1",
    title: "Sign in to CyanBridge",
    description:
      "Create your account and choose a plan for managed access to supported smart-glasses AI workflows.",
  },
  {
    label: "Step 2",
    title: "Connect your app workflow",
    description:
      "Use the companion Android app to route compatible smart-glasses requests through CyanBridge.",
  },
  {
    label: "Step 3",
    title: "Send AI requests",
    description:
      "Access chat, voice, and image models from one subscription with predictable monthly quotas.",
  },
  {
    label: "Step 4",
    title: "Track usage and upgrade anytime",
    description:
      "Manage billing, quota consumption, and plan changes from the same software account.",
  },
];

export function ServiceCardsSection() {
  const sectionRef = useRef<HTMLElement>(null);
  const headingRef = useRef<HTMLDivElement>(null);
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
          headingRef.current,
          { opacity: 0, y: 28 },
          {
            opacity: 1,
            y: 0,
            duration: 0.7,
            ease: "power2.out",
            scrollTrigger: {
              trigger: headingRef.current,
              start: "top 85%",
              toggleActions: "play none none reverse",
            },
          },
        );

        const cards = cardsRef.current?.querySelectorAll(".service-card") || [];
        if (cards.length === 0) return;

        gsap.set(cards, {
          opacity: 0,
          y: 48,
          scale: 0.92,
          rotateX: 8,
        });

        const mm = gsap.matchMedia();

        mm.add("(min-width: 1024px)", () => {
          const tl = gsap.timeline({
            scrollTrigger: {
              trigger: cardsRef.current,
              start: "top 80%",
              end: "bottom 60%",
              scrub: 1,
              anticipatePin: 1,
            },
          });

          tl.to(cards, {
            opacity: 1,
            y: 0,
            scale: 1,
            rotateX: 0,
            duration: 0.5,
            ease: "power3.out",
            stagger: 0.15,
          });

          return () => {
            tl.kill();
          };
        });

        mm.add("(max-width: 1023px)", () => {
          cards.forEach((card) => {
            gsap.to(card, {
              opacity: 1,
              y: 0,
              scale: 1,
              rotateX: 0,
              duration: 0.5,
              ease: "power3.out",
              scrollTrigger: {
                trigger: card,
                start: "top 85%",
                toggleActions: "play none none reverse",
              },
            });
          });
        });

        return () => {
          mm.revert();
        };
      }, sectionRef);

      return () => ctx.revert();
    },
    { scope: sectionRef, dependencies: [isReducedMotion] },
  );

  return (
    <section id="servicos" ref={sectionRef} className="relative py-section scroll-mt-20 sm:scroll-mt-24">
      <div className="absolute inset-0 -z-10">
        <div className="absolute inset-0 bg-gradient-to-b from-transparent via-sky-50/40 to-transparent" />
      </div>
      <div className="container-width">
        <div ref={headingRef}>
          <SectionHeading
            eyebrow="HOW IT WORKS"
            title="From signup to smart-glasses AI access"
            className="mb-10"
          />
        </div>

        <div className="sr-only">
          <h2>How CyanBridge works</h2>
          <ol>
            <li>
              <strong>Sign in to CyanBridge</strong> - Create your account and choose a plan for managed access to supported smart-glasses AI workflows.
            </li>
            <li>
              <strong>Connect your app workflow</strong> - Use the companion Android app to route compatible smart-glasses requests through CyanBridge.
            </li>
            <li>
              <strong>Send AI requests</strong> - Access chat, voice, and image models from one subscription with predictable monthly quotas.
            </li>
            <li>
              <strong>Track usage and upgrade anytime</strong> - Manage billing, quota consumption, and plan changes from the same software account.
            </li>
          </ol>
        </div>

        <div
          ref={cardsRef}
          className="mt-10 -mx-4 flex gap-4 overflow-x-auto px-4 pb-4 sm:mx-0 sm:grid sm:grid-cols-2 sm:overflow-visible sm:px-0 sm:pb-0 lg:grid-cols-4"
        >
          {services.map((service) => (
            <div
              key={service.title}
              className="service-card relative aspect-[4/5] w-[85%] flex-shrink-0 overflow-hidden rounded-2xl border border-[#cdbe98]/45 bg-[linear-gradient(180deg,rgba(255,255,255,0.96),rgba(232,243,247,0.92))] shadow-card sm:w-auto"
            >
              <div
                className="absolute inset-0"
                style={{
                  background:
                    "radial-gradient(circle at top right, rgba(149,182,165,0.22) 0%, transparent 34%), radial-gradient(circle at bottom left, rgba(31,91,102,0.16) 0%, transparent 36%)",
                }}
                aria-hidden="true"
              />
              <div className="absolute inset-0 z-10 flex flex-col justify-between p-5 sm:p-6">
                <p className="font-mono text-xs uppercase tracking-[0.16em] text-[#1f5b66]/76">
                  {service.label}
                </p>
                <div className="rounded-2xl border border-white/70 bg-white/75 p-4 backdrop-blur-md">
                  <p className="text-base font-semibold text-[#24393f]">{service.title}</p>
                  <p className="mt-2 text-sm leading-snug text-[#1f5b66]/88">
                    {service.description}
                  </p>
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
