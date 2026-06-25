"use client";

import { useEffect, useState, useRef } from "react";
import { useGSAP } from "@gsap/react";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { GlassCard } from "@/components/ui/glass-card";

gsap.registerPlugin(ScrollTrigger);

type SuccessStory = {
  id: string;
  quote: string;
  patientName: string;
  achievement: string;
};

export function SignatureSuccessStory({ story }: { story: SuccessStory }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);
  const [isReducedMotion, setIsReducedMotion] = useState(false);

  // Detect reduced motion preference
  useEffect(() => {
    const mediaQuery = window.matchMedia("(prefers-reduced-motion: reduce)");
    const onChange = () => setIsReducedMotion(mediaQuery.matches);
    onChange();
    mediaQuery.addEventListener("change", onChange);
    return () => mediaQuery.removeEventListener("change", onChange);
  }, []);

  // GSAP sequential reveal animation
  useGSAP(
    () => {
      if (isReducedMotion) return;

      const ctx = gsap.context(() => {
        // Timeline for sequential reveal
        const tl = gsap.timeline({
          scrollTrigger: {
            trigger: containerRef.current,
            start: "top 75%",
            toggleActions: "play none none reverse",
          },
        });

        // 1. Container fade in
        tl.fromTo(
          containerRef.current,
          { opacity: 0 },
          { opacity: 1, duration: 0.4, ease: "power2.out" }
        );

        // 2. Content details reveal
        tl.fromTo(
          contentRef.current,
          { opacity: 0, y: 16 },
          { opacity: 1, y: 0, duration: 0.5, ease: "power2.out" },
          "-=0.2"
        );
      }, containerRef);

      return () => ctx.revert();
    },
    { scope: containerRef, dependencies: [isReducedMotion] }
  );

  return (
    <div
      ref={containerRef}
      className="mx-auto max-w-5xl"
      suppressHydrationWarning
    >
      <GlassCard
        variant="hero"
        className="overflow-hidden p-6 sm:p-8 lg:p-10"
        data-testid="signature-success-story"
      >
        <div
          ref={contentRef}
          className="flex flex-col justify-center space-y-6"
        >
          {/* Achievement badge */}
          <div className="inline-flex items-center gap-2 self-start rounded-full border border-[var(--brand)]/30 bg-[var(--brand)]/8 px-3 py-1">
            <span className="h-1.5 w-1.5 rounded-full bg-[var(--brand)]" />
            <span className="text-xs font-semibold uppercase tracking-wider text-[var(--brand)]">
              Caso de Sucesso
            </span>
          </div>

          {/* Quote */}
          <blockquote className="relative">
            <span
              className="absolute -top-2 -left-1 text-5xl font-serif text-[var(--brand)]/20"
              aria-hidden="true"
            >
              &ldquo;
            </span>
            <p className="text-lg leading-relaxed italic text-[#24393f] sm:text-xl">
              {story.quote}
            </p>
          </blockquote>

          {/* Patient info */}
          <div className="flex items-center gap-3 pt-2">
            <div className="h-px w-10 bg-[var(--brand)]/50" />
            <div>
              <p className="text-sm font-semibold text-[#24393f]">
                {story.patientName}
              </p>
              <p className="text-xs text-[#24393f]/70">{story.achievement}</p>
            </div>
          </div>
        </div>
      </GlassCard>
    </div>
  );
}
