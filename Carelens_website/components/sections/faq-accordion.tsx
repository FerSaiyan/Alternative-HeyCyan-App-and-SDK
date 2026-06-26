"use client";

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
    question: "What is CyanBridge?",
    answer:
      "CyanBridge is a software subscription that routes supported smart-glasses requests to chat, voice, and image AI models.",
  },
  {
    id: "faq-como-funcionam-os-oculos",
    question: "Do you sell the smart glasses on this site?",
    answer:
      "No. This website sells software access only. CyanBridge works with supported smart-glasses workflows, but hardware is not sold through this checkout domain.",
  },
  {
    id: "faq-quem-pode-usar",
    question: "What do paid plans include?",
    answer:
      "Paid plans include managed access to AI models, monthly quotas, billing support, and the CyanBridge relay used by the companion app.",
  },
  {
    id: "faq-qual-o-custo",
    question: "How much does it cost?",
    answer:
      "CyanBridge offers monthly software plans starting at $1, with higher tiers at $5 and $20 depending on your quota needs.",
  },
  {
    id: "faq-cancelar-assinatura",
    question: "Can I cancel my subscription?",
    answer:
      "Yes. You can cancel your software subscription from your account or by contacting support. Access continues until the end of the current billing period.",
  },
  {
    id: "faq-monitoramento-familiar",
    question: "Is CyanBridge a medical or high-stakes decision system?",
    answer:
      "No. CyanBridge is not a medical service and should not be used to make healthcare, diagnosis, treatment, employment, credit, housing, or other high-stakes personal decisions.",
  },
];

function FAQItemComponent({ item, index, isOpen, onToggle }: { item: FAQItem; index: number; isOpen: boolean; onToggle: () => void }) {
  const contentRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const numberLabel = String(index + 1).padStart(2, "0");

  useGSAP(
    () => {
      if (!contentRef.current) return;

      const prefersReducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      if (prefersReducedMotion) {
        contentRef.current.style.gridTemplateRows = isOpen ? "1fr" : "0fr";
        contentRef.current.style.opacity = isOpen ? "1" : "0";
        return;
      }

      gsap.to(contentRef.current, {
        gridTemplateRows: isOpen ? "1fr" : "0fr",
        opacity: isOpen ? "1" : "0",
        duration: 0.35,
        ease: "power2.out",
      });

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
        className="faq-question flex w-full items-start gap-4 rounded-lg py-4 text-left focus:outline-none focus:ring-2 focus:ring-brand/50 focus:ring-offset-2"
      >
        <span
          className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-[#95b6a5]/24 text-xs font-mono font-medium text-[#1f5b66]/82"
          aria-hidden="true"
        >
          [{numberLabel}]
        </span>
        <span className="flex-1 text-base font-semibold text-[#24393f]">{item.question}</span>
        <span
          className="faq-chevron flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-[#95b6a5]/24 text-base font-semibold leading-none text-[#1f5b66]"
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
        <div className="overflow-hidden pb-4 pl-12 text-sm leading-relaxed text-[#1f5b66]/88">
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
    <section id="faq" ref={sectionRef} className="space-y-6 scroll-mt-20 sm:scroll-mt-24" aria-label="Frequently asked questions">
      <div ref={headerRef} className="space-y-1">
        <h2 className="text-2xl font-semibold tracking-tight text-[#24393f] sm:text-3xl">
          Frequently asked questions
        </h2>
        <p className="text-base text-[#1f5b66]/88">
          Questions about the software, smart-glasses support, and billing.
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
