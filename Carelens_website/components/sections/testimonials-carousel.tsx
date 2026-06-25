"use client";

import { useEffect, useState, useRef } from "react";
import Image from "next/image";
import { useGSAP } from "@gsap/react";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { GlassCard } from "@/components/ui/glass-card";

gsap.registerPlugin(ScrollTrigger);

type Testimonial = {
  id: string;
  quote: string;
  image?: string;
  alt?: string;
  beforeImage?: string;
  afterImage?: string;
  beforeAlt?: string;
  afterAlt?: string;
  instagramHandle?: string;
  instagramUrl?: string;
};

const FALLBACK_IMAGE = "/assets/website-package-1/Central Social Prof Photo 1.png";
const FALLBACK_ALT = "Foto do paciente";

function getItemsPerPage(testimonialCount: number): number {
  if (typeof window === "undefined") return 1;
  if (testimonialCount <= 0) return 1;

  const width = window.innerWidth;
  if (width >= 1280) return Math.min(4, testimonialCount);
  if (width >= 1024) return Math.min(3, testimonialCount);
  if (width >= 640) return Math.min(2, testimonialCount);
  return 1;
}

function ClientCard({
  testimonial,
  isReducedMotion,
}: {
  testimonial: Testimonial & { imageSrc: string; altText: string; handle: string; url: string };
  isReducedMotion: boolean;
}) {
  const [showBefore, setShowBefore] = useState(false);
  const [hasBeforeError, setHasBeforeError] = useState(false);
  const cardRef = useRef<HTMLDivElement>(null);

  const afterImage = testimonial.afterImage || testimonial.imageSrc;
  const beforeImage = testimonial.beforeImage;
  const afterAlt = testimonial.afterAlt || testimonial.altText;
  const beforeAlt = testimonial.beforeAlt || "Antes do tratamento";
  const isShowingBefore = Boolean(beforeImage && !hasBeforeError && showBefore);

  // Light parallax on card hover / scroll
  useEffect(() => {
    if (isReducedMotion) return;

    const card = cardRef.current;
    if (!card) return;

    let rafId = 0;
    const handleMove = (e: MouseEvent | TouchEvent) => {
      const rect = card.getBoundingClientRect();
      const cx = rect.left + rect.width / 2;
      const cy = rect.top + rect.height / 2;
      let clientX: number, clientY: number;
      if ("touches" in e) {
        clientX = e.touches[0]?.clientX ?? cx;
        clientY = e.touches[0]?.clientY ?? cy;
      } else {
        clientX = e.clientX;
        clientY = e.clientY;
      }
      const dx = (clientX - cx) / rect.width;
      const dy = (clientY - cy) / rect.height;
      cancelAnimationFrame(rafId);
      rafId = requestAnimationFrame(() => {
        const imgEl = card.querySelector(".card-image-wrap");
        if (imgEl) {
          (imgEl as HTMLElement).style.transform = `translate(${dx * 6}px, ${dy * 4}px) scale(1.05)`;
        }
      });
    };

    const handleLeave = () => {
      const imgEl = card.querySelector(".card-image-wrap");
      if (imgEl) {
        (imgEl as HTMLElement).style.transform = "translate(0, 0) scale(1)";
      }
    };

    card.addEventListener("mousemove", handleMove);
    card.addEventListener("touchmove", handleMove, { passive: true });
    card.addEventListener("mouseleave", handleLeave);

    return () => {
      card.removeEventListener("mousemove", handleMove);
      card.removeEventListener("touchmove", handleMove);
      card.removeEventListener("mouseleave", handleLeave);
      cancelAnimationFrame(rafId);
    };
  }, [isReducedMotion]);

  useEffect(() => {
    if (!beforeImage || hasBeforeError || isReducedMotion) return;

    const interval = setInterval(() => {
      setShowBefore((prev) => !prev);
    }, 4000);

    return () => clearInterval(interval);
  }, [beforeImage, hasBeforeError, isReducedMotion]);

  const handleBeforeError = () => {
    setHasBeforeError(true);
  };

  return (
    <div ref={cardRef} className="testimonial-card group cursor-default">
      <GlassCard variant="hero" className="overflow-hidden p-0" data-testid="social-proof-card">
        <div className="flex flex-col gap-3 p-3 sm:gap-4 sm:p-4 lg:gap-4 lg:p-4">
          <div
            className="card-image-wrap relative h-[260px] w-[208px] self-center overflow-hidden rounded-xl bg-gradient-to-br from-[#fafaf7] to-[#fafaf7] transition-transform duration-300 ease-out sm:h-[260px] md:h-[260px] lg:h-[280px] lg:w-[224px]"
            data-testid="social-proof-frame"
          >
            <Image
              src={afterImage}
              alt={afterAlt}
              fill
              sizes="(max-width: 640px) 100vw, (max-width: 1024px) 50vw, (max-width: 1280px) 33vw, 25vw"
              loading="eager"
              className="object-contain object-center"
            />
            {beforeImage && !hasBeforeError && (
              <div
                className="absolute inset-0 transition-all duration-700 ease-in-out"
                style={{
                  clipPath: showBefore ? "inset(0)" : "inset(0 0 100% 0)",
                }}
              >
                <Image
                  src={beforeImage}
                  alt={beforeAlt}
                  fill
                  sizes="(max-width: 640px) 100vw, (max-width: 1024px) 50vw, (max-width: 1280px) 33vw, 25vw"
                  loading="eager"
                  className="object-contain object-center"
                  onError={handleBeforeError}
                />
              </div>
            )}

            <div className="pointer-events-none absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/45 via-black/10 to-transparent p-2">
              <span className="inline-flex rounded-full border border-white/35 bg-black/35 px-2 py-0.5 text-[9px] font-semibold uppercase tracking-[0.08em] text-white/95 backdrop-blur-sm sm:text-[10px]">
                {isShowingBefore ? "Antes" : "Depois"}
              </span>
            </div>
          </div>

          <div className="flex min-w-0 flex-1 flex-col justify-center">
            <div className="mb-2 h-px w-7 bg-[var(--brand)]/40" aria-hidden="true" />
            <blockquote className="text-xs italic leading-relaxed text-[#24393f]/90 sm:text-sm">
              &ldquo;{testimonial.quote}&rdquo;
            </blockquote>
            <a
              href={testimonial.url}
              target="_blank"
              rel="noopener noreferrer"
              className="mt-1 inline-flex w-fit items-center rounded-full border border-[#95b6a5]/55 bg-[#fafaf7] px-2 py-0.5 text-[10px] font-semibold text-[#1f5b66] hover:bg-[#95b6a5]/18 focus:outline-none focus:ring-2 focus:ring-[#95b6a5]/55 focus:ring-offset-2 sm:text-xs"
            >
              {testimonial.handle}
            </a>
          </div>
        </div>
      </GlassCard>
    </div>
  );
}

export function TestimonialsCarousel({ testimonials }: { testimonials: Testimonial[] }) {
  const [itemsPerPage, setItemsPerPage] = useState(1);
  const [currentPage, setCurrentPage] = useState(0);
  const sectionRef = useRef<HTMLDivElement>(null);
  const [isReducedMotion, setIsReducedMotion] = useState(false);

  // Detect reduced motion preference
  useEffect(() => {
    const mediaQuery = window.matchMedia("(prefers-reduced-motion: reduce)");
    const onChange = () => setIsReducedMotion(mediaQuery.matches);
    onChange();
    mediaQuery.addEventListener("change", onChange);
    return () => mediaQuery.removeEventListener("change", onChange);
  }, []);

  // GSAP entrance + parallax for testimonial cards
  useGSAP(
    () => {
      if (isReducedMotion) return;

      const ctx = gsap.context(() => {
        // Stagger entrance on viewport enter
        const cards = sectionRef.current?.querySelectorAll(".testimonial-card") || [];
        gsap.fromTo(
          cards,
          { opacity: 0, y: 36 },
          {
            opacity: 1,
            y: 0,
            duration: 0.65,
            stagger: 0.1,
            ease: "power2.out",
            scrollTrigger: {
              trigger: sectionRef.current,
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

  const normalizedTestimonials = testimonials.map((item) => ({
    ...item,
    imageSrc: item.image || FALLBACK_IMAGE,
    altText: item.alt || FALLBACK_ALT,
    handle: item.instagramHandle || "@perfil",
    url: item.instagramUrl || "#",
  }));

  const totalPages = normalizedTestimonials.length > 0 ? Math.ceil(normalizedTestimonials.length / itemsPerPage) : 0;

  useEffect(() => {
    const updateItemsPerPage = () => {
      const newItemsPerPage = getItemsPerPage(normalizedTestimonials.length);
      setItemsPerPage(newItemsPerPage);
      setCurrentPage(0);
    };

    updateItemsPerPage();
    window.addEventListener("resize", updateItemsPerPage);
    return () => window.removeEventListener("resize", updateItemsPerPage);
  }, [normalizedTestimonials.length]);

  useEffect(() => {
    if (totalPages < 2) {
      return;
    }

    const interval = window.setInterval(() => {
      setCurrentPage((prev) => (prev + 1) % totalPages);
    }, 8000);

    return () => window.clearInterval(interval);
  }, [totalPages]);

  if (normalizedTestimonials.length === 0) {
    return null;
  }

  function goPrevious() {
    setCurrentPage((prev) => (prev === 0 ? totalPages - 1 : prev - 1));
  }

  function goNext() {
    setCurrentPage((prev) => (prev + 1) % totalPages);
  }

  const pages = Array.from({ length: totalPages }, (_, pageIndex) => {
    const startIndex = pageIndex * itemsPerPage;
    return normalizedTestimonials.slice(startIndex, startIndex + itemsPerPage);
  });

  return (
    <div ref={sectionRef} className="relative" suppressHydrationWarning>
      <div className="overflow-hidden">
        <div
          className="flex transition-transform duration-500 ease-out"
          style={{ transform: `translateX(-${currentPage * 100}%)` }}
        >
          {pages.map((pageItems, pageIndex) => (
            <div key={pageIndex} className="w-full shrink-0">
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
                {pageItems.map((item) => (
                  <div key={item.id}>
                    <ClientCard testimonial={item} isReducedMotion={isReducedMotion} />
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      </div>

      {totalPages > 1 && (
        <div className="mt-5 flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-2">
            {Array.from({ length: totalPages }).map((_, index) => (
              <button
                key={index}
                type="button"
                aria-label={`Ir para página ${index + 1}`}
                onClick={() => setCurrentPage(index)}
                className={`h-2.5 rounded-full transition-all ${
                  currentPage === index ? "w-7 bg-[#1f5b66]" : "w-2.5 bg-[#cdbe98]"
                }`}
              />
            ))}
          </div>

          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={goPrevious}
              className="inline-flex h-10 w-10 items-center justify-center rounded-full border border-[#cdbe98]/70 bg-white text-[#24393f] hover:bg-[#fafaf7] focus:outline-none focus:ring-2 focus:ring-brand/50"
              aria-label="Página anterior"
            >
              ←
            </button>
            <button
              type="button"
              onClick={goNext}
              className="inline-flex h-10 w-10 items-center justify-center rounded-full border border-[#cdbe98]/70 bg-white text-[#24393f] hover:bg-[#fafaf7] focus:outline-none focus:ring-2 focus:ring-brand/50"
              aria-label="Próxima página"
            >
              →
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
