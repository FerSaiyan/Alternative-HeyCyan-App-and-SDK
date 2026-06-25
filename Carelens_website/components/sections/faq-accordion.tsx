"use client";

/**
 * FAQAccordion - Keyboard accessible FAQ accordion component
 *
 * Section 9 of 10:
 * - 6 Q/A pairs in Portuguese with medical compliance
 * - Minimal numbered rows style [01]-[06]
 * - Plus/minus toggle indicators
 * - Proper ARIA attributes for accessibility
 * - Expand/collapse animation with GSAP polish
 */
import { useState, useRef, useEffect } from "react";
import { useGSAP } from "@gsap/react";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";

gsap.registerPlugin(ScrollTrigger);

export interface FAQItem {
  id: string;
  question: string;
  answer: string;
}

const faqItems: FAQItem[] = [
  {
    id: "faq-o-que-e",
    question: "O que é a CareLens?",
    answer:
      "A CareLens é uma plataforma de tecnologia assistiva que usa IA para apoiar idosos no dia a dia, através dos óculos HeyCyan.",
  },
  {
    id: "faq-como-funcionam-os-oculos",
    question: "Como funcionam os óculos?",
    answer:
      "Os óculos têm câmera HD, microfone e alto-falante. A IA processa o que vê e ouve para ajudar com lembretes, alertas e companhia.",
  },
  {
    id: "faq-quem-pode-usar",
    question: "Quem pode usar?",
    answer:
      "Qualquer pessoa idosa. O familiar configura a IA durante o onboarding com informações de saúde e rotina.",
  },
  {
    id: "faq-qual-o-custo",
    question: "Qual o custo?",
    answer:
      "Os óculos custam R$ 250,00 e a assinatura mensal de IA é R$ 50,00. Garantia de 30 dias.",
  },
  {
    id: "faq-cancelar-assinatura",
    question: "Posso cancelar a assinatura?",
    answer:
      "Sim, após 3 meses do primeiro pagamento. Os óculos continuam funcionando como dispositivo básico.",
  },
  {
    id: "faq-monitoramento-familiar",
    question: "Como funciona o monitoramento familiar?",
    answer:
      "O familiar tem acesso a um painel com atividade, lembretes, interações da IA e alertas em tempo real.",
  },
];

function FAQItemComponent({ item, index, isOpen, onToggle }: { item: FAQItem; index: number; isOpen: boolean; onToggle: () => void }) {
  const contentRef = useRef<HTMLDivElement>(null);
  const contentInnerRef = useRef<HTMLParagraphElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const numberLabel = String(index + 1).padStart(2, "0");

  // GSAP expand/collapse animation using grid-template-rows for dynamic height
  useGSAP(
    () => {
      if (!contentRef.current) return;

      // Get reduced motion preference
      const prefersReducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      if (prefersReducedMotion) {
        // No animation, just toggle display state
        contentRef.current.style.gridTemplateRows = isOpen ? "1fr" : "0fr";
        contentRef.current.style.opacity = isOpen ? "1" : "0";
        return;
      }

      // Animate using grid-template-rows (no fixed max-height needed)
      const animateTo = isOpen ? "1fr" : "0fr";
      const opacityTo = isOpen ? "1" : "0";
      
      gsap.to(contentRef.current, {
        gridTemplateRows: animateTo,
        opacity: opacityTo,
        duration: 0.35,
        ease: "power2.out",
      });

      // Animate the rotate icon
      const chevron = buttonRef.current?.querySelector(".faq-chevron") as HTMLElement | null;
      if (chevron) {
        gsap.to(chevron, {
          rotation: isOpen ? 180 : 0,
          duration: 0.3,
          ease: "power2.out",
        });
      }
    },
    { scope: contentRef, dependencies: [isOpen] },
  );

  // Handle keyboard navigation
  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      onToggle();
    }
  };

  return (
    <div id={item.id} className="scroll-mt-24 border-b border-[#cdbe98]/45 last:border-b-0">
      <button
        ref={buttonRef}
        onClick={onToggle}
        onKeyDown={handleKeyDown}
        aria-expanded={isOpen}
        aria-controls={`faq-answer-${item.id}`}
        id={`faq-question-${item.id}`}
        className="faq-question flex w-full items-start gap-4 py-4 text-left focus:outline-none focus:ring-2 focus:ring-brand/50 focus:ring-offset-2 rounded-lg"
      >
        {/* Minimal numbered badge [01]-[06] */}
        <span
          className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-[#95b6a5]/24 text-xs font-mono font-medium text-[#1f5b66]/82"
          aria-hidden="true"
        >
          [{numberLabel}]
        </span>
        <span className="flex-1 text-base font-semibold text-[#24393f]">{item.question}</span>
        <span
          className="faq-chevron flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-[#95b6a5]/24 text-[#1f5b66] text-base font-semibold leading-none"
          aria-hidden="true"
        >
          {isOpen ? "−" : "+"}
        </span>
      </button>
      <div
        ref={contentRef}
        id={`faq-answer-${item.id}`}
        role="region"
        aria-hidden={!isOpen}
        aria-labelledby={`faq-question-${item.id}`}
        className="faq-answer grid transition-all duration-300 ease-out"
        style={{
          gridTemplateRows: isOpen ? "1fr" : "0fr",
          opacity: isOpen ? 1 : 0,
        }}
      >
        <div ref={contentInnerRef} className="overflow-hidden pb-4 pl-12 text-sm leading-relaxed text-[#1f5b66]/88">
          {item.answer}
        </div>
      </div>
    </div>
  );
}

export function FAQAccordion() {
  const sectionRef = useRef<HTMLElement>(null);
  const headerRef = useRef<HTMLDivElement>(null);
  const accordionRef = useRef<HTMLDivElement>(null);
  const [openIndex, setOpenIndex] = useState<number | null>(0);
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

        // Accordion items stagger reveal
        gsap.fromTo(
          accordionRef.current?.querySelectorAll(".faq-question") || [],
          { opacity: 0, y: 20 },
          {
            opacity: 1,
            y: 0,
            duration: 0.5,
            stagger: 0.1,
            ease: "power2.out",
            scrollTrigger: {
              trigger: accordionRef.current,
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

  const handleToggle = (index: number) => {
    setOpenIndex(openIndex === index ? null : index);
  };

  // Close on escape key
  useEffect(() => {
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === "Escape" && openIndex !== null) {
        setOpenIndex(null);
      }
    };
    document.addEventListener("keydown", handleEscape);
    return () => document.removeEventListener("keydown", handleEscape);
  }, [openIndex]);

  useEffect(() => {
    const syncOpenItemWithHash = () => {
      const hash = window.location.hash;
      if (!hash.startsWith("#faq-")) return;
      const targetId = hash.slice(1);
      const targetIndex = faqItems.findIndex((item) => item.id === targetId);
      if (targetIndex !== -1) {
        setOpenIndex(targetIndex);
      }
    };

    const handleAnchorClick = (event: MouseEvent) => {
      const target = event.target;
      if (!(target instanceof Element)) return;

      const link = target.closest('a[href^="#faq-"]');
      if (!(link instanceof HTMLAnchorElement)) return;

      const href = link.getAttribute("href");
      if (!href) return;

      const targetId = href.slice(1);
      const targetIndex = faqItems.findIndex((item) => item.id === targetId);
      if (targetIndex !== -1) {
        setOpenIndex(targetIndex);
      }
    };

    syncOpenItemWithHash();
    window.addEventListener("hashchange", syncOpenItemWithHash);
    document.addEventListener("click", handleAnchorClick);
    return () => {
      window.removeEventListener("hashchange", syncOpenItemWithHash);
      document.removeEventListener("click", handleAnchorClick);
    };
  }, []);

  return (
    <section id="faq" ref={sectionRef} className="space-y-6 scroll-mt-20 sm:scroll-mt-24" aria-label="Perguntas frequentes">
      <div ref={headerRef} className="space-y-1">
        <h2 className="text-2xl font-semibold tracking-tight text-[#24393f] sm:text-3xl">
          Perguntas frequentes
        </h2>
        <p className="text-base text-[#1f5b66]/88">
          Tire suas dúvidas sobre a CareLens, os óculos e a assinatura de IA.
        </p>
      </div>
      <div ref={accordionRef} className="rounded-xl border border-[#cdbe98]/45 bg-white/60 p-4 shadow-card backdrop-blur-sm sm:p-5">
        {faqItems.map((item, index) => (
          <FAQItemComponent
            key={item.id}
            item={item}
            index={index}
            isOpen={openIndex === index}
            onToggle={() => handleToggle(index)}
          />
        ))}
      </div>
    </section>
  );
}
