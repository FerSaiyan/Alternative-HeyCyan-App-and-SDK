"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { useGSAP } from "@gsap/react";
import gsap from "gsap";

const highlights = [
  { label: "Supported Devices", value: "Built for compatible smart-glasses workflows" },
  { label: "AI Modes", value: "Chat, voice, and image requests in one subscription" },
  { label: "Activation", value: "Instant software access after checkout" },
  {
    label: "Plans",
    value: "$1, $5, and $20 monthly options",
    note: "Software only, no hardware sold here",
  },
];

const heroTitleWords = ["AI", "software", "for", "smart", "glasses"];

interface HeroSectionProps {
  id?: string;
}

export function HeroSection({ id }: HeroSectionProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const hasPlayedEntranceRef = useRef(false);
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
      if (hasPlayedEntranceRef.current) return;
      hasPlayedEntranceRef.current = true;

      const tl = gsap.timeline({ defaults: { ease: "power3.out" } });

      tl.fromTo(
        ".hero-content",
        { opacity: 0 },
        { opacity: 1, duration: 0.01 },
      )
        .fromTo(
          ".hero-shell",
          { opacity: 0, y: 24, scale: 0.975 },
          { opacity: 1, y: 0, scale: 1, duration: 0.72 },
        )
        .fromTo(
          ".hero-title .hero-word",
          { opacity: 0, yPercent: 130, rotateX: -72, transformOrigin: "50% 100%" },
          {
            opacity: 1,
            yPercent: 0,
            rotateX: 0,
            duration: 0.8,
            stagger: 0.055,
            ease: "back.out(1.25)",
          },
          "-=0.36",
        )
        .fromTo(
          ".hero-subtitle",
          { opacity: 0, y: 18 },
          { opacity: 1, y: 0, duration: 0.58 },
          "-=0.4",
        )
        .fromTo(
          ".hero-cta",
          { opacity: 0, y: 16, scale: 0.94 },
          { opacity: 1, y: 0, scale: 1, duration: 0.48, ease: "power2.out" },
          "-=0.32",
        )
        .fromTo(
          ".hero-highlight",
          { opacity: 0, y: 36, scale: 0.9, rotateX: 12, transformOrigin: "50% 100%" },
          {
            opacity: 1,
            y: 0,
            scale: 1,
            rotateX: 0,
            duration: 0.62,
            stagger: 0.24,
            ease: "power2.out",
          },
          "+=0.18",
        );
    },
    { scope: containerRef, dependencies: [isReducedMotion] },
  );

  return (
    <section
      id={id}
      className="relative flex min-h-[82vh] items-center justify-center overflow-hidden scroll-mt-20 sm:scroll-mt-24"
    >
      <div className="absolute inset-0 z-0">
        <div
          className="absolute inset-0"
          style={{
            background:
              "linear-gradient(180deg, rgba(250,250,247,0.92) 0%, rgba(214,231,237,0.96) 46%, rgba(186,214,224,0.98) 100%)",
          }}
          aria-hidden="true"
        />
        <div
          className="absolute inset-0"
          style={{
            background:
              "radial-gradient(circle at 18% 22%, rgba(31,91,102,0.18) 0%, transparent 32%), radial-gradient(circle at 82% 16%, rgba(149,182,165,0.24) 0%, transparent 30%), radial-gradient(circle at 50% 78%, rgba(205,190,152,0.18) 0%, transparent 28%)",
          }}
          aria-hidden="true"
        />
      </div>

      <div className="container-width relative z-10 py-16 sm:py-20 lg:py-28">
        <div
          ref={containerRef}
          className="hero-content mx-auto max-w-4xl"
          style={!isReducedMotion ? { opacity: 0 } : undefined}
        >
          <div className="space-y-9 text-center">
            <div className="hero-shell rounded-2xl border border-[#24393f]/18 bg-white p-5 shadow-[0_10px_34px_rgba(36,57,63,0.22)] sm:p-6">
              <h1 className="hero-title mt-4 mx-auto max-w-3xl text-center text-hero font-bold leading-[1.08] tracking-tight text-[#24393f]">
                {heroTitleWords.map((word, index) => (
                  <span key={`${word}-${index}`} className="hero-word inline-block">
                    {word}
                    {index < heroTitleWords.length - 1 ? "\u00a0" : ""}
                  </span>
                ))}
              </h1>
              <p className="hero-subtitle mx-auto mt-4 max-w-2xl text-center text-lg leading-relaxed text-black sm:text-xl">
                CyanBridge is a software subscription that connects supported smart
                glasses to chat, voice, and image AI models. This website sells
                software access only, not hardware or medical services.
              </p>

              <div className="mt-6 flex flex-col justify-center gap-4 sm:flex-row">
                <Link
                  href="/pricing"
                  className="hero-cta btn-primary px-8 py-4 text-base focus:outline-none focus:ring-2 focus:ring-brand/50 focus:ring-offset-2"
                >
                  View Pricing
                </Link>
                <Link
                  href="#servicos"
                  className="hero-cta btn-secondary px-8 py-4 text-base focus:outline-none focus:ring-2 focus:ring-brand/50 focus:ring-offset-2"
                >
                  How It Works
                </Link>
              </div>

              <div className="mt-6 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
                {highlights.map((item) => (
                  <div
                    key={item.label}
                    className="hero-highlight rounded-2xl border p-4 text-center backdrop-blur-md sm:text-left"
                    style={{
                      borderColor: "rgba(205,190,152,0.58)",
                      backgroundColor: "#1f5b66",
                    }}
                  >
                    <p className="text-xs uppercase tracking-[0.14em]" style={{ color: "#fafaf7" }}>
                      {item.label}
                    </p>
                    <p className="mt-1.5 text-base font-semibold text-white">{item.value}</p>
                    {item.note ? (
                      <p className="mt-1 text-[11px] leading-tight text-white/82">{item.note}</p>
                    ) : null}
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
