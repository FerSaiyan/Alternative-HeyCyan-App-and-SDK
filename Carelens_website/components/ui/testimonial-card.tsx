import Image from "next/image";
import { Testimonial, getInitials } from "@/lib/testimonials";

type TestimonialCardProps = {
  testimonial: Testimonial;
};

/**
 * TestimonialCard - Displays a single patient testimonial
 *
 * Supports:
 * - Photo display (when available)
 * - Initials fallback if no photo
 * - First name and cityState
 * - Quote text
 * - Optional contextLabel badge
 *
 * Note: optional internalNotes is internal-only and NOT rendered
 */
export function TestimonialCard({ testimonial }: TestimonialCardProps) {
  const { photoUrl, firstName, initials, cityState, quote, contextLabel } =
    testimonial;

  const displayInitials = getInitials(firstName, initials);

  return (
    <div className="glass-minimal rounded-xl p-5">
      <div className="flex items-start gap-3">
        {/* Avatar or Initials */}
        {photoUrl ? (
          <div className="relative flex h-10 w-10 shrink-0 items-center justify-center overflow-hidden rounded-full bg-[var(--accent-soft)]">
            <Image
              src={photoUrl}
              alt={`${firstName}, ${cityState}`}
              fill
              sizes="40px"
              className="object-cover"
            />
          </div>
        ) : (
          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-[var(--accent-subtle)] text-sm font-medium text-[var(--accent)]">
            {displayInitials}
          </div>
        )}

        <div className="flex-1 min-w-0">
          {/* Quote */}
          <p className="text-sm leading-relaxed text-secondary">{quote}</p>

          {/* Author Info */}
          <div className="mt-3 flex items-center gap-2">
            <p className="text-xs font-medium text-primary">{firstName}</p>
            <span className="text-xs text-muted">•</span>
            <p className="text-xs text-muted">{cityState}</p>
            {contextLabel && (
              <>
                <span className="text-xs text-muted">•</span>
                <span className="text-xs rounded-full bg-[var(--accent-subtle)] px-2 py-0.5 text-[var(--accent)]">
                  {contextLabel}
                </span>
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}