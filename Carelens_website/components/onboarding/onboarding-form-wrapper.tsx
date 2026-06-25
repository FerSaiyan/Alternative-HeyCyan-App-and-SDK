"use client";

import { useRef, ReactNode } from "react";
import { useGSAP } from "@gsap/react";
import gsap from "gsap";

type OnboardingFormWrapperProps = {
  children: ReactNode;
};

export function OnboardingFormWrapper({ children }: OnboardingFormWrapperProps) {
  const containerRef = useRef<HTMLDivElement>(null);

  useGSAP(
    () => {
      if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
        return;
      }

      const elements = containerRef.current?.querySelectorAll(".animate-in");
      if (elements) {
        gsap.fromTo(
          elements,
          { opacity: 0, y: 20 },
          {
            opacity: 1,
            y: 0,
            duration: 0.6,
            stagger: 0.1,
            ease: "power2.out",
          },
        );
      }
    },
    { scope: containerRef },
  );

  return (
    <div ref={containerRef} className="onboarding-form-wrapper">
      {children}
    </div>
  );
}
