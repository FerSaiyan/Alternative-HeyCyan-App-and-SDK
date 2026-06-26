"use client";

import Link from "next/link";
import { useRef, useState, useEffect } from "react";
import { useGSAP } from "@gsap/react";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";

gsap.registerPlugin(ScrollTrigger);

export function BottomCtaSection() {
  const sectionRef = useRef<HTMLElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);
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
          contentRef.current?.children || [],
          { opacity: 0, y: 28 },
          {
            opacity: 1,
            y: 0,
            duration: 0.7,
            stagger: 0.12,
            ease: "power2.out",
            scrollTrigger: {
              trigger: contentRef.current,
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
    <section id="cta-final" ref={sectionRef} className="relative py-section scroll-mt-20 sm:scroll-mt-24">
      <div
        className="absolute inset-0 bg-gradient-to-b from-[#18343b] via-[#1f5b66] to-[#24393f]"
        aria-hidden="true"
      />
      <div
        className="absolute inset-0 bg-gradient-to-b from-white/10 via-transparent to-transparent"
        aria-hidden="true"
      />
      <div
        className="absolute inset-0"
        style={{
          background:
            "radial-gradient(circle at 20% 20%, rgba(149,182,165,0.18) 0%, transparent 30%), radial-gradient(circle at 80% 70%, rgba(205,190,152,0.12) 0%, transparent 28%)",
        }}
        aria-hidden="true"
      />

      <div ref={contentRef} className="container-width relative z-10 px-4 py-20 text-center sm:py-28 lg:py-32">
        <p className="cta-eyebrow pill-eyebrow mx-auto mb-8 w-fit border-white/20 bg-white/10 text-white">
          Ready to start
        </p>
        <h2 className="cta-headline mx-auto max-w-3xl text-3xl font-semibold leading-tight tracking-tight text-white sm:text-4xl lg:text-5xl">
          Ready to activate AI on your smart glasses?
        </h2>
        <p className="cta-subtitle mx-auto mt-4 max-w-xl text-center text-lg leading-relaxed text-white/85">
          Pick a software plan, connect your workflow, and start routing
          smart-glasses requests through CyanBridge.
        </p>
        <div className="cta-button-wrapper mt-10">
          <Link
            href="/pricing"
            className="btn-primary cta-button px-10 py-4 text-base focus:outline-none focus:ring-2 focus:ring-white/50 focus:ring-offset-2 focus:ring-offset-slate-900"
          >
            View Pricing
          </Link>
        </div>
      </div>
    </section>
  );
}
