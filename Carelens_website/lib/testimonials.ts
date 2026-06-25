/**
 * Testimonials data structure - Patient quotes with photo support
 *
 * Required field mapping per specifications:
 * - photoUrl: URL to patient photo (optional - no fake photos)
 * - firstName: First name for display
 * - cityState: city/state for location display
 * - quote: Short testimonial quote
 * - consentConfirmed: Explicit consent for display (draft/published toggle)
 * - contextLabel: Optional context (e.g., "após consulta", "em análise")
 * - optional internalNotes: Internal metadata - NOT displayed publicly
 */

export interface Testimonial {
  id: string;
  photoUrl?: string;
  firstName: string;
  initials?: string;
  cityState: string;
  quote: string;
  consentConfirmed: boolean;
  contextLabel?: string;
  internalNotes?: string; // Internal only - not publicly exposed
  createdAt: string;
}

/**
 * Default testimonials data
 * Currently empty - to be populated with real patient quotes
 * when consent is obtained
 */
export const testimonials: Testimonial[] = [];

export function getVisibleTestimonials(): Testimonial[] {
  return testimonials.filter((t) => t.consentConfirmed);
}

export function getInitials(firstName: string, fallback?: string): string {
  if (fallback) return fallback;
  const parts = firstName.trim().split(/\s+/);
  if (parts.length >= 2) {
    return `${parts[0][0]}${parts[1][0]}`.toUpperCase();
  }
  return firstName.substring(0, 2).toUpperCase();
}