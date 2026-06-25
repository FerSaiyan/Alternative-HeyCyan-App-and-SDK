import { Role } from "@prisma/client";
import { SiteFooter } from "@/components/sections/site-footer";
import { SiteHeader } from "@/components/sections/site-header";
import { GlassCard } from "@/components/ui/glass-card";
import { prisma } from "@/lib/prisma";
import { requirePageRole } from "@/lib/role-guard";
import { formatPriceBrl } from "@/lib/business";
import Link from "next/link";

type AffiliatesPageProps = {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

export default async function AdminAffiliatesPage({ searchParams }: AffiliatesPageProps) {
  const params = await searchParams;
  const success = String(params.success ?? "");
  const error = String(params.error ?? "");

  await requirePageRole([Role.ADMIN], "/admin/affiliates");

  const [coupons, users, withdrawals] = await Promise.all([
    prisma.affiliateCoupon.findMany({
      include: {
        owner: { select: { email: true, fullName: true } },
        purchases: { select: { revenueAmount: true, status: true } },
      },
      orderBy: { createdAt: "desc" },
    }),
    prisma.user.findMany({
      where: { role: { in: [Role.ELDERLY, Role.FAMILY] } },
      select: { id: true, email: true, fullName: true, role: true },
      orderBy: { createdAt: "desc" },
      take: 100,
    }),
    prisma.affiliateWithdrawal.findMany({
      include: { owner: { select: { email: true, fullName: true } } },
      orderBy: { requestedAt: "desc" },
      take: 20,
    }),
  ]);

  const successMessages: Record<string, string> = {
    created: "Cupom de afiliado criado com sucesso.",
    withdrawal_updated: "Status do saque atualizado.",
  };
  const successMessage = successMessages[success] ?? null;

  const errorMessages: Record<string, string> = {
    missing_fields: "Preencha todos os campos obrigatórios.",
    code_taken: "Este código de cupom já existe.",
    user_not_found: "Usuário não encontrado.",
    invalid_discount: "Tipo ou valor de desconto inválido.",
    withdrawal_not_found: "Solicitação de saque não encontrada.",
  };
  const errorMessage = errorMessages[error] ?? null;

  return (
    <div className="pb-10">
      <div className="carelens-ambient" />
      <SiteHeader />
      <main className="container-width space-y-5 pt-8 sm:pt-12">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight text-slate-900">
              Gerenciar Afiliados
            </h1>
            <p className="mt-1 text-sm text-slate-600">
              Crie cupons de afiliado e gerencie solicitações de saque.
            </p>
          </div>
          <Link href="/admin" className="btn-secondary !py-2 text-xs">
            Voltar ao painel
          </Link>
        </div>

        {successMessage && (
          <p className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900">
            {successMessage}
          </p>
        )}
        {errorMessage && (
          <p className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-900">
            {errorMessage}
          </p>
        )}

        {/* Create new affiliate coupon */}
        <GlassCard className="p-6 sm:p-7">
          <h2 className="text-base font-semibold text-slate-900">Criar cupom de afiliado</h2>
          <form action="/api/admin/affiliates/create" method="post" className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <label className="text-xs text-slate-700">
              Proprietário
              <select name="ownerId" required className="mt-1 w-full rounded-xl border border-white/60 bg-white/80 px-3 py-2 text-sm text-slate-800">
                <option value="">Selecione um usuário</option>
                {users.map((u) => (
                  <option key={u.id} value={u.id}>
                    {u.fullName || u.email} ({u.role})
                  </option>
                ))}
              </select>
            </label>
            <label className="text-xs text-slate-700">
              Código do cupom
              <input
                name="code"
                required
                placeholder="Ex: DR.CLAKE10"
                className="mt-1 w-full rounded-xl border border-white/60 bg-white/80 px-3 py-2 text-sm text-slate-800 uppercase"
              />
            </label>
            <label className="text-xs text-slate-700">
              Tipo de desconto
              <select name="discountType" required className="mt-1 w-full rounded-xl border border-white/60 bg-white/80 px-3 py-2 text-sm text-slate-800">
                <option value="PERCENT">Percentual (%)</option>
                <option value="FIXED">Valor fixo (R$)</option>
              </select>
            </label>
            <label className="text-xs text-slate-700">
              Valor do desconto
              <input
                name="discountValue"
                type="number"
                step="0.01"
                min="0"
                required
                placeholder="Ex: 10 ou 200"
                className="mt-1 w-full rounded-xl border border-white/60 bg-white/80 px-3 py-2 text-sm text-slate-800"
              />
            </label>
            <div className="sm:col-span-2 lg:col-span-4">
              <button type="submit" className="btn-primary !py-2 text-xs">
                Criar cupom de afiliado
              </button>
            </div>
          </form>
        </GlassCard>

        {/* Existing coupons */}
        <GlassCard className="p-6 sm:p-7">
          <h2 className="text-base font-semibold text-slate-900">Cupons existentes ({coupons.length})</h2>
          {coupons.length === 0 ? (
            <p className="mt-3 text-sm text-slate-500">Nenhum cupom de afiliado criado.</p>
          ) : (
            <div className="mt-4 overflow-x-auto">
              <table className="w-full text-sm text-slate-800">
                <thead>
                  <tr className="border-b border-white/70 text-left">
                    <th className="pb-2 pr-4 font-semibold text-slate-600">Código</th>
                    <th className="pb-2 pr-4 font-semibold text-slate-600">Proprietário</th>
                    <th className="pb-2 pr-4 font-semibold text-slate-600">Desconto</th>
                    <th className="pb-2 pr-4 font-semibold text-slate-600">Usos</th>
                    <th className="pb-2 pr-4 font-semibold text-slate-600">Receita gerada</th>
                    <th className="pb-2 font-semibold text-slate-600">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {coupons.map((c) => {
                    const totalRev = c.purchases
                      .filter((p) => p.status !== "CANCELLED")
                      .reduce((sum, p) => sum + p.revenueAmount, 0);
                    return (
                      <tr key={c.id} className="border-b border-white/50 last:border-0">
                        <td className="py-2 pr-4 font-mono font-bold text-indigo-700">{c.code}</td>
                        <td className="py-2 pr-4">{c.owner.fullName || c.owner.email}</td>
                        <td className="py-2 pr-4">
                          {c.discountType === "FIXED"
                            ? formatPriceBrl(c.discountValue)
                            : `${c.discountValue}%`}
                        </td>
                        <td className="py-2 pr-4">{c.usageCount}</td>
                        <td className="py-2 pr-4 font-semibold text-emerald-700">{formatPriceBrl(totalRev)}</td>
                        <td className="py-2">
                          <span
                            className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                              c.isActive ? "bg-emerald-100 text-emerald-800" : "bg-slate-100 text-slate-600"
                            }`}
                          >
                            {c.isActive ? "Ativo" : "Inativo"}
                          </span>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </GlassCard>

        {/* Withdrawal requests */}
        <GlassCard className="p-6 sm:p-7">
          <h2 className="text-base font-semibold text-slate-900">Solicitações de saque ({withdrawals.length})</h2>
          {withdrawals.length === 0 ? (
            <p className="mt-3 text-sm text-slate-500">Nenhuma solicitação de saque.</p>
          ) : (
            <div className="mt-4 overflow-x-auto">
              <table className="w-full text-sm text-slate-800">
                <thead>
                  <tr className="border-b border-white/70 text-left">
                    <th className="pb-2 pr-4 font-semibold text-slate-600">Usuário</th>
                    <th className="pb-2 pr-4 font-semibold text-slate-600">Valor</th>
                    <th className="pb-2 pr-4 font-semibold text-slate-600">Data</th>
                    <th className="pb-2 pr-4 font-semibold text-slate-600">Status</th>
                    <th className="pb-2 font-semibold text-slate-600">Ação</th>
                  </tr>
                </thead>
                <tbody>
                  {withdrawals.map((w) => (
                    <tr key={w.id} className="border-b border-white/50 last:border-0">
                      <td className="py-2 pr-4">{w.owner.fullName || w.owner.email}</td>
                      <td className="py-2 pr-4 font-semibold">{formatPriceBrl(w.amountBrl)}</td>
                      <td className="py-2 pr-4 text-slate-600">
                        {new Intl.DateTimeFormat("pt-BR", {
                          day: "2-digit",
                          month: "2-digit",
                          year: "numeric",
                        }).format(w.requestedAt)}
                      </td>
                      <td className="py-2 pr-4">
                        <span
                          className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                            w.status === "PAID"
                              ? "bg-emerald-100 text-emerald-800"
                              : w.status === "APPROVED"
                                ? "bg-blue-100 text-blue-800"
                                : w.status === "REJECTED"
                                  ? "bg-red-100 text-red-800"
                                  : "bg-amber-100 text-amber-800"
                          }`}
                        >
                          {w.status === "PAID"
                            ? "Pago"
                            : w.status === "APPROVED"
                              ? "Aprovado"
                              : w.status === "REJECTED"
                                ? "Rejeitado"
                                : "Pendente"}
                        </span>
                      </td>
                      <td className="py-2">
                        {w.status === "PENDING" && (
                          <div className="flex gap-1">
                            <form action="/api/admin/affiliates/withdrawal" method="post">
                              <input type="hidden" name="withdrawalId" value={w.id} />
                              <input type="hidden" name="status" value="APPROVED" />
                              <button type="submit" className="rounded-full bg-emerald-100 px-2 py-0.5 text-xs font-medium text-emerald-800 hover:bg-emerald-200">
                                Aprovar
                              </button>
                            </form>
                            <form action="/api/admin/affiliates/withdrawal" method="post">
                              <input type="hidden" name="withdrawalId" value={w.id} />
                              <input type="hidden" name="status" value="REJECTED" />
                              <button type="submit" className="rounded-full bg-red-100 px-2 py-0.5 text-xs font-medium text-red-800 hover:bg-red-200">
                                Rejeitar
                              </button>
                            </form>
                          </div>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </GlassCard>
      </main>
      <SiteFooter />
    </div>
  );
}
