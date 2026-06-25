import { ReactNode } from "react";

type GlassCardProps = {
  children: ReactNode;
  className?: string;
  variant?: "default" | "hero" | "hover" | "minimal";
};

/**
 * GlassCard - Tokenized glassmorphism component
 *
 * Variants:
 * - default: Subtle content cards (blur: 16px, opacity: 0.58, radius: 24px)
 * - hero: Expressive hero sections (blur: 22px, opacity: 0.68, radius: 32px)
 * - hover: Interactive hoverable cards (blur: 16px, opacity: 0.65, radius: 24px)
 * - minimal: Consistent cards (blur: 16px, opacity: 0.58, radius: 24px)
 */
export function GlassCard({
  children,
  className = "",
  variant = "default",
}: GlassCardProps) {
  const variantClasses = {
    default: "glass-card rounded-card",
    hero: "glass-hero rounded-card-xl",
    hover: "glass-card-hover rounded-card",
    minimal: "glass-minimal rounded-card",
  } as const;

  return (
    <div className={`${variantClasses[variant]} ${className}`.trim()}>
      {children}
    </div>
  );
}