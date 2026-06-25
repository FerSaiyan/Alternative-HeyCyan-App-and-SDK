import { Role, SubscriptionStatus } from "@prisma/client";
import { SiteFooter } from "@/components/sections/site-footer";
import { SiteHeader } from "@/components/sections/site-header";
import { GlassCard } from "@/components/ui/glass-card";
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

export default async function AdminPage() {

  const adminUser = await requirePageRole([Role.ADMIN], "/admin");

  const [totalUsers, totalElderly, totalFamily, activeSubscriptions, recentSubscriptions, recentUsers] =
    await Promise.all([
      prisma.user.count(),
      prisma.user.count({ where: { role: Role.ELDERLY } }),
      prisma.user.count({ where: { role: Role.FAMILY } }),
      prisma.subscription.count({
        where: { status: SubscriptionStatus.ACTIVE },
      }),
      prisma.subscription.findMany({
        orderBy: { createdAt: "desc" },
        take: 5,
        select: {
          id: true,
          status: true,
          monthlyPriceBrl: true,
          createdAt: true,
          user: { select: { email: true } },
        },
      }),
      prisma.user.findMany({
        orderBy: { createdAt: "desc" },
        take: 5,
        select: {
          id: true,
          email: true,
          role: true,
          fullName: true,
          createdAt: true,
        },
      }),
    ]);

  const overviewStats = [
    { label: "Total de usuários", value: totalUsers },
    { label: "Idosos", value: totalElderly },
    { label: "Familiares", value: totalFamily },
    { label: "Assinaturas ativas", value: activeSubscriptions },
  ];

  return (
    <div className="pb-10">
      <div className="carelens-ambient" />
      <SiteHeader />
      <main className="container-width pt-8 sm:pt-12 space-y-5">
        <section className="grid gap-5 lg:grid-cols-[1.1fr,0.9fr]">
          <GlassCard className="glass-card-strong p-6 sm:p-7">
            <p className="pill-eyebrow">Operações CareLens</p>
            <h1 className="mt-3 text-3xl font-semibold tracking-tight text-slate-900">
              Painel Administrativo
            </h1>
            <p className="mt-2 text-sm text-muted">
              Monitoramento operacional de usuários, assinaturas e afiliados.
            </p>
            <p className="mt-1 text-xs text-slate-600">Sessão administrativa: {adminUser.email}</p>
            <div className="mt-3 flex flex-wrap gap-2">
              <Link href="/admin/users" className="btn-secondary !py-2 text-xs">
                Gerenciar usuários e perfis
              </Link>
              <Link href="/admin/affiliates" className="btn-secondary !py-2 text-xs">
                Gerenciar afiliados
              </Link>
            </div>

            <div className="mt-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              {overviewStats.map((stat) => (
                <article
                  key={stat.label}
                  className="rounded-xl border border-white/60 bg-white/65 p-4"
                >
                  <p className="text-xs uppercase tracking-wide text-slate-600">
                    {stat.label}
                  </p>
                  <p className="mt-2 text-2xl font-semibold text-slate-800">
                    {stat.value}
                  </p>
                </article>
              ))}
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-base font-semibold text-slate-900">
              Checklist operacional do dia
            </h2>
            <ul className="mt-4 space-y-2 text-sm text-slate-800">
              <li className="flex items-start gap-2 rounded-xl border border-white/60 bg-white/60 px-3 py-2">
                <span className="mt-0.5 inline-block h-2 w-2 shrink-0 rounded-full bg-brand opacity-80" />
                <span>
                  <span className="text-xs uppercase tracking-wide text-slate-500">Financeiro</span>
                  <span className="ml-2 font-medium text-slate-800">Conciliar pagamentos de assinatura</span>
                </span>
              </li>
              <li className="flex items-start gap-2 rounded-xl border border-white/60 bg-white/60 px-3 py-2">
                <span className="mt-0.5 inline-block h-2 w-2 shrink-0 rounded-full bg-brand opacity-80" />
                <span>
                  <span className="text-xs uppercase tracking-wide text-slate-500">Suporte</span>
                  <span className="ml-2 font-medium text-slate-800">Responder tickets de suporte</span>
                </span>
              </li>
              <li className="flex items-start gap-2 rounded-xl border border-white/60 bg-white/60 px-3 py-2">
                <span className="mt-0.5 inline-block h-2 w-2 shrink-0 rounded-full bg-brand opacity-80" />
                <span>
                  <span className="text-xs uppercase tracking-wide text-slate-500">Afiliados</span>
                  <span className="ml-2 font-medium text-slate-800">Revisar saques pendentes</span>
                </span>
              </li>
            </ul>

            <div className="mt-5 rounded-xl border border-white/65 bg-white/70 p-3 text-xs text-slate-600">
              Dados atualizados a cada carregamento.
            </div>
          </GlassCard>
        </section>

        <section className="grid gap-5 lg:grid-cols-[1.2fr_0.8fr]">
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-base font-semibold text-slate-900">
              Cadastros recentes
            </h2>
            <p className="mt-1 text-xs text-slate-500">
              Últimos {recentUsers.length} usuários cadastrados.
            </p>

            {recentUsers.length === 0 ? (
              <p className="mt-4 rounded-xl border border-white/60 bg-white/60 px-3 py-3 text-sm text-slate-600">
                Nenhum usuário registrado.
              </p>
            ) : (
              <ul className="mt-3 space-y-2">
                {recentUsers.map((user) => (
                  <li
                    key={user.id}
                    className="flex items-start justify-between gap-3 rounded-xl border border-white/60 bg-white/60 px-3 py-2.5 text-xs"
                  >
                    <div className="min-w-0 flex-1">
                      <p className="truncate font-medium text-slate-800">
                        {user.fullName || user.email}
                      </p>
                      <p className="mt-0.5 text-slate-500">{user.email}</p>
                    </div>
                    <div className="shrink-0 text-right">
                      <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-semibold ${
                        user.role === Role.ELDERLY
                          ? "bg-sky-100 text-sky-800"
                          : user.role === Role.FAMILY
                          ? "bg-violet-100 text-violet-800"
                          : user.role === Role.PHARMACY
                          ? "bg-indigo-100 text-indigo-800"
                          : "bg-emerald-100 text-emerald-800"
                      }`}>
                        {user.role === Role.ELDERLY ? "Idoso" : user.role === Role.FAMILY ? "Família" : user.role === Role.PHARMACY ? "Farmácia" : "Admin"}
                      </span>
                      <p className="mt-1 text-slate-500">{fmtPt(user.createdAt)}</p>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-base font-semibold text-slate-900">
              Assinaturas recentes
            </h2>
            <p className="mt-1 text-xs text-slate-500">
              Últimas {recentSubscriptions.length} entradas.
            </p>

            {recentSubscriptions.length === 0 ? (
              <p className="mt-4 rounded-xl border border-white/60 bg-white/60 px-3 py-3 text-sm text-slate-600">
                Nenhuma assinatura registrada.
              </p>
            ) : (
              <ul className="mt-3 space-y-2">
                {recentSubscriptions.map((sub) => (
                  <li
                    key={sub.id}
                    className="flex items-start justify-between gap-2 rounded-xl border border-white/60 bg-white/60 px-3 py-2.5 text-xs"
                  >
                    <div className="min-w-0 flex-1">
                      <p className="truncate font-medium text-slate-800">
                        {sub.user.email}
                      </p>
                      <p className="mt-0.5 text-slate-500">
                        R$ {sub.monthlyPriceBrl}
                      </p>
                    </div>
                    <div className="shrink-0 text-right">
                      <span
                        className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-semibold ${
                          sub.status === SubscriptionStatus.ACTIVE
                            ? "bg-emerald-100 text-emerald-800"
                            : sub.status === SubscriptionStatus.REFUND_PENDING
                            ? "bg-amber-100 text-amber-800"
                            : sub.status === SubscriptionStatus.REFUNDED
                            ? "bg-slate-100 text-slate-700"
                            : sub.status === SubscriptionStatus.CANCELED
                            ? "bg-red-100 text-red-800"
                            : "bg-slate-100 text-slate-600"
                        }`}
                      >
                        {sub.status === "DRAFT"
                          ? "Rascunho"
                          : sub.status === "ACTIVE"
                          ? "Ativa"
                          : sub.status === "CANCELED"
                          ? "Cancelada"
                          : sub.status === "REFUND_PENDING"
                          ? "Reembolso"
                          : "Estornado"}
                      </span>
                      <p className="mt-1 text-slate-500">{fmtPt(sub.createdAt)}</p>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </GlassCard>
        </section>
      </main>
      <SiteFooter />
    </div>
  );
}
