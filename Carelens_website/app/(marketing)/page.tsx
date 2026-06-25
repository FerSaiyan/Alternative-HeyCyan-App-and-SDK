import { SiteFooter } from "@/components/sections/site-footer";
import { SiteHeader } from "@/components/sections/site-header";
import { HeroSection } from "@/components/sections/hero-section";
import { ServiceCardsSection } from "@/components/sections/service-cards-section";
import { PricingSection } from "@/components/sections/pricing-section";
import { PersonalizedPathSection } from "@/components/sections/personalized-path-section";
import { EditorialReassuranceSection } from "@/components/sections/editorial-reassurance-section";
import { TestimonialsSection } from "@/components/sections/testimonials-section";
import { BottomCtaSection } from "@/components/sections/bottom-cta-section";
import { FAQAccordion } from "@/components/sections/faq-accordion";

/**
 * CareLens AI Landing Page
 *
 * Sections in order:
 * 1. Hero - Sky gradient background, H1, CTA, highlight cards
 * 2. Service Cards - Horizontal scroll row on mobile
 * 3. Pricing - Pricing cards for glasses, subscription, and bundle
 * 4. Personalized Path - Care journey path cards
 * 5. Editorial Reassurance - Trust-value cards
 * 6. Testimonials - Family testimonials
 * 7. Bottom CTA - Cinematic full-width dark overlay panel
 * 8. FAQ Accordion - 6 Q/A with keyboard navigation
 * 9. Footer - Glass footer with links
 */
export default async function MarketingHomePage() {

  return (
    <div className="pb-4">
      <SiteHeader />

      <main>
        {/* Section 1: Hero */}
        <HeroSection id="tratamento" />

        {/* Section 2: Approach (commented out per request)
        <ApproachSection />
        */}

        {/* Section 2: Service Cards */}
        <ServiceCardsSection />

        {/* Section 4: Pricing */}
        <div className="mt-6 lg:mt-10">
          <PricingSection />
        </div>

        {/* Section 5: Personalized Path */}
        <PersonalizedPathSection />

        {/* Section 6: Editorial Reassurance */}
        <div className="mt-6 lg:mt-10">
          <EditorialReassuranceSection />
        </div>

        {/* Section 7: Testimonials/Trust */}
        <TestimonialsSection />

        {/* Section "Como funciona" + standalone "Condições" (commented out per request)
        <section id="processo" className="mt-10 scroll-mt-20 sm:scroll-mt-24 lg:mt-12">
          ...
        </section>
        */}

        {/* Section 8: Bottom CTA */}
        <div className="mt-10 lg:mt-14">
          <BottomCtaSection />
        </div>

        {/* Section 9: FAQ Accordion */}
        <div className="mt-10 lg:mt-14">
          <FAQAccordion />
        </div>
      </main>
      <SiteFooter />
    </div>
  );
}
