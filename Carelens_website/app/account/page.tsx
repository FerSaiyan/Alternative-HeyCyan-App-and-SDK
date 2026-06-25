import { SubscriptionStatus } from "@prisma/client";
import { SiteFooter } from "@/components/sections/site-footer";
import { SiteHeader } from "@/components/sections/site-header";
import Link from "next/link";
import { GlassCard } from "@/components/ui/glass-card";
import { DeleteAccountButton } from "@/components/ui/delete-account-button";
import { getSessionUserProfileById, parseUserIdCookie } from "@/lib/session-user";
import { prisma } from "@/lib/prisma";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { formatPriceBrl } from "@/lib/business";

function formatSubscriptionStatus(s: SubscriptionStatus): string {
  switch (s) {
    case SubscriptionStatus.ACTIVE:
      return "Ativa";
    case SubscriptionStatus.CANCELED:
      return "Cancelada";
    case SubscriptionStatus.REFUND_PENDING:
      return "Reembolso pendente";
    case SubscriptionStatus.REFUNDED:
      return "Reembolsado";
    default:
      return "Em processamento";
  }
}

function formatPurchaseType(hasStripeSub: boolean): string {
  return hasStripeSub ? "Assinatura mensal" : "Plano ativo";
}

function calculateAge(dateOfBirth: Date | null): number | null {
  if (!dateOfBirth) return null;
  const today = new Date();
  let age = today.getFullYear() - dateOfBirth.getFullYear();
  const monthDiff = today.getMonth() - dateOfBirth.getMonth();
  if (monthDiff < 0 || (monthDiff === 0 && today.getDate() < dateOfBirth.getDate())) {
    age--;
  }
  return age;
}

export default async function AccountPage() {
  const cookieStore = await cookies();
  const userId = parseUserIdCookie(cookieStore.get("carelens_user_id")?.value);

  if (!userId) {
    redirect("/signin?auth=required&next=/account");
  }

  const userProfile = await getSessionUserProfileById(userId);
  if (!userProfile) {
    redirect("/signin?auth=required&next=/account");
  }

  const activeSubscription = await prisma.subscription.findFirst({
    where: { userId },
    orderBy: { createdAt: "desc" },
  });

  const hasActiveSub =
    activeSubscription?.status === SubscriptionStatus.ACTIVE ||
    activeSubscription?.status === SubscriptionStatus.REFUND_PENDING;

  const purchaseType = hasActiveSub
    ? formatPurchaseType(!!activeSubscription.stripeSubscriptionId)
    : "Nenhum";

  const subStatus = activeSubscription
    ? formatSubscriptionStatus(activeSubscription.status)
    : "Sem plano";

  const glassesOrder = await prisma.glassesOrder.findFirst({
    where: { userId },
    orderBy: { createdAt: "desc" },
  });

  const deviceConnected = glassesOrder?.status === "DELIVERED" || glassesOrder?.status === "SHIPPED";

  const affiliateCoupon = await prisma.affiliateCoupon.findFirst({
    where: { ownerId: userId },
  });

  let affiliateReferrals = 0;
  let affiliateRevenue = 0;
  if (affiliateCoupon) {
    const stats = await prisma.affiliatePurchase.aggregate({
      where: { couponId: affiliateCoupon.id, status: { not: "CANCELLED" } },
      _count: true,
      _sum: { revenueAmount: true },
    });
    affiliateReferrals = stats._count;
    affiliateRevenue = stats._sum.revenueAmount ?? 0;
  }

  const age = calculateAge(userProfile.dateOfBirth);

  const recentInteractions = [
    { time: "Hoje, 09:30", text: "Lembrete: Hora de tomar o café da manhã ⏰" },
    { time: "Hoje, 08:00", text: "Bom dia! Hoje está ensolarado. Não esqueça de beber água." },
    { time: "Ontem, 20:00", text: "É hora de escovar os dentes e se preparar para dormir." },
    { time: "Ontem, 18:00", text: "Seu filho(A) deixou um recado: 'Boa noite, pai!' 💙" },
  ];

  const medicationReminders = userProfile.medications
    ? userProfile.medications.split(",").map((m) => m.trim())
    : [];

  return (
    <div className="pb-10">
      <div className="carelens-ambient" />
      <SiteHeader />
      <main className="container-width pt-8 sm:pt-12">
        {/* Accessibility notice */}
        <div className="mb-4 rounded-2xl border border-amber-200 bg-amber-50 px-5 py-3 text-sm text-amber-900">
          🔍 Dica: Use Ctrl+ e Ctrl- para aumentar ou diminuir o texto.
        </div>

        {/* Emergency Button - Large, prominent */}
        <GlassCard className="glass-card-strong p-6 sm:p-7 mb-5">
          <div className="flex flex-col items-center gap-4 sm:flex-row sm:justify-between">
            <div>
              <p className="pill-eyebrow text-lg">Meu Painel</p>
              <h1 className="mt-2 text-4xl font-bold tracking-tight text-slate-900">
                Olá, {userProfile.fullName || "amigo"}!
              </h1>
              {age && (
                <p className="mt-1 text-lg text-slate-600">{age} anos</p>
              )}
            </div>
            <a
              href="tel:192"
              className="inline-flex items-center gap-3 rounded-2xl bg-red-600 px-8 py-5 text-xl font-bold text-white shadow-lg transition-transform hover:scale-105 hover:bg-red-700 focus:outline-none focus:ring-4 focus:ring-red-300"
              aria-label="Ligar para emergência - Samu 192"
            >
              <svg className="h-8 w-8" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M3 5a2 2 0 012-2h3.28a1 1 0 01.948.684l1.498 4.493a1 1 0 01-.502 1.21l-2.257 1.13a11.042 11.042 0 005.516 5.516l1.13-2.257a1 1 0 011.21-.502l4.493 1.498a1 1 0 01.684.949V19a2 2 0 01-2 2h-1C9.716 21 3 14.284 3 6V5z" />
              </svg>
              Emergência - Samu 192
            </a>
          </div>
        </GlassCard>

        <section className="grid gap-5 lg:grid-cols-[1.1fr,0.9fr]">
          {/* Device Status & Account Info */}
          <GlassCard className="glass-card-strong p-6 sm:p-7">
            <h2 className="text-2xl font-semibold text-slate-900">Status do Dispositivo</h2>
            <div className="mt-4 grid gap-4 sm:grid-cols-2">
              <div className="rounded-2xl border-2 border-white/60 bg-white/65 p-5">
                <div className="flex items-center gap-3">
                  <span className={`inline-block h-4 w-4 rounded-full ${deviceConnected ? "bg-green-500" : "bg-amber-400"} shadow-lg`} />
                  <div>
                    <p className="text-lg font-semibold text-slate-900">
                      {deviceConnected ? "Conectado" : "Pendente"}
                    </p>
                    <p className="text-sm text-slate-600">Óculos CareLens</p>
                  </div>
                </div>
                {glassesOrder && (
                  <p className="mt-2 text-sm text-slate-500">
                    Cor: {glassesOrder.color === "WHITE" ? "Branco" : glassesOrder.color === "BLACK" ? "Preto" : glassesOrder.color || "—"}
                    {glassesOrder.trackingCode ? ` · Rastreio: ${glassesOrder.trackingCode}` : ""}
                  </p>
                )}
              </div>
              <div className="rounded-2xl border-2 border-white/60 bg-white/65 p-5">
                <p className="text-sm uppercase tracking-wide text-slate-600">Plano</p>
                <p className="mt-1 text-xl font-semibold text-slate-900">{purchaseType}</p>
                <p className="mt-1 text-lg text-slate-700">Status: {subStatus}</p>
              </div>
            </div>

            <div className="mt-4 rounded-2xl border border-white/60 bg-white/65 p-4">
              <p className="text-lg font-medium text-slate-900">Sua conta</p>
              <p className="mt-1 text-base text-slate-600">{userProfile.email}</p>
            </div>

            {/* Family Contacts Quick Access */}
            <div className="mt-6">
              <h3 className="text-xl font-semibold text-slate-900">Contatos da Família</h3>
              <div className="mt-3 space-y-3">
                {userProfile.emergencyName ? (
                  <div className="flex items-center gap-4 rounded-2xl border border-white/60 bg-white/65 p-4">
                    <div className="flex h-12 w-12 items-center justify-center rounded-full bg-brand/20 text-xl font-bold text-brand">
                      {userProfile.emergencyName.charAt(0).toUpperCase()}
                    </div>
                    <div>
                      <p className="text-lg font-semibold text-slate-900">{userProfile.emergencyName}</p>
                      {userProfile.emergencyPhone && (
                        <a
                          href={`tel:${userProfile.emergencyPhone}`}
                          className="text-base text-brand underline hover:text-brand/80"
                        >
                          {userProfile.emergencyPhone}
                        </a>
                      )}
                    </div>
                  </div>
                ) : (
                  <p className="text-base text-slate-500">Nenhum contato de emergência cadastrado.</p>
                )}
              </div>
            </div>
          </GlassCard>

          {/* Recent AI Interactions */}
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-2xl font-semibold text-slate-900">Interações Recentes</h2>
            <p className="mt-1 text-base text-slate-500">Últimas interações com a IA dos óculos.</p>

            <ul className="mt-4 space-y-3">
              {recentInteractions.map((item, idx) => (
                <li key={idx} className="rounded-2xl border border-white/60 bg-white/60 p-4">
                  <p className="text-xs uppercase tracking-wide text-slate-500">{item.time}</p>
                  <p className="mt-1 text-lg font-medium text-slate-900">{item.text}</p>
                </li>
              ))}
            </ul>
          </GlassCard>

          {/* Medication Reminders */}
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-2xl font-semibold text-slate-900">Lembretes de Medicação</h2>

            {medicationReminders.length > 0 ? (
              <div className="mt-4 space-y-3">
                {medicationReminders.map((med, idx) => (
                  <div key={idx} className="flex items-center gap-4 rounded-2xl border border-white/60 bg-white/65 p-4">
                    <span className="flex h-10 w-10 items-center justify-center rounded-full bg-green-100 text-lg">💊</span>
                    <div>
                      <p className="text-lg font-semibold text-slate-900">{med}</p>
                      <p className="text-sm text-slate-500">Lembrete registrado</p>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <div className="mt-4 rounded-2xl border border-white/60 bg-white/60 p-4">
                <p className="text-base text-slate-600">Nenhum medicamento cadastrado.</p>
                <p className="mt-1 text-sm text-slate-500">Converse com seu familiar para cadastrar seus remédios.</p>
              </div>
            )}
          </GlassCard>

          {/* Privacy & legal links */}
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">Privacidade e Dados</h2>
            <div className="mt-4 space-y-4">
              <DeleteAccountButton />
              <div className="flex flex-wrap gap-2 pt-2">
                <a
                  href="/privacidade"
                  className="rounded-full border border-[#cdbe98]/60 bg-white px-4 py-2 text-base font-medium text-slate-700 transition-colors hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-brand/50"
                >
                  Política de Privacidade
                </a>
                <a
                  href="/privacidade/dados"
                  className="rounded-full border border-[#cdbe98]/60 bg-white px-4 py-2 text-base font-medium text-slate-700 transition-colors hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-brand/50"
                >
                  Retenção e Eliminação de Dados
                </a>
                <Link
                  href="/termos"
                  className="rounded-full border border-[#cdbe98]/60 bg-white px-4 py-2 text-base font-medium text-slate-700 transition-colors hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-brand/50"
                >
                  Termos de Uso
                </Link>
              </div>
            </div>
          </GlassCard>

          {/* Affiliate section */}
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">Programa de Afiliados</h2>
            {affiliateCoupon ? (
              <div className="mt-4 space-y-4">
                <div className="rounded-2xl border border-indigo-200 bg-indigo-50 p-5">
                  <p className="text-sm uppercase tracking-wide text-indigo-700">Seu cupom</p>
                  <p className="mt-1 text-2xl font-bold text-indigo-900">{affiliateCoupon.code}</p>
                  <p className="mt-1 text-base text-indigo-700">
                    {affiliateCoupon.discountType === "FIXED"
                      ? `${formatPriceBrl(affiliateCoupon.discountValue)} de desconto`
                      : `${affiliateCoupon.discountValue}% de desconto`}
                  </p>
                </div>
                <div className="grid gap-3 sm:grid-cols-2">
                  <div className="rounded-2xl border border-white/60 bg-white/65 p-4">
                    <p className="text-sm uppercase tracking-wide text-slate-600">Indicações</p>
                    <p className="mt-1 text-3xl font-semibold text-slate-900">{affiliateReferrals}</p>
                  </div>
                  <div className="rounded-2xl border border-white/60 bg-white/65 p-4">
                    <p className="text-sm uppercase tracking-wide text-slate-600">Receita gerada</p>
                    <p className="mt-1 text-3xl font-semibold text-emerald-700">{formatPriceBrl(affiliateRevenue)}</p>
                  </div>
                </div>
                <p className="text-sm text-slate-500">
                  Compartilhe seu cupom: <span className="font-mono text-slate-700">https://carelens.com.br/?coupon={affiliateCoupon.code}</span>
                </p>
                <Link
                  href="/support?topic=affiliate_withdrawal"
                  className="btn-secondary inline-block text-base"
                >
                  Solicitar saque do saldo
                </Link>
              </div>
            ) : (
              <div className="mt-4">
                <p className="text-base text-slate-600">
                  Você ainda não possui um cupom de afiliado. Indique amigos e familiares!
                </p>
                <Link
                  href="/support?topic=affiliate_request"
                  className="btn-primary mt-3 inline-block text-base"
                >
                  Solicitar cupom de afiliado
                </Link>
              </div>
            )}
          </GlassCard>
        </section>
      </main>
      <SiteFooter />
    </div>
  );
}
