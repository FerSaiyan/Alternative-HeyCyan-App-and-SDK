/**
 * BottomCtaSection - Cinematic image-led full-width CTA
 *
 * Section 8 of 8:
 * - Full-width image panel with real asset-based look
 * - Image-led design with gradient overlay for text readability
 * - Strong headline and CTA
 * - Compliance line included
 */
"use client";

import Link from "next/link";
import Image from "next/image";
import { useRef, useState, useEffect } from "react";
import { useGSAP } from "@gsap/react";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";

gsap.registerPlugin(ScrollTrigger);

export function BottomCtaSection() {
  const sectionRef = useRef<HTMLElement>(null);
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

  // GSAP scroll-trigger reveal
  useGSAP(
    () => {
      if (isReducedMotion) return;

      const ctx = gsap.context(() => {
        // Content reveal with stagger
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
      {/* Cinematic image-led background panel */}
      <div className="absolute inset-0" aria-hidden="true">
        <Image
          src="/assets/website-package-1/Hero image.png"
          alt=""
          fill
          className="object-cover scale-105"
          aria-hidden="true"
        />
      </div>

      {/* Layered gradient overlay for dramatic cinematic feel */}
      <div
        className="absolute inset-0 bg-gradient-to-b from-slate-900/70 via-slate-900/50 to-slate-900/80"
        aria-hidden="true"
      />

      {/* Cinematic top light bleed */}
      <div
        className="absolute inset-0 bg-gradient-to-b from-white/8 via-transparent to-transparent"
        aria-hidden="true"
      />

      {/* Vignette effect */}
      <div
        className="absolute inset-0"
        style={{
          background: "radial-gradient(ellipse at center, transparent 60%, rgba(0,0,0,0.25) 100%)"
        }}
        aria-hidden="true"
      />

      {/* Content */}
      <div ref={contentRef} className="container-width relative z-10 text-center py-20 sm:py-28 lg:py-32 px-4">
        <p className="cta-eyebrow pill-eyebrow bg-white/10 border-white/20 text-white mx-auto w-fit mb-8">
          Pronto para começar
        </p>
        <h2 className="cta-headline text-3xl font-semibold tracking-tight text-white sm:text-4xl lg:text-5xl max-w-3xl mx-auto leading-tight">
          Pronto para cuidar de quem você ama?
        </h2>
        <p className="cta-subtitle mx-auto mt-4 max-w-xl text-center text-lg leading-relaxed text-white/85">
          Comece agora com os óculos CareLens e tenha a tranquilidade de saber que seu familiar está sendo assistido.
        </p>
        <div className="cta-button-wrapper mt-10">
          <Link
            href="#contato"
            className="btn-primary cta-button px-10 py-4 text-base focus:outline-none focus:ring-2 focus:ring-white/50 focus:ring-offset-2 focus:ring-offset-slate-900"
          >
            Começar Agora
          </Link>
        </div>
      </div>
    </section>
  );
}
