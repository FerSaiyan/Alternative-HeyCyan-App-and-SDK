import Link from "next/link";
import { SiteFooter } from "@/components/sections/site-footer";
import { SiteHeader } from "@/components/sections/site-header";
import { GlassCard } from "@/components/ui/glass-card";

export default function SupportPage() {
  return (
    <div className="pb-10">
      <div className="carelens-ambient" />
      <SiteHeader />
      <main className="container-width pt-8 sm:pt-12">
        <section className="mx-auto grid max-w-4xl gap-5 lg:grid-cols-[1fr,1fr]">
          <GlassCard className="glass-card-strong p-6 sm:p-7">
            <p className="pill-eyebrow">CyanBridge Support</p>
            <h1 className="mt-3 text-3xl font-semibold tracking-tight text-slate-900">Need help right now?</h1>
            <p className="mt-3 text-sm leading-relaxed text-muted">
              Contact us about software subscriptions, billing, companion app
              access, and refunds.
            </p>
            <div className="mt-6 space-y-2 text-sm text-slate-800">
              <p>- Email: contato@fersaiyan.com</p>
              <p>- Hours: Monday to Friday, 8am to 6pm</p>
              <p>- Scope: software subscriptions only; hardware is not sold on this site</p>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-lg font-semibold text-slate-900">Quick links</h2>
            <div className="mt-4 grid gap-3">
              <Link href="/account" className="btn-secondary text-center">
                My account
              </Link>
              <Link href="/refund-policy" className="btn-secondary text-center">
                Refund policy
              </Link>
              <Link href="/support" className="btn-secondary text-center">
                Contact support
              </Link>
            </div>
          </GlassCard>
        </section>
      </main>
      <SiteFooter />
    </div>
  );
}
