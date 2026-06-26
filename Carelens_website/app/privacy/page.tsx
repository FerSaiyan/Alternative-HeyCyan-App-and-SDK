import { SiteFooter } from "@/components/sections/site-footer";
import { SiteHeader } from "@/components/sections/site-header";
import { GlassCard } from "@/components/ui/glass-card";

export default function PrivacyPage() {
  return (
    <div className="pb-10">
      <div className="carelens-ambient" />
      <SiteHeader />
      <main className="container-width pt-8 sm:pt-12">
        <section className="mx-auto max-w-4xl space-y-5">
          <GlassCard className="glass-card-strong p-6 sm:p-7">
            <p className="pill-eyebrow">Privacy</p>
            <h1 className="mt-3 text-3xl font-semibold tracking-tight text-slate-900">
              Privacy Policy
            </h1>
            <p className="mt-2 text-xs text-slate-500">
              Last updated: June 2026
            </p>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              1. Controller and Contact Information
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                <strong>Controller:</strong> CyanBridge (Fernando Saiyan)
              </p>
              <p>
                <strong>Email:</strong> contato@fersaiyan.com
              </p>
              <p>
                <strong>Support:</strong> contato@fersaiyan.com
              </p>
              <p>
                CyanBridge is a software subscription that provides AI model
                proxy services for supported smart-glasses workflows through its
                relay server and companion app. This domain does not sell
                hardware or provide medical services. For any privacy-related
                inquiries, you may contact us at the email above.
              </p>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              2. What Data We Collect
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>We may collect the following categories of data:</p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li>
                  <strong>Account Information:</strong> email address,
                  display name, and authentication credentials when you
                  register for an account.
                </li>
                <li>
                  <strong>Usage Data:</strong> API model selection, number of
                  requests, tokens consumed, and timestamps of API calls. This
                  data is used solely for quota enforcement, billing, and service
                  improvement.
                </li>
                <li>
                  <strong>Payment Data:</strong> transaction records
                  (payment method type, amount, currency, timestamp). We do{" "}
                  <strong>not</strong> store full credit card numbers, CVV codes,
                  or other sensitive payment instrument data. Payment processing
                  is handled by Asaas and Stripe.
                </li>
                <li>
                  <strong>Communication Content:</strong> prompts, messages,
                  voice recordings, and images you submit to AI models through
                  the service. These are forwarded to the respective AI model
                  provider (e.g., OpenRouter) and are not stored on our servers
                  beyond transient processing for delivery.
                </li>
                <li>
                  <strong>Device Information:</strong> Android device model,
                  OS version, connected app version, and similar technical data
                  for troubleshooting and analytics.
                </li>
                <li>
                  <strong>Technical Data:</strong> IP address, browser/user-agent
                  information, and session identifiers collected for security
                  and operational purposes.
                </li>
              </ul>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              3. How We Use Your Data
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>We use your data for the following purposes:</p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li>Providing and operating the AI model proxy service.</li>
                <li>Managing user accounts, authentication, and access control.</li>
                <li>Enforcing usage quotas and processing subscription payments.</li>
                <li>Forwarding prompts and content to third-party AI model providers as requested by you.</li>
                <li>Improving the service, diagnosing technical issues, and preventing abuse.</li>
                <li>Complying with legal obligations and enforcing our Terms of Use.</li>
              </ul>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              4. Open Source Transparency
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                The entire CyanBridge application source code is publicly
                available on GitHub. You are free to inspect, audit, and verify
                how your data is handled at every layer of the application:
              </p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li>
                  <strong>Android app:</strong> includes the companion app,
                  transport logic, and data handling code used for supported
                  smart-glasses workflows.
                </li>
                <li>
                  <strong>Relay server:</strong> the Vercel-hosted backend
                  (cyanbridge.vercel.app) that proxies AI model requests,
                  manages subscriptions, and enforces quotas.
                </li>
              </ul>
              <p className="mt-2">
                We believe transparency builds trust. However, open-source
                availability does not imply any warranty or additional
                liability regarding the security or privacy of the system (see
                Section 9 below).
              </p>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              5. Data Sharing and Third Parties
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>We share your data only where necessary to provide the service:</p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li>
                  <strong>OpenRouter:</strong> receives your prompts, messages,
                  voice recordings, and images to process AI model inference
                  requests. Data handling by OpenRouter is governed by their
                  own privacy policy.
                </li>
                <li>
                  <strong>Asaas / Stripe:</strong> process subscription payments.
                  They receive transaction data as necessary to execute
                  recurring billing.
                </li>
                <li>
                  <strong>Legal authorities:</strong> when required by
                  applicable law, court order, or governmental regulation.
                </li>
              </ul>
              <p className="mt-3">
                We do not sell your personal data to third parties. All
                third-party processors are contractually bound to process data
                only for the purposes described in this policy.
              </p>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              6. Data Security
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>We implement reasonable technical and organizational security measures:</p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li>Encryption in transit (TLS 1.3) for all communications.</li>
                <li>Encryption at rest for stored data.</li>
                <li>Role-based access controls on server infrastructure.</li>
                <li>Regular security reviews and dependency audits.</li>
                <li>We do not store full credit card numbers, CVV codes, or passwords in plaintext.</li>
              </ul>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              7. Data Retention
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>We retain your data only as long as necessary:</p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li>
                  <strong>Account data:</strong> for the duration of your
                  account plus 5 years after closure (statutory limitation
                  period).
                </li>
                <li>
                  <strong>Usage logs and quota records:</strong> for the
                  current billing period plus 12 months for accounting purposes.
                </li>
                <li>
                  <strong>Payment records:</strong> 5 years to comply with
                  tax and fiscal obligations.
                </li>
                <li>
                  <strong>AI prompts and responses:</strong> not retained on
                  our servers beyond the time necessary to forward them to
                  the AI provider and deliver the response back to you.
                </li>
              </ul>
              <p className="mt-2">
                After the retention period, data is securely deleted or
                anonymized. Backups are rotated every 90 days, after which
                deleted data is overwritten.
              </p>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              8. Your Rights
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>Depending on your jurisdiction, you may have the following rights:</p>
              <ul className="list-disc pl-5 space-y-1 mt-2">
                <li><strong>Access:</strong> request a copy of the data we hold about you.</li>
                <li><strong>Rectification:</strong> request correction of inaccurate or incomplete data.</li>
                <li><strong>Erasure:</strong> request deletion of your data, subject to legal retention requirements.</li>
                <li><strong>Portability:</strong> request transfer of your data to another service provider.</li>
                <li><strong>Objection:</strong> object to processing based on legitimate interests.</li>
                <li><strong>Withdraw consent:</strong> where processing is based on consent, you may withdraw it at any time.</li>
              </ul>
              <p className="mt-2">
                To exercise your rights, contact us at{" "}
                <span className="font-semibold text-brand">contato@fersaiyan.com</span>.
                We will respond within 30 days.
              </p>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              9. Disclaimer Regarding Data Privacy and Security
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                <strong>No Guarantee of Absolute Security.</strong> While we
                design CyanBridge with privacy as a core principle and take
                reasonable measures to protect your data, no system is
                completely secure. We cannot and do not guarantee that
                unauthorized access, data breaches, or other security incidents
                will never occur.
              </p>
              <p>
                <strong>No Liability for Data Incidents.</strong> To the fullest
                extent permitted by applicable law, CyanBridge, its developers,
                and contributors shall not be held liable for any loss,
                corruption, or unauthorized disclosure of your data arising
                from security breaches, software vulnerabilities, or any other
                cause beyond our reasonable control. This includes, but is not
                limited to, data transmitted to or from third-party AI model
                providers via OpenRouter.
              </p>
              <p>
                <strong>Open Source Does Not Imply Warranty.</strong> The fact
                that the CyanBridge source code is publicly available for
                inspection does not create any warranty, guarantee, or
                additional duty of care beyond what is explicitly stated in
                this policy and the Terms of Use.
              </p>
              <p>
                <strong>Third-Party Services.</strong> Prompts and content you
                submit to AI models are forwarded to third-party providers
                (e.g., OpenRouter, and ultimately the AI model hosts). We have
                no control over how those third parties handle your data after
                it reaches them. You should review their privacy policies
                before using the service.
              </p>
              <p>
                <strong>Use at Your Own Risk.</strong> By using CyanBridge, you
                acknowledge and accept these risks. You are responsible for
                evaluating whether the service meets your privacy and security
                requirements.
              </p>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">
              10. Changes to This Policy
            </h2>
            <div className="mt-3 space-y-2 text-sm text-slate-800 leading-relaxed">
              <p>
                We may update this Privacy Policy from time to time. Material
                changes will be notified via email (if you have an account) or
                through a prominent notice on the website.
              </p>
              <p className="mt-2">
                Continued use of the service after changes take effect
                constitutes acceptance of the updated policy.
              </p>
            </div>
          </GlassCard>
        </section>
      </main>
      <SiteFooter />
    </div>
  );
}
