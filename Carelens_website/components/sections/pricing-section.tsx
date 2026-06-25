"use client";

/**
 * PricingSection - Premium blue/cloud panel
 *
 * Section 4 of 8:
 * - Full-width blue/cloud environment
 * - Centered cards with generous whitespace
 * - Primary card: "Plano Semestral" com pagamento único parcelado
 * - Comparison card: Processo sem coordenação
 * - CTA: "Começar agora →"
 * - Disclaimer: prescription not guaranteed
 */
import Link from "next/link";
import { useRef, useState, useEffect } from "react";
import { useGSAP } from "@gsap/react";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { GlassCard } from "@/components/ui/glass-card";

// Register ScrollTrigger
gsap.registerPlugin(ScrollTrigger);

const includedItems = [
  "Óculos HeyCyan com câmera HD POV",
  "Microfone direcional e alto-falante integrado",
  "Assistente IA personalizada para o idoso",
  "Painel de monitoramento familiar",
  "Alertas inteligentes (queda, medicação)",
  "Suporte técnico dedicado",
];

export function PricingSection() {
  const sectionRef = useRef<HTMLElement>(null);
  const headerRef = useRef<HTMLDivElement>(null);
  const cardsRef = useRef<HTMLDivElement>(null);
  const [isReducedMotion, setIsReducedMotion] = useState(false);

  // Detect reduced motion preference
  useEffect(() => {
    const mediaQuery = window.matchMedia("(prefers-reduced-motion: reduce)");
    const onChange = () => setIsReducedMotion(mediaQuery.matches);
    onChange();
    mediaQuery.addEventListener("change", onChange);
    return () => mediaQuery.removeEventListener("change", onChange);
  }, []);

  // Scroll-triggered section reveal
  useGSAP(
    () => {
      if (isReducedMotion) return;

      const ctx = gsap.context(() => {
        // Header reveal
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

        // Cards stagger reveal
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
      {/* Full-width blue/cloud background with premium atmospheric depth */}
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
            `
          }}
        />
        {/* Decorative blur orbs — breaks flat card stack with atmospheric depth */}
        <div className="pointer-events-none absolute top-24 right-[15%] h-[380px] w-[380px] rounded-full bg-[#95b6a5]/22 blur-[140px]" aria-hidden="true" />
        <div className="pointer-events-none absolute bottom-10 left-[5%] h-[280px] w-[280px] rounded-full bg-[#1f5b66]/16 blur-[100px]" aria-hidden="true" />
        <div className="pointer-events-none absolute top-[60%] left-[40%] h-[200px] w-[200px] rounded-full bg-[#cdbe98]/14 blur-[80px]" aria-hidden="true" />
      </div>

      <div className="container-width">
        {/* Header */}
        <div ref={headerRef} className="max-w-2xl mx-auto text-center mb-14">
          <p className="pill-eyebrow mx-auto w-fit mb-6">INVESTIMENTO</p>
          <h2 className="text-3xl font-semibold tracking-tight text-[#24393f] sm:text-4xl">
            Óculos + IA por menos de R$ 1/dia
          </h2>
        </div>

        {/* Centered cards with generous whitespace */}
        <div ref={cardsRef} className="mx-auto grid items-start gap-12 md:max-w-5xl md:grid-cols-3">
          {/* Card 1: Glasses */}
          <div
            id="oculos"
            className="pricing-card relative scroll-mt-20 sm:scroll-mt-24"
          >
            <GlassCard variant="hero" className="relative p-8 text-center md:p-10">
              <p className="mt-2 text-sm font-medium uppercase tracking-widest text-[#1f5b66]">
                Óculos HeyCyan
              </p>

              <p className="mt-6">
                <span className="text-5xl font-bold text-[#24393f]">R$ 250,00</span>
              </p>

              <p className="mt-2 text-sm text-[#1f5b66]/80">Pagamento único. Escolha entre branco ou preto. Garantia de 30 dias.</p>

              <div className="mt-8 text-left">
                <p className="mb-4 text-sm font-medium uppercase tracking-wider text-[#24393f]">
                  Inclui
                </p>
                <ul className="space-y-3">
                  <li className="flex gap-3 text-sm text-[#24393f]">
                    <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-[#95b6a5]/28 text-xs font-bold text-[#1f5b66]">✓</span>
                    Câmera HD POV integrada
                  </li>
                  <li className="flex gap-3 text-sm text-[#24393f]">
                    <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-[#95b6a5]/28 text-xs font-bold text-[#1f5b66]">✓</span>
                    Microfone direcional
                  </li>
                  <li className="flex gap-3 text-sm text-[#24393f]">
                    <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-[#95b6a5]/28 text-xs font-bold text-[#1f5b66]">✓</span>
                    Alto-falante integrado
                  </li>
                  <li className="flex gap-3 text-sm text-[#24393f]">
                    <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-[#95b6a5]/28 text-xs font-bold text-[#1f5b66]">✓</span>
                    Carregador e estojo
                  </li>
                </ul>
              </div>
            </GlassCard>
          </div>

          {/* Card 2: Subscription (Recommended) */}
          <div
            id="assinatura"
            className="pricing-card relative scroll-mt-20 sm:scroll-mt-24"
          >
            <GlassCard variant="hero" className="relative p-8 text-center md:p-10">
              {/* Card badge */}
              <div className="absolute -top-3 left-1/2 -translate-x-1/2">
                <span className="inline-flex items-center rounded-full bg-[#1f5b66] px-4 py-1 text-xs font-semibold text-[#fafaf7] shadow-sm">
                  Recomendado
                </span>
              </div>

              <p className="mt-2 text-sm font-medium uppercase tracking-widest text-[#1f5b66]">
                Assinatura IA
              </p>

              <p className="mt-6">
                <span className="text-5xl font-bold text-[#24393f]">R$ 50,00</span>
                <span className="text-xl font-medium text-[#1f5b66]/80">/mês</span>
              </p>

              <p className="mt-2 text-sm text-[#1f5b66]/80">Assinatura mensal com todos os recursos de IA. Cancele quando quiser após 3 meses.</p>

              <div className="mt-8 text-left">
                <p className="mb-4 text-sm font-medium uppercase tracking-wider text-[#24393f]">
                  Recursos de IA
                </p>
                <ul className="space-y-3">
                  {includedItems.map((item) => (
                    <li key={item} className="flex gap-3 text-sm text-[#24393f]">
                      <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-[#95b6a5]/28 text-xs font-bold text-[#1f5b66]">✓</span>
                      {item}
                    </li>
                  ))}
                </ul>
              </div>

              {/* CTA - Dark pill */}
              <Link
                href="/signin?next=%2Fsub_onboarding"
                className="btn-primary mt-8 block w-full py-4 text-base text-center focus:outline-none focus:ring-2 focus:ring-[#24393f]/50 focus:ring-offset-2"
                style={{
                  background: "linear-gradient(145deg, #24393f, #1f5b66)",
                  boxShadow: "0 4px 14px rgba(36, 57, 63, 0.25)",
                }}
              >
                Assinar IA →
              </Link>
            </GlassCard>
          </div>

          {/* Card 3: Bundle */}
          <GlassCard variant="default" className="pricing-card flex flex-col border-[#cdbe98]/45 bg-white/90 p-8 text-center shadow-card md:p-10">
            <p className="mt-2 text-sm font-medium uppercase tracking-widest text-[#1f5b66]">
            Combo Completo
            </p>

            <p className="mt-6">
              <span className="text-5xl font-bold text-[#24393f]">R$ 300,00</span>
            </p>

            <p className="mt-2 text-sm text-[#1f5b66]/80">Óculos + primeiro mês de IA. Economia de R$ 50,00 no primeiro mês.</p>

            <div className="mt-8 text-left flex-grow">
              <p className="mb-4 text-sm font-medium uppercase tracking-wider text-[#1f5b66]">
                Por que escolher o combo:
              </p>
              <ul className="space-y-3">
                <li className="flex gap-3 text-sm text-[#24393f]">
                  <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-[#95b6a5]/28 text-xs font-bold text-[#1f5b66]">✓</span>
                  Tudo pronto para usar
                </li>
                <li className="flex gap-3 text-sm text-[#24393f]">
                  <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-[#95b6a5]/28 text-xs font-bold text-[#1f5b66]">✓</span>
                  Configuração guiada da IA
                </li>
                <li className="flex gap-3 text-sm text-[#24393f]">
                  <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-[#95b6a5]/28 text-xs font-bold text-[#1f5b66]">✓</span>
                  Painel familiar liberado
                </li>
                <li className="flex gap-3 text-sm text-[#24393f]">
                  <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-[#95b6a5]/28 text-xs font-bold text-[#1f5b66]">✓</span>
                  Garantia de 30 dias
                </li>
                <li className="flex gap-3 text-sm text-[#24393f]">
                  <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-[#95b6a5]/28 text-xs font-bold text-[#1f5b66]">✓</span>
                  Suporte técnico prioritário
                </li>
              </ul>
            </div>

            {/* CTA */}
            <Link
              href="/signin?next=%2Fsingle_purchase_onboarding"
              className="btn-primary mt-8 block w-full py-4 text-base text-center focus:outline-none focus:ring-2 focus:ring-[#24393f]/50 focus:ring-offset-2"
              style={{
                background: "linear-gradient(145deg, #1f5b66, #24393f)",
                boxShadow: "0 4px 14px rgba(36, 57, 63, 0.25)",
              }}
            >
              Comprar Combo →
            </Link>

            <p className="mt-5 text-center text-xs leading-relaxed text-[#1f5b66]/82">
              Garantia de 30 dias para os óculos. Satisfação garantida ou seu dinheiro de volta.
            </p>
          </GlassCard>
        </div>
      </div>
    </section>
  );
}
