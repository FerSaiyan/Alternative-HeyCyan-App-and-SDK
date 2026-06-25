"use client";

import { useState } from "react";

type AffiliatePurchase = {
  id: string;
  buyerEmail: string;
  purchaseValue: number;
  revenueAmount: number;
  status: string;
  createdAt: string;
};

type MonthlyData = {
  month: string;
  count: number;
  revenue: number;
};

type AffiliateDashboardProps = {
  couponCode: string;
  couponDiscountLabel: string;
  totalReferrals: number;
  totalRevenue: number;
  pendingBalance: number;
  availableBalance: number;
  recentPurchases: AffiliatePurchase[];
  monthlyData: MonthlyData[];
  supportUrl: string;
};

export function AffiliateDashboard({
  couponCode,
  couponDiscountLabel,
  totalReferrals,
  totalRevenue,
  pendingBalance,
  availableBalance,
  recentPurchases,
  monthlyData,
  supportUrl,
}: AffiliateDashboardProps) {
  const [copied, setCopied] = useState(false);

  const maxRevenue = Math.max(...monthlyData.map((m) => m.revenue), 1);

  function copyCouponLink() {
    const url = `https://carelens.com.br/?coupon=${couponCode}`;
    navigator.clipboard.writeText(url).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  }

  return (
    <div className="space-y-6">
      {/* Coupon info */}
      <div className="onboarding-data-card">
        <p className="onboarding-data-card-title">Seu cupom de afiliado</p>
        <div className="mt-3 flex flex-wrap items-center gap-3">
          <code className="rounded-lg bg-indigo-100 px-4 py-2 text-lg font-bold text-indigo-800">
            {couponCode}
          </code>
          <span className="text-sm text-slate-600">{couponDiscountLabel}</span>
        </div>
        <p className="mt-2 text-xs text-slate-500">
          Link de afiliado: <span className="font-mono text-slate-700">https://carelens.com.br/?coupon={couponCode}</span>
        </p>
        <button
          type="button"
          onClick={copyCouponLink}
          className="btn-secondary mt-3 !py-1.5 text-xs"
        >
          {copied ? "Link copiado!" : "Copiar link de afiliado"}
        </button>
      </div>

      {/* Stats cards */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <div className="onboarding-data-card">
          <p className="text-xs uppercase tracking-wide text-slate-600">Indicações</p>
          <p className="mt-1 text-2xl font-semibold text-slate-800">{totalReferrals}</p>
        </div>
        <div className="onboarding-data-card">
          <p className="text-xs uppercase tracking-wide text-slate-600">Receita total</p>
          <p className="mt-1 text-2xl font-semibold text-emerald-700">R$ {totalRevenue.toFixed(2)}</p>
        </div>
        <div className="onboarding-data-card">
          <p className="text-xs uppercase tracking-wide text-slate-600">Pendente</p>
          <p className="mt-1 text-2xl font-semibold text-amber-700">R$ {pendingBalance.toFixed(2)}</p>
        </div>
        <div className="onboarding-data-card">
          <p className="text-xs uppercase tracking-wide text-slate-600">Disponível</p>
          <p className="mt-1 text-2xl font-semibold text-indigo-700">R$ {availableBalance.toFixed(2)}</p>
        </div>
      </div>

      {/* Monthly chart */}
      {monthlyData.length > 0 && (
        <div className="onboarding-data-card">
          <p className="onboarding-data-card-title">Receita mensal (últimos 6 meses)</p>
          <div className="mt-4 flex items-end gap-2" style={{ height: "160px" }}>
            {monthlyData.map((m) => {
              const heightPct = maxRevenue > 0 ? (m.revenue / maxRevenue) * 100 : 0;
              return (
                <div key={m.month} className="flex flex-1 flex-col items-center gap-1">
                  <span className="text-[10px] font-medium text-slate-600">
                    {m.revenue > 0 ? `R$${m.revenue.toFixed(0)}` : ""}
                  </span>
                  <div
                    className="w-full rounded-t-lg bg-gradient-to-t from-indigo-500 to-indigo-400 transition-all"
                    style={{ height: `${Math.max(heightPct, 4)}%` }}
                  />
                  <span className="text-[10px] text-slate-500">{m.month}</span>
                  <span className="text-[10px] text-slate-400">{m.count} venda{m.count !== 1 ? "s" : ""}</span>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* Withdrawal actions */}
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="onboarding-data-card">
          <p className="onboarding-data-card-title">Sacar saldo</p>
          <p className="mt-2 text-sm text-slate-600">
            Entre em contato com o suporte para solicitar o saque do seu saldo disponível.
          </p>
          <a
            href={supportUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="btn-primary mt-3 inline-block text-xs"
          >
            Contatar suporte para saque
          </a>
        </div>
        <div className="onboarding-data-card">
          <p className="onboarding-data-card-title">Usar como crédito</p>
          <p className="mt-2 text-sm text-slate-600">
            Use seu saldo como desconto no próximo plano de tratamento de 6 meses.
          </p>
          <form action="/api/affiliate/credit" method="post">
            <button type="submit" className="btn-secondary mt-3 text-xs">
              Usar crédito no próximo tratamento
            </button>
          </form>
        </div>
      </div>

      {/* Recent purchases table */}
      <div className="onboarding-data-card">
        <p className="onboarding-data-card-title">Compras recentes com seu cupom</p>
        {recentPurchases.length === 0 ? (
          <p className="mt-3 text-sm text-slate-500">Nenhuma compra registrada com seu cupom ainda.</p>
        ) : (
          <div className="mt-4 overflow-x-auto">
            <table className="w-full text-sm text-slate-800">
              <thead>
                <tr className="border-b border-white/70 text-left">
                  <th className="pb-2 pr-4 font-semibold text-slate-600">Paciente</th>
                  <th className="pb-2 pr-4 font-semibold text-slate-600">Data</th>
                  <th className="pb-2 pr-4 font-semibold text-slate-600">Valor</th>
                  <th className="pb-2 pr-4 font-semibold text-slate-600">Sua receita</th>
                  <th className="pb-2 font-semibold text-slate-600">Status</th>
                </tr>
              </thead>
              <tbody>
                {recentPurchases.map((p) => (
                  <tr key={p.id} className="border-b border-white/50 last:border-0">
                    <td className="py-2 pr-4 font-medium">{p.buyerEmail}</td>
                    <td className="py-2 pr-4 text-slate-600">{p.createdAt}</td>
                    <td className="py-2 pr-4">R$ {p.purchaseValue.toFixed(2)}</td>
                    <td className="py-2 pr-4 font-semibold text-emerald-700">R$ {p.revenueAmount.toFixed(2)}</td>
                    <td className="py-2">
                      <span
                        className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                          p.status === "CONFIRMED"
                            ? "bg-emerald-100 text-emerald-800"
                            : p.status === "PENDING"
                              ? "bg-amber-100 text-amber-800"
                              : "bg-red-100 text-red-800"
                        }`}
                      >
                        {p.status === "CONFIRMED" ? "Confirmado" : p.status === "PENDING" ? "Pendente" : "Cancelado"}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
