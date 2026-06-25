"use client";

import Link from "next/link";
import Image from "next/image";
import { useEffect, useRef, useState } from "react";
import { useGSAP } from "@gsap/react";
import gsap from "gsap";


function clamp(value: number): number {
  if (value < 0) return 0;
  if (value > 1) return 1;
  return value;
}

const highlights = [
  { label: "Óculos HD", value: "Câmera POV, microfone e alto-falante" },
  { label: "Assistente IA", value: "Lembretes, alertas e companhia" },
  { label: "Monitoramento", value: "Painel para familiares em tempo real" },
  {
    label: "Frete",
    value: "Chega na sua casa em até 10 dias",
    note: "Dependendo da localização",
  },
];

const heroTitleWords = [
  "Tecnologia",
  "assistiva",
  "inteligente",
  "para",
  "um",
  "envelhecimento",
  "mais",
  "seguro",
];

interface HeroSectionProps {
  id?: string;
}

export function HeroSection({ id }: HeroSectionProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const hasPlayedEntranceRef = useRef(false);
  const [isReducedMotion, setIsReducedMotion] = useState(false);
  const [videoProgress, setVideoProgress] = useState(0);
  const [videoEnded, setVideoEnded] = useState(false);
  const revealProgress = isReducedMotion ? 1 : videoEnded ? 1 : clamp(videoProgress);
  const heroReveal = isReducedMotion ? 1 : videoEnded ? 1 : clamp((revealProgress - 0.64) / 0.28);
  const fogOpacity = 0.44 + heroReveal * 0.46;

  useEffect(() => {
    const mediaQuery = window.matchMedia("(prefers-reduced-motion: reduce)");
    const onChange = () => setIsReducedMotion(mediaQuery.matches);
    onChange();
    mediaQuery.addEventListener("change", onChange);
    return () => mediaQuery.removeEventListener("change", onChange);
  }, []);

  // GSAP kinetic animations - cleanup safe, respects reduced motion
  useGSAP(
    () => {
      if (isReducedMotion) return;
      if (!videoEnded) return;
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
    { scope: containerRef, dependencies: [isReducedMotion, videoEnded] },
  );

  return (
    <section id={id} className="relative min-h-[82vh] flex items-center justify-center overflow-hidden scroll-mt-20 sm:scroll-mt-24">
      <div className="absolute inset-0 z-0">
        <Image
          src="/assets/website-package-1/two-vials-poster.webp"
          alt=""
          fill
          className="object-cover object-center"
          priority
          aria-hidden="true"
        />
        {!isReducedMotion ? (
          <video
            className="absolute inset-0 h-full w-full object-cover object-center"
            autoPlay
            muted
            playsInline
            preload="auto"
            onLoadedMetadata={(event) => {
              event.currentTarget.playbackRate = 1.5;
            }}
            onTimeUpdate={(event) => {
              const target = event.currentTarget;
              if (!Number.isFinite(target.duration) || target.duration <= 0) return;
              setVideoProgress(target.currentTime / target.duration);
            }}
            onEnded={() => {
              setVideoEnded(true);
              setVideoProgress(1);
            }}
            aria-hidden="true"
          >
            <source
              src="/assets/website-package-1/two-vials-hero-mobile-9x16.mp4"
              type="video/mp4"
              media="(max-width: 767px)"
            />
            <source src="/assets/website-package-1/two-vials-hero.mp4" type="video/mp4" />
          </video>
        ) : null}

        <div
          className="absolute inset-0"
          style={{
            background:
              "linear-gradient(to bottom, rgba(30,76,99,0.78), rgba(43,96,120,0.6) 34%, rgba(69,124,148,0.72) 68%, rgba(188,217,230,0.9) 100%)",
            opacity: fogOpacity,
          }}
          aria-hidden="true"
        />
        <div
          className="absolute inset-0"
          style={{
            background:
              "radial-gradient(ellipse at center, rgba(255,255,255,0.03) 0%, rgba(104,159,182,0.48) 66%, rgba(22,67,88,0.74) 100%)",
            opacity: fogOpacity * 0.95,
          }}
          aria-hidden="true"
        />
      </div>

      <div className="container-width relative z-10 py-16 sm:py-20 lg:py-28">
        <div
          ref={containerRef}
          className="hero-content mx-auto max-w-4xl"
          style={!isReducedMotion && !videoEnded ? { opacity: 0 } : undefined}
        >
          <div className="space-y-9 text-center">
            <div className="hero-shell rounded-2xl border border-[#24393f]/18 bg-white p-5 shadow-[0_10px_34px_rgba(36,57,63,0.22)] sm:p-6">
              <h1 className="hero-title mt-4 max-w-3xl mx-auto text-center font-bold tracking-tight text-[#24393f] text-hero leading-[1.08]">
                {heroTitleWords.map((word, index) => (
                  <span key={`${word}-${index}`} className="hero-word inline-block">
                    {word}
                    {index < heroTitleWords.length - 1 ? "\u00a0" : ""}
                  </span>
                ))}
              </h1>
              <p className="hero-subtitle mx-auto mt-4 max-w-xl text-center text-lg leading-relaxed text-black sm:text-xl">
                A CareLens AI transforma os óculos HeyCyan em um assistente com IA para apoiar idosos, famílias e cuidadores no dia a dia.
              </p>

              <div className="mt-6 flex flex-col sm:flex-row gap-4 justify-center">
                <Link
                  href="#contato"
                  className="hero-cta btn-primary px-8 py-4 text-base focus:outline-none focus:ring-2 focus:ring-brand/50 focus:ring-offset-2"
                >
                  Saiba Mais
                </Link>
                <Link
                  href="#produto"
                  className="hero-cta btn-secondary px-8 py-4 text-base focus:outline-none focus:ring-2 focus:ring-brand/50 focus:ring-offset-2"
                >
                  Como Funciona
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
                    <p
                      className="text-xs uppercase tracking-[0.14em]"
                      style={{ color: "#fafaf7" }}
                    >
                      {item.label}
                    </p>
                    <p className="mt-1.5 text-base font-semibold text-white">{item.value}</p>
                    {"note" in item && item.note ? (
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
