import { SiteFooter } from "@/components/sections/site-footer";
import { SiteHeader } from "@/components/sections/site-header";
import { GlassCard } from "@/components/ui/glass-card";

export default function TermsPage() {
  return (
    <div className="pb-10">
      <div className="carelens-ambient" />
      <SiteHeader />
      <main className="container-width pt-8 sm:pt-12">
        <section className="mx-auto max-w-4xl space-y-5">
          <GlassCard className="glass-card-strong p-6 sm:p-7">
            <p className="pill-eyebrow">Terms of Use</p>
            <h1 className="mt-3 text-3xl font-semibold tracking-tight text-slate-900">
              Terms of Use
            </h1>
            <p className="mt-2 text-xs text-slate-500">
              Last updated: June 2026
            </p>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              1. Acceptance of Terms
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                By accessing or using the CyanBridge application, website
                (cyanbridge.vercel.app), and associated services (collectively,
                the &ldquo;Service&rdquo;), you agree to be bound by these
                Terms of Use (&ldquo;Terms&rdquo;). If you do not agree, do not
                use the Service.
              </p>
              <p>
                These Terms apply to all users, including free trial users and
                paid subscribers.
              </p>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              2. Service Description
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                CyanBridge is an open-source Android application that:
              </p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li>
                  Provides a managed relay for chat, voice, and image AI model
                  access used by the companion Android app and supported
                  smart-glasses workflows.
                </li>
                <li>
                  Provides a relay proxy that routes your prompts and content to
                  third-party AI model providers (through OpenRouter) and returns
                  the generated responses to you.
                </li>
                <li>
                  Manages user subscriptions, quotas, and access to various AI
                  models through a tiered pricing system.
                </li>
              </ul>
              <p className="mt-2">
                The Service acts solely as a technical intermediary. We do not
                host, create, or control the AI models themselves. This website
                sells software subscriptions only and does not sell hardware or
                provide medical services.
              </p>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              3. Eligibility
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                You must be at least 18 years old (or the age of majority in
                your jurisdiction) to use the Service. By using the Service,
                you represent and warrant that you meet this requirement and
                that all information you provide is accurate and complete.
              </p>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              4. Account Registration
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                To access certain features, you must create an account. You
                are responsible for:
              </p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li>Maintaining the confidentiality of your login credentials.</li>
                <li>All activity that occurs under your account.</li>
                <li>Notifying us immediately of any unauthorized use.</li>
              </ul>
              <p>
                We reserve the right to suspend or terminate accounts that
                violate these Terms.
              </p>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              5. Subscriptions and Payments
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                Paid subscriptions are billed monthly in advance through our
                payment processors (Asaas and Stripe). By subscribing, you
                authorize recurring charges at the then-current rate until the
                subscription is cancelled.
              </p>
              <p className="font-semibold mt-2">Cancellation:</p>
              <ul className="list-disc pl-5 space-y-1">
                <li>
                  You may cancel at any time through your account settings or
                  by contacting support.
                </li>
                <li>
                  Upon cancellation, your subscription remains active until the
                  end of the current billing period. No prorated refunds are
                  provided for partial periods.
                </li>
              </ul>
              <p className="font-semibold mt-2">Plan Changes:</p>
              <ul className="list-disc pl-5 space-y-1">
                <li>
                  Upgrading takes effect immediately, and the additional
                  charges are prorated for the remainder of the billing period.
                </li>
                <li>
                  Downgrading takes effect at the start of the next billing
                  period.
                </li>
              </ul>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              6. Acceptable Use
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>You agree not to use the Service to:</p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li>
                  Generate, distribute, or promote illegal, harmful, abusive,
                  harassing, defamatory, or otherwise objectionable content.
                </li>
                <li>
                  Violate any applicable law, regulation, or third-party right.
                </li>
                <li>
                  Attempt to circumvent usage quotas, authentication mechanisms,
                  or payment obligations.
                </li>
                <li>
                  Reverse engineer, decompile, or extract the source code of
                  the Service beyond what is already publicly available as
                  open-source.
                </li>
                <li>
                  Use automated scripts, bots, or scraping tools to access the
                  Service in a manner that exceeds reasonable human usage.
                </li>
                <li>
                  Interfere with or disrupt the integrity or performance of the
                  Service or its underlying infrastructure.
                </li>
              </ul>
              <p className="mt-2">
                Violation of these rules may result in immediate suspension or
                termination of your account without refund.
              </p>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              7. Open Source License
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                The CyanBridge Android application source code is released
                under an open-source license. The relay server code is also
                publicly available for inspection.
              </p>
              <p>
                The open-source nature of the project means you are free to
                review, audit, and fork the codebase. However:
              </p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li>
                  The Service as operated on cyanbridge.vercel.app is a
                  managed instance. Self-hosted instances are subject to their
                  own terms and are not covered by this agreement.
                </li>
                <li>
                  Open-source availability does not create any warranty or
                  additional liability for the managed Service beyond what is
                  stated in these Terms.
                </li>
              </ul>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              8. Third-Party AI Models
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                The Service acts as a proxy to third-party AI models accessed
                through OpenRouter. We do not control, endorse, or guarantee
                the output, accuracy, safety, or legality of content generated
                by these third-party models.
              </p>
              <p>
                You are solely responsible for:
              </p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li>
                  The prompts and content you submit to AI models through the
                  Service.
                </li>
                <li>
                  How you use the AI-generated responses.
                </li>
                <li>
                  Complying with the terms of service of OpenRouter and the
                  underlying AI model providers.
                </li>
              </ul>
              <p>
                AI model output may be inaccurate, biased, or otherwise
                unsuitable for your purposes. You should independently verify
                critical information before relying on it.
              </p>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              9. Disclaimer of Warranties
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                THE SERVICE IS PROVIDED &ldquo;AS IS&rdquo; AND
                &ldquo;AS AVAILABLE&rdquo; WITHOUT ANY WARRANTIES OF ANY KIND,
                WHETHER EXPRESS OR IMPLIED.
              </p>
              <p>
                TO THE FULLEST EXTENT PERMITTED BY LAW, CYANBRIDGE, ITS
                DEVELOPERS, AND CONTRIBUTORS DISCLAIM ALL WARRANTIES, INCLUDING
                BUT NOT LIMITED TO:
              </p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li>
                  Implied warranties of merchantability, fitness for a
                  particular purpose, and non-infringement.
                </li>
                <li>
                  Warranties that the Service will be uninterrupted,
                  error-free, secure, or free of viruses or other harmful
                  components.
                </li>
                <li>
                  Warranties regarding the accuracy, reliability, or quality
                  of AI-generated content.
                </li>
              </ul>
              <p className="mt-2">
                No advice or information obtained through the Service creates
                any warranty not expressly stated in these Terms.
              </p>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              10. Limitation of Liability
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                <strong>TO THE MAXIMUM EXTENT PERMITTED BY LAW, IN NO EVENT
                SHALL CYANBRIDGE, ITS DEVELOPERS, OR CONTRIBUTORS BE LIABLE FOR
                ANY INDIRECT, INCIDENTAL, SPECIAL, CONSEQUENTIAL, OR EXEMPLARY
                DAMAGES ARISING OUT OF OR RELATED TO YOUR USE OF THE SERVICE.
              </strong>
              </p>
              <p>This limitation applies to, but is not limited to:</p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li>
                  Loss of data, including unauthorized access, data corruption,
                  or data breaches.
                </li>
                <li>
                  Loss of profits, revenue, business opportunities, or goodwill.
                </li>
                <li>
                  Personal injury or property damage arising from your use of
                  the Service, third-party hardware, or third-party software.
                </li>
                <li>
                  Damages resulting from AI-generated content, including but
                  not limited to inaccurate, harmful, or offensive output.
                </li>
                <li>
                  Any unauthorized access to or use of our servers, third-party
                  APIs, or any personal information stored therein.
                </li>
                <li>
                  Any interruption or cessation of transmission to or from the
                  Service.
                </li>
              </ul>
              <p className="mt-2">
                <strong>Total Liability Cap:</strong> In any case, our total
                liability to you for all claims arising from or relating to
                these Terms or your use of the Service shall not exceed the
                greater of (a) the total amount paid by you to us in the 12
                months preceding the claim, or (b) US $1.00.
              </p>
              <p className="mt-2">
                <strong>Data Disclaimer:</strong> While CyanBridge is designed
                with user privacy in mind and its source code is open for
                public review, we cannot guarantee that data breaches, leaks,
                or unauthorized access will never occur. You assume all risk
                associated with transmitting data through the Service,
                including prompts, media files, and personal information. To
                the fullest extent permitted by law, we expressly disclaim any
                liability for loss, corruption, or unauthorized disclosure of
                your data.
              </p>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              11. Indemnification
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                You agree to indemnify, defend, and hold harmless CyanBridge,
                its developers, and contributors from and against any and all
                claims, liabilities, damages, losses, costs, and expenses
                (including reasonable legal fees) arising out of or related to:
              </p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li>Your use of the Service.</li>
                <li>Your violation of these Terms.</li>
                <li>Your violation of any applicable law or third-party right.</li>
                <li>Content you submit, generate, or share through the Service.</li>
              </ul>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              12. Termination
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                We reserve the right to suspend or terminate your access to the
                Service at any time, with or without cause or notice, including
                if you violate these Terms.
              </p>
              <p>
                Upon termination:
              </p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li>Your right to use the Service immediately ceases.</li>
                <li>
                  We may delete your account data in accordance with our
                  Privacy Policy.
                </li>
                <li>
                  Any prepaid subscription fees are non-refundable for the
                  remaining billing period.
                </li>
              </ul>
              <p className="mt-2">
                Sections 9 (Disclaimer), 10 (Limitation of Liability), and 11
                (Indemnification) survive any termination of these Terms.
              </p>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              13. Governing Law
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                These Terms are governed by the laws of Brazil. Any disputes
                arising out of or relating to these Terms or the Service shall
                be resolved in the courts of São Paulo, SP, Brazil.
              </p>
              <p>
                If you are a consumer in another jurisdiction, you may have
                additional rights that cannot be waived by this provision.
              </p>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              14. Changes to These Terms
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                We may revise these Terms from time to time. Material changes
                will be notified via email (if you have an account) or through
                a prominent notice on the website.
              </p>
              <p className="mt-2">
                Continued use of the Service after changes take effect
                constitutes acceptance of the updated Terms. If you do not
                agree, you must stop using the Service.
              </p>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              15. Contact
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                For questions about these Terms, please contact:
              </p>
              <p className="font-semibold text-brand">
                contato@fersaiyan.com
              </p>
            </div>
          </GlassCard>
        </section>
      </main>
      <SiteFooter />
    </div>
  );
}
