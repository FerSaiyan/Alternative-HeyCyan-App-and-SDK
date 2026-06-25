/**
 * UnderHeroAnimation - Atmospheric transition below hero
 *
 * Uses animated WebP as integrated atmospheric transition
 * - Decorative only (aria-hidden)
 * - Animated WebP with static poster fallback for reduced motion
 * - Soft gradient masks for cinematic blending between hero and next section
 * - Graceful degradation through Next.js Image
 */
"use client";

import { useEffect, useRef, useState } from "react";
import Image from "next/image";

const TOTAL_FRAMES = 144;

function frameSrc(index: number): string {
  const clamped = Math.min(Math.max(index, 0), TOTAL_FRAMES - 1);
  return `/assets/website-package-1/two-vials-frames/frame-${String(clamped).padStart(3, "0")}.jpg`;
}

function clamp(value: number): number {
  if (value < 0) return 0;
  if (value > 1) return 1;
  return value;
}

export function UnderHeroAnimation() {
  const [isReducedMotion, setIsReducedMotion] = useState(false);
  const [currentFrame, setCurrentFrame] = useState(0);
  const [frameLoadError, setFrameLoadError] = useState(false);
  const [viewportRatio, setViewportRatio] = useState(0.56);
  const sectionRef = useRef<HTMLElement>(null);
  const frameImagesRef = useRef<(HTMLImageElement | null)[]>([]);
  const activeFrameRef = useRef(-1);

  useEffect(() => {
    frameImagesRef.current = new Array(TOTAL_FRAMES).fill(null);

    const preload = (index: number) => {
      if (frameImagesRef.current[index]) return;

      const img = new window.Image();
      img.src = frameSrc(index);
      frameImagesRef.current[index] = img;
    };

    for (let i = 0; i < Math.min(TOTAL_FRAMES, 12); i += 1) {
      preload(i);
    }
  }, []);

  useEffect(() => {
    const mediaQuery = window.matchMedia("(prefers-reduced-motion: reduce)");
    const onChange = () => setIsReducedMotion(mediaQuery.matches);

    onChange();
    mediaQuery.addEventListener("change", onChange);
    return () => mediaQuery.removeEventListener("change", onChange);
  }, []);

  useEffect(() => {
    const updateViewportRatio = () => {
      const height = Math.max(window.innerHeight, 1);
      const width = Math.max(window.innerWidth, 1);
      setViewportRatio(width / height);
    };

    updateViewportRatio();
    window.addEventListener("resize", updateViewportRatio);
    return () => window.removeEventListener("resize", updateViewportRatio);
  }, []);

  useEffect(() => {
    if (isReducedMotion) return;

    const section = sectionRef.current;
    if (!section) return;

    const drawFrame = (frameIndex: number) => {
      if (frameIndex === activeFrameRef.current) return;

      const index = Math.min(Math.max(frameIndex, 0), TOTAL_FRAMES - 1);
      activeFrameRef.current = index;
      setCurrentFrame(index);

      let img = frameImagesRef.current[index];
      if (!img) {
        img = new window.Image();
        img.src = frameSrc(index);
        frameImagesRef.current[index] = img;
      }

      for (let i = 1; i <= 6; i += 1) {
        const next = index + i;
        if (next < TOTAL_FRAMES && !frameImagesRef.current[next]) {
          const nextImg = new window.Image();
          nextImg.src = frameSrc(next);
          frameImagesRef.current[next] = nextImg;
        }
      }
    };

    const updateByScroll = () => {
      const rect = section.getBoundingClientRect();
      const viewportHeight = window.innerHeight || 1;
      const travel = Math.max(rect.height - viewportHeight, 1);
      const progress = clamp(-rect.top / travel);
      const frameIndex = Math.round(progress * (TOTAL_FRAMES - 1));
      drawFrame(frameIndex);
    };

    let rafId = 0;
    const tick = () => {
      updateByScroll();
      rafId = window.requestAnimationFrame(tick);
    };

    const handleResize = () => {
      activeFrameRef.current = -1;
      updateByScroll();
    };

    updateByScroll();
    tick();
    window.addEventListener("resize", handleResize);

    return () => {
      window.cancelAnimationFrame(rafId);
      window.removeEventListener("resize", handleResize);
    };
  }, [isReducedMotion]);

  const showAnimation = !isReducedMotion;
  const isNarrowOuter = viewportRatio <= 0.5;
  const isInnerFold = viewportRatio > 0.5 && viewportRatio <= 0.95;
  const isPhoneLike = viewportRatio <= 0.62;
  const useCoverFit = viewportRatio <= 1.05;
  const frameScale = useCoverFit ? (isNarrowOuter ? 1.08 : isInnerFold ? 1.01 : 1) : 1;
  const mediaClass = `${useCoverFit ? "object-cover" : "object-contain"} object-center`;
  const mediaStyle = useCoverFit ? { transform: `scale(${frameScale})` } : undefined;
  const pinHeightClass = isPhoneLike ? "h-[165vh]" : "h-[200vh] sm:h-[220vh] lg:h-[240vh]";
  const stickyViewportClass = isPhoneLike ? "h-[82svh] top-[9svh]" : "h-screen top-0";

  return (
    <section
      ref={sectionRef}
      className="relative -mt-6 w-full sm:-mt-8 lg:-mt-10"
      aria-hidden="true"
      data-testid="under-hero"
      data-state={showAnimation ? "animated" : "poster"}
    >
      {showAnimation ? (
        <div className={`relative ${pinHeightClass}`}>
          <div className={`sticky ${stickyViewportClass}`}>
            <div className="relative h-full w-full overflow-hidden bg-[#e4eee8]">
              <Image
                src="/assets/website-package-1/two-vials-poster.webp"
                alt=""
                fill
                unoptimized
                priority
                className={mediaClass}
                style={mediaStyle}
              />
              <Image
                src={frameSrc(currentFrame)}
                alt=""
                fill
                unoptimized
                priority
                className={mediaClass}
                onError={() => setFrameLoadError(true)}
                style={{
                  ...mediaStyle,
                  opacity: frameLoadError ? 0 : 1,
                }}
                data-testid="under-hero-animated-media"
              />

              {/* Top fade — extended blend from hero's sky/slate overlay into the animation */}
              <div
                className="absolute inset-x-0 top-0 h-28 sm:h-28 lg:h-32 pointer-events-none"
                style={{
                  background: "linear-gradient(to bottom, rgba(228,238,232,0.98), rgba(228,238,232,0.55) 42%, transparent)",
                }}
                aria-hidden="true"
              />

              {/* Bottom gradient — deeper blend into the next section's background */}
              <div
                className="absolute inset-x-0 bottom-0 h-56 sm:h-44 lg:h-52 pointer-events-none"
                style={{
                  background: "linear-gradient(to bottom, transparent 0%, rgba(228,238,232,0.56) 48%, rgba(250,250,247,0.95) 82%, var(--bg-base) 100%)",
                }}
                aria-hidden="true"
              />
            </div>
          </div>
        </div>
      ) : (
        <div className="relative w-full aspect-[16/9] sm:aspect-[16/8] lg:aspect-[16/6.8] overflow-hidden bg-[#e4eee8]">
          <Image
            src="/assets/website-package-1/two-vials-poster.webp"
            alt=""
            fill
            className={mediaClass}
            style={mediaStyle}
            unoptimized
            data-testid="under-hero-poster-media"
          />

          <div
            className="absolute inset-x-0 top-0 h-28 sm:h-28 lg:h-32 pointer-events-none"
            style={{
              background: "linear-gradient(to bottom, rgba(228,238,232,0.98), rgba(228,238,232,0.55) 42%, transparent)",
            }}
            aria-hidden="true"
          />

          <div
            className="absolute inset-x-0 bottom-0 h-56 sm:h-44 lg:h-52 pointer-events-none"
            style={{
              background: "linear-gradient(to bottom, transparent 0%, rgba(228,238,232,0.56) 48%, rgba(250,250,247,0.95) 82%, var(--bg-base) 100%)",
            }}
            aria-hidden="true"
          />
        </div>
      )}
    </section>
  );
}
