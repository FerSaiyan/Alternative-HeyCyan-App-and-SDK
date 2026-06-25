import { Role, SubscriptionStatus } from "@prisma/client";
import { SiteFooter } from "@/components/sections/site-footer";
import { SiteHeader } from "@/components/sections/site-header";
import { GlassCard } from "@/components/ui/glass-card";
import { SectionHeading } from "@/components/ui/section-heading";
import { prisma } from "@/lib/prisma";
import { requirePageRole } from "@/lib/role-guard";
import Link from "next/link";

function fmtPt(ts: Date): string {
  return new Intl.DateTimeFormat("pt-BR", {
    day: "2-digit",
    month: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(ts);
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

export default async function FamilyPage() {
  const familyUser = await requirePageRole([Role.FAMILY], "/family");

  // Try to find an elderly user linked to this family member
  // First check if this user has a FamilyProfile
  const familyProfile = await prisma.familyProfile.findUnique({
    where: { userId: familyUser.id },
  });

  // Find the most recently created elderly user as the monitored person
  const elderlyUser = await prisma.user.findFirst({
    where: { role: Role.ELDERLY },
    orderBy: { createdAt: "desc" },
    select: {
      id: true,
      email: true,
      fullName: true,
      dateOfBirth: true,
      visionLevel: true,
      hearingLevel: true,
      mobilityLevel: true,
      medications: true,
      allergies: true,
      emergencyContactName: true,
      emergencyContactPhone: true,
      glassesColor: true,
      createdAt: true,
    },
  });

  // Get subscription info for the elderly user
  const elderlySubscription = elderlyUser
    ? await prisma.subscription.findFirst({
        where: { userId: elderlyUser.id },
        orderBy: { createdAt: "desc" },
      })
    : null;

  const elderlyHasActiveSub =
    elderlySubscription?.status === SubscriptionStatus.ACTIVE ||
    elderlySubscription?.status === SubscriptionStatus.REFUND_PENDING;

  // Check glasses order
  const elderlyGlassesOrder = elderlyUser
    ? await prisma.glassesOrder.findFirst({
        where: { userId: elderlyUser.id },
        orderBy: { createdAt: "desc" },
      })
    : null;

  const deviceConnected = elderlyGlassesOrder?.status === "DELIVERED" || elderlyGlassesOrder?.status === "SHIPPED";

  const elderlyAge = calculateAge(elderlyUser?.dateOfBirth ?? null);

  // Recent AI interactions (simulated for now)
  const recentActivity = [
    { time: "Hoje, 09:30", description: "IA lembrou o idoso de tomar café da manhã" },
    { time: "Hoje, 08:00", description: "Interação matinal: Bom dia! Clima ensolarado." },
    { time: "Ontem, 20:00", description: "Lembrete noturno: Escovar os dentes" },
    { time: "Ontem, 18:00", description: "Recado enviado para o idoso: 'Boa noite!'" },
  ];

  // Medication reminders from profile
  const medications = elderlyUser?.medications
    ? elderlyUser.medications.split(",").map((m) => m.trim())
    : [];

  // Alerts (simulated)
  const alerts = [
    { type: "info", text: "Óculos conectado e funcionando normalmente.", time: "Hoje, 10:00" },
    { type: "success", text: "Medicação matinal tomada às 08:30.", time: "Hoje, 08:35" },
    { type: "warning", text: "Bateria dos óculos está em 15%. Carregue esta noite.", time: "Ontem, 22:00" },
  ];

  const alertStyles: Record<string, string> = {
    info: "border-blue-200 bg-blue-50 text-blue-900",
    success: "border-emerald-200 bg-emerald-50 text-emerald-900",
    warning: "border-amber-200 bg-amber-50 text-amber-900",
    error: "border-red-200 bg-red-50 text-red-900",
  };

  return (
    <div className="pb-10">
      <div className="carelens-ambient" />
      <SiteHeader />
      <main className="container-width pt-8 sm:pt-12 space-y-5">
        {/* Dashboard Title */}
        <SectionHeading
          eyebrow="CareLens"
          title="Painel da Família"
          subtitle="Acompanhe o bem-estar do seu idoso em tempo real."
        />

        {/* 1. Visão Geral */}
        <section>
          <GlassCard className="glass-card-strong p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">Visão Geral</h2>
            <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
              <div className="rounded-2xl border border-white/60 bg-white/65 p-4">
                <p className="text-xs uppercase tracking-wide text-slate-600">Idoso</p>
                <p className="mt-1 text-lg font-semibold text-slate-900">
                  {elderlyUser?.fullName || "Não vinculado"}
                </p>
                {elderlyAge && <p className="text-sm text-slate-500">{elderlyAge} anos</p>}
                {familyProfile && (
                  <p className="mt-1 text-sm text-slate-500">
                    Parentesco: {familyProfile.relationship || "Não informado"}
                  </p>
                )}
              </div>
              <div className="rounded-2xl border border-white/60 bg-white/65 p-4">
                <p className="text-xs uppercase tracking-wide text-slate-600">Dispositivo</p>
                <div className="mt-1 flex items-center gap-2">
                  <span className={`inline-block h-3 w-3 rounded-full ${deviceConnected ? "bg-green-500" : "bg-amber-400"}`} />
                  <p className="text-lg font-semibold text-slate-900">
                    {deviceConnected ? "Conectado" : "Aguardando"}
                  </p>
                </div>
                {elderlyGlassesOrder && (
                  <p className="mt-1 text-sm text-slate-500">
                    Cor: {elderlyGlassesOrder.color === "WHITE" ? "Branco" : elderlyGlassesOrder.color === "BLACK" ? "Preto" : elderlyGlassesOrder.color || "—"}
                  </p>
                )}
              </div>
              <div className="rounded-2xl border border-white/60 bg-white/65 p-4">
                <p className="text-xs uppercase tracking-wide text-slate-600">Assinatura</p>
                <p className="mt-1 text-lg font-semibold text-slate-900">
                  {elderlyHasActiveSub ? "Ativa" : "Inativa"}
                </p>
                {elderlySubscription && (
                  <p className="mt-1 text-sm text-slate-500">
                    R$ {elderlySubscription.monthlyPriceBrl}/mês
                  </p>
                )}
              </div>
              <div className="rounded-2xl border border-white/60 bg-white/65 p-4">
                <p className="text-xs uppercase tracking-wide text-slate-600">Sessão</p>
                <p className="mt-1 text-sm font-medium text-slate-900">{familyUser.email}</p>
                <p className="text-xs text-slate-500">
                  Familiar
                  {familyProfile?.displayName ? `: ${familyProfile.displayName}` : ""}
                </p>
              </div>
            </div>
          </GlassCard>
        </section>

        {/* 2. Atividade Recente & 3. Lembretes */}
        <section className="grid gap-5 lg:grid-cols-2">
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">Atividade Recente</h2>
            <p className="mt-1 text-sm text-slate-500">Últimas interações da IA com o idoso.</p>
            <ul className="mt-4 space-y-3">
              {recentActivity.map((activity, idx) => (
                <li
                  key={idx}
                  className="rounded-2xl border border-white/60 bg-white/65 p-4"
                >
                  <p className="text-xs uppercase tracking-wide text-slate-500">{activity.time}</p>
                  <p className="mt-1 text-sm font-medium text-slate-900">{activity.description}</p>
                </li>
              ))}
            </ul>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">Lembretes</h2>
            <p className="mt-1 text-sm text-slate-500">Medicações e cuidados registrados.</p>

            <div className="mt-4 space-y-3">
              {medications.length > 0 ? (
                medications.map((med, idx) => (
                  <div key={idx} className="flex items-center gap-3 rounded-2xl border border-white/60 bg-white/65 p-4">
                    <span className="flex h-10 w-10 items-center justify-center rounded-full bg-green-100 text-lg">💊</span>
                    <div>
                      <p className="text-base font-semibold text-slate-900">{med}</p>
                      <p className="text-xs text-slate-500">Medicação registrada</p>
                    </div>
                  </div>
                ))
              ) : (
                <div className="rounded-2xl border border-white/60 bg-white/60 p-4">
                  <p className="text-sm text-slate-600">Nenhum medicamento cadastrado no perfil do idoso.</p>
                </div>
              )}

              {elderlyUser?.allergies && (
                <div className="rounded-2xl border border-amber-200 bg-amber-50 p-4">
                  <p className="text-xs uppercase tracking-wide text-amber-700">Alergias</p>
                  <p className="mt-1 text-sm font-medium text-amber-900">{elderlyUser.allergies}</p>
                </div>
              )}
            </div>

            {/* Upcoming reminders placeholder */}
            <div className="mt-4 rounded-2xl border border-indigo-200 bg-indigo-50 p-4">
              <p className="text-xs uppercase tracking-wide text-indigo-700">Próximos lembretes</p>
              <p className="mt-1 text-sm text-indigo-900">
                Café da manhã - 08:00 | Almoço - 12:00 | Jantar - 18:00
              </p>
            </div>
          </GlassCard>
        </section>

        {/* 4. Alertas */}
        <section>
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">Alertas</h2>
            <p className="mt-1 text-sm text-slate-500">Notificações e anomalias detectadas.</p>

            <div className="mt-4 space-y-3">
              {alerts.map((alert, idx) => (
                <div
                  key={idx}
                  className={`rounded-2xl border px-4 py-3 ${alertStyles[alert.type] || alertStyles.info}`}
                >
                  <div className="flex items-start justify-between">
                    <p className="text-sm font-medium">{alert.text}</p>
                    <span className="shrink-0 text-xs opacity-70">{alert.time}</span>
                  </div>
                </div>
              ))}
            </div>
          </GlassCard>
        </section>

        {/* 5. Saúde & 6. Assinatura */}
        <section className="grid gap-5 lg:grid-cols-2">
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">Saúde</h2>
            <p className="mt-1 text-sm text-slate-500">Perfil de saúde do idoso.</p>

            <div className="mt-4 grid gap-3 sm:grid-cols-2">
              <div className="rounded-2xl border border-white/60 bg-white/65 p-4">
                <p className="text-xs uppercase tracking-wide text-slate-600">Visão</p>
                <p className="mt-1 text-base font-semibold text-slate-900">
                  {elderlyUser?.visionLevel || "Não informado"}
                </p>
              </div>
              <div className="rounded-2xl border border-white/60 bg-white/65 p-4">
                <p className="text-xs uppercase tracking-wide text-slate-600">Audição</p>
                <p className="mt-1 text-base font-semibold text-slate-900">
                  {elderlyUser?.hearingLevel || "Não informado"}
                </p>
              </div>
              <div className="rounded-2xl border border-white/60 bg-white/65 p-4">
                <p className="text-xs uppercase tracking-wide text-slate-600">Mobilidade</p>
                <p className="mt-1 text-base font-semibold text-slate-900">
                  {elderlyUser?.mobilityLevel || "Não informado"}
                </p>
              </div>
              <div className="rounded-2xl border border-white/60 bg-white/65 p-4">
                <p className="text-xs uppercase tracking-wide text-slate-600">Alergias</p>
                <p className="mt-1 text-base font-semibold text-slate-900">
                  {elderlyUser?.allergies || "Nenhuma registrada"}
                </p>
              </div>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">Assinatura</h2>
            <p className="mt-1 text-sm text-slate-500">Status do plano e informações de cobrança.</p>

            <div className="mt-4 space-y-3">
              <div className="rounded-2xl border border-white/60 bg-white/65 p-4">
                <p className="text-xs uppercase tracking-wide text-slate-600">Status</p>
                <p className={`mt-1 text-lg font-semibold ${
                  elderlyHasActiveSub ? "text-emerald-700" : "text-slate-600"
                }`}>
                  {elderlyHasActiveSub ? "Ativo" : "Inativo"}
                </p>
              </div>
              {elderlySubscription && (
                <>
                  <div className="rounded-2xl border border-white/60 bg-white/65 p-4">
                    <p className="text-xs uppercase tracking-wide text-slate-600">Valor mensal</p>
                    <p className="mt-1 text-lg font-semibold text-slate-900">
                      R$ {elderlySubscription.monthlyPriceBrl}
                    </p>
                  </div>
                  <div className="rounded-2xl border border-white/60 bg-white/65 p-4">
                    <p className="text-xs uppercase tracking-wide text-slate-600">Criada em</p>
                    <p className="mt-1 text-base text-slate-900">
                      {fmtPt(elderlySubscription.createdAt)}
                    </p>
                  </div>
                </>
              )}
              {!elderlySubscription && (
                <div className="rounded-2xl border border-amber-200 bg-amber-50 p-4">
                  <p className="text-sm text-amber-900">Nenhuma assinatura ativa no momento.</p>
                </div>
              )}
            </div>
          </GlassCard>
        </section>

        {/* 7. Gerenciar */}
        <section>
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-xl font-semibold text-slate-900">Gerenciar</h2>
            <p className="mt-1 text-sm text-slate-500">Ajuste o perfil do idoso, contatos de emergência e medicações.</p>

            <div className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              <Link
                href="/support?topic=edit_profile"
                className="rounded-2xl border border-white/60 bg-white/65 p-4 hover:bg-white/80 transition-colors"
              >
                <p className="text-base font-semibold text-slate-900">✏️ Editar perfil do idoso</p>
                <p className="mt-1 text-xs text-slate-500">Atualize dados pessoais e de saúde.</p>
              </Link>
              <Link
                href="/support?topic=emergency_contacts"
                className="rounded-2xl border border-white/60 bg-white/65 p-4 hover:bg-white/80 transition-colors"
              >
                <p className="text-base font-semibold text-slate-900">👤 Contatos de emergência</p>
                <p className="mt-1 text-xs text-slate-500">Gerencie os contatos importantes.</p>
              </Link>
              <Link
                href="/support?topic=medications"
                className="rounded-2xl border border-white/60 bg-white/65 p-4 hover:bg-white/80 transition-colors"
              >
                <p className="text-base font-semibold text-slate-900">💊 Medicações</p>
                <p className="mt-1 text-xs text-slate-500">Cadastre e atualize remédios.</p>
              </Link>
            </div>

            {familyProfile && (
              <div className="mt-4 rounded-2xl border border-violet-200 bg-violet-50 p-4">
                <p className="text-xs uppercase tracking-wide text-violet-700">Seu perfil de familiar</p>
                <p className="mt-1 text-sm text-violet-900">
                  {familyProfile.displayName || "Sem nome de exibição"}
                  {familyProfile.relationship ? ` · ${familyProfile.relationship}` : ""}
                </p>
              </div>
            )}
          </GlassCard>
        </section>
      </main>
      <SiteFooter />
    </div>
  );
}
