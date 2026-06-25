"use client";

import { ButtonHTMLAttributes, ReactNode } from "react";

type OnboardingCtaProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: "primary" | "secondary";
  children: ReactNode;
};

export function OnboardingCta({
  variant = "primary",
  children,
  className = "",
  ...props
}: OnboardingCtaProps) {
  const baseClasses = "onboarding-cta";
  const variantClasses = variant === "primary" ? "onboarding-cta-primary" : "onboarding-cta-secondary";

  return (
    <button className={`${baseClasses} ${variantClasses} ${className}`} {...props}>
      {children}
    </button>
  );
}