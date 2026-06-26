import { SiteFooter } from "@/components/sections/site-footer";
import { SiteHeader } from "@/components/sections/site-header";
import { GlassCard } from "@/components/ui/glass-card";

export default function RefundPolicyPage() {
  return (
    <div className="pb-10">
      <div className="carelens-ambient" />
      <SiteHeader />
      <main className="container-width pt-8 sm:pt-12">
        <section className="mx-auto max-w-4xl space-y-5">
          <GlassCard className="glass-card-strong p-6 sm:p-7">
            <p className="pill-eyebrow">Refund Policy</p>
            <h1 className="mt-3 text-3xl font-semibold tracking-tight text-slate-900">
              Refund Policy
            </h1>
            <p className="mt-2 text-xs text-slate-500">
              Last updated: June 2026
            </p>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              1. Subscription Cancellation
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                You may cancel your subscription at any time through your
                account settings or by contacting support at{" "}
                <span className="font-semibold text-brand">contato@fersaiyan.com</span>.
              </p>
              <p>
                Upon cancellation, your subscription remains active until the
                end of the current paid billing period. You will retain access
                to all features of your plan during that time.
              </p>
              <p className="font-semibold">
                No prorated refunds or credits are provided for partial
                billing periods.
              </p>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              2. Free Trial
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                Eligible users may receive a free trial period (currently 30
                days). During the trial, you have access to a limited set of
                AI models through the cheap models router.
              </p>
              <p>
                If you cancel during the free trial, your access ends
                immediately. No payment method is charged during the trial
                unless you explicitly subscribe to a paid plan.
              </p>
              <p>
                At the end of the trial period, your account will be
                automatically downgraded to the free tier (if available) or
                suspended until a paid plan is selected.
              </p>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              3. Plan Changes
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                <strong>Upgrades:</strong> When you upgrade to a higher-tier
                plan, the change takes effect immediately. The additional
                charges are prorated for the remainder of the current billing
                period.
              </p>
              <p>
                <strong>Downgrades:</strong> When you downgrade to a lower-tier
                plan, the change takes effect at the start of the next billing
                period. You retain access to your current plan's features until
                then. No refund is issued for the difference in plan price
                during the current period.
              </p>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              4. Payment Disputes and Chargebacks
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                If you believe a charge was made in error, please contact us
                at{" "}
                <span className="font-semibold text-brand">contato@fersaiyan.com</span>{" "}
                before filing a dispute or chargeback with your payment
                provider. We will work with you to resolve the issue.
              </p>
              <p>
                Filing an unnecessary chargeback may result in immediate
                suspension of your account and termination of all active
                subscriptions. We reserve the right to dispute chargebacks
                with supporting evidence of service provided.
              </p>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              5. Refund Exceptions
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                The following are generally not eligible for refunds:
              </p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li>
                  Usage of AI model tokens consumed before cancellation.
                </li>
                <li>
                  Partial billing periods after cancellation.
                </li>
                <li>
                  Dissatisfaction with AI model output quality or accuracy
                  (see our Terms of Use for the full disclaimer on
                  third-party AI model output).
                </li>
                <li>
                  Downgrade price differences during the current billing
                  period.
                </li>
              </ul>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              6. How to Request a Refund
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                To request a refund or ask questions about billing, contact us
                at:
              </p>
              <p className="font-semibold text-brand">
                contato@fersaiyan.com
              </p>
              <p className="mt-2">
                Please include the email address associated with your account
                and a brief description of the issue. We will respond within
                10 business days.
              </p>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              7. Changes to This Policy
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                We may update this Refund Policy from time to time. Changes
                will take effect at the start of the next billing period
                following the update, unless otherwise required by law.
              </p>
            </div>
          </GlassCard>
        </section>
      </main>
      <SiteFooter />
    </div>
  );
}
