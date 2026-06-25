"use client";

import Image from "next/image";
import { useRef, useState, useEffect } from "react";
import { useGSAP } from "@gsap/react";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { SectionHeading } from "@/components/ui/section-heading";

gsap.registerPlugin(ScrollTrigger);

const services = [
  {
    label: "Etapa 1",
    title: "Aquisição dos Óculos HeyCyan",
    description:
      "Óculos com câmera HD POV, microfone direcional e alto-falante integrado para assistência contínua.",
    imageSrc: "/assets/website-package-1/triagem_carelens.png",
    imageAlt: "Aquisição dos Óculos HeyCyan",
  },
  {
    label: "Etapa 2",
    title: "Configuração da IA Personalizada",
    description:
      "A IA aprende a rotina, medicamentos e necessidades específicas de cada idoso.",
    imageSrc: "/assets/website-package-1/consulta_prescricao_carelens.png",
    imageAlt: "Configuração da IA Personalizada CareLens",
  },
  {
    label: "Etapa 3",
    title: "Monitoramento Familiar",
    description:
      "Painel completo para familiares acompanharem atividade, lembretes e interações da IA.",
    imageSrc: "/assets/website-package-1/aquisicao_farmacias_frete_carelens.png",
    imageAlt: "Monitoramento Familiar CareLens",
  },
  {
    label: "Etapa 4",
    title: "Acompanhamento Contínuo",
    description:
      "Suporte técnico e atualizações constantes da IA para melhorar o cuidado.",
    imageSrc: "/assets/website-package-1/acompanhamento_carelens.png",
    imageAlt: "Acompanhamento Contínuo CareLens",
  },
];

export function ServiceCardsSection() {
  const sectionRef = useRef<HTMLElement>(null);
  const headingRef = useRef<HTMLDivElement>(null);
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

  // GSAP scroll-triggered card animations with timeline + stagger
  useGSAP(
    () => {
      if (isReducedMotion) return;

      const ctx = gsap.context(() => {
        // Heading reveal on scroll into view
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

        // Set initial state for all cards
        gsap.set(cards, {
          opacity: 0,
          y: 48,
          scale: 0.92,
          rotateX: 8,
        });

        // Desktop (≥1024px): Scroll-scrub timeline with stagger for sequential reveal
        // Mobile/tablet: Lighter trigger-based animation
        const mm = gsap.matchMedia();

        // Desktop: sequential scroll-scrub timeline
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

        // Mobile/tablet: lighter trigger-based (non-scrub) for smoother feel
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
            eyebrow="COMO FUNCIONA"
            title="Da aquisição ao cuidado contínuo:"
            className="mb-10"
          />
        </div>

        {/* Screen-reader accessible description of the services */}
        <div className="sr-only">
          <h2>Nossos serviços</h2>
          <ol>
            <li>
              <strong>Aquisição dos Óculos HeyCyan</strong> - Óculos com câmera HD POV, microfone direcional e alto-falante integrado para assistência contínua.</li>
            <li>
              <strong>Configuração da IA Personalizada</strong> - A IA aprende a rotina, medicamentos e necessidades específicas de cada idoso.</li>
            <li>
              <strong>Monitoramento Familiar</strong> - Painel completo para familiares acompanharem atividade, lembretes e interações da IA.</li>
            <li>
              <strong>Acompanhamento Contínuo</strong> - Suporte técnico e atualizações constantes da IA para melhorar o cuidado.</li>
          </ol>
        </div>

        {/* Card grid with scroll-triggered raise-forward animation */}
        <div
          ref={cardsRef}
          className="mt-10 -mx-4 flex gap-4 overflow-x-auto px-4 pb-4 sm:mx-0 sm:grid sm:grid-cols-2 sm:overflow-visible sm:px-0 sm:pb-0 lg:grid-cols-4"
        >
          {services.map((service) => (
            <div
              key={service.title}
              className="service-card relative w-[85%] flex-shrink-0 overflow-hidden rounded-2xl shadow-card aspect-[4/5] sm:w-auto"
            >
              <Image
                src={service.imageSrc}
                alt={service.imageAlt}
                fill
                className="object-cover object-center"
                sizes="(max-width: 639px) 85vw, (max-width: 1023px) 50vw, 25vw"
              />
              <div
                className="absolute inset-0 bg-gradient-to-t from-black/75 via-black/35 to-black/10"
                aria-hidden="true"
              />
              <div className="absolute inset-x-0 bottom-0 z-10">
                <div className="flex h-[148px] flex-col overflow-hidden rounded-t-2xl border-x border-t border-[#cdbe98]/45 bg-[#24393f]/58 px-4 py-3 backdrop-blur-md sm:h-[156px] sm:px-5 lg:h-[164px]">
                  <p className="text-base font-semibold text-white">{service.title}</p>
                  <p className="mt-1.5 text-sm leading-snug text-white/95">
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
