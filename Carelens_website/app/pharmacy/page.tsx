import { GlassesOrderStatus, Role } from "@prisma/client";
import { SiteFooter } from "@/components/sections/site-footer";
import { SiteHeader } from "@/components/sections/site-header";
import { GlassCard } from "@/components/ui/glass-card";
import { prisma } from "@/lib/prisma";
import { requirePageRole } from "@/lib/role-guard";

type GlassesPageProps = {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

const statusConfig: Record<
  GlassesOrderStatus,
  {
    label: string;
    badge: string;
    next?: { status: GlassesOrderStatus; label: string };
  }
> = {
  ORDERED: {
    label: "Pedido recebido",
    badge: "bg-amber-100 text-amber-800",
    next: { status: GlassesOrderStatus.SHIPPED, label: "Marcar como enviado" },
  },
  SHIPPED: {
    label: "Enviado",
    badge: "bg-blue-100 text-blue-800",
    next: { status: GlassesOrderStatus.DELIVERED, label: "Confirmar entrega" },
  },
  DELIVERED: {
    label: "Entregue",
    badge: "bg-emerald-100 text-emerald-800",
  },
};

function formatPt(date: Date): string {
  return new Intl.DateTimeFormat("pt-BR", {
    day: "2-digit",
    month: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

export default async function GlassesDashboardPage({ searchParams }: GlassesPageProps) {
  const params = await searchParams;
  const updated = String(params.updated ?? "") === "1";
  await requirePageRole([Role.ADMIN], "/admin");

  const [orders, orderedCount, shippedCount, deliveredCount] = await Promise.all([
    prisma.glassesOrder.findMany({
      include: {
        user: {
          select: { email: true, fullName: true },
        },
      },
      orderBy: { updatedAt: "desc" },
      take: 20,
    }),
    prisma.glassesOrder.count({ where: { status: GlassesOrderStatus.ORDERED } }),
    prisma.glassesOrder.count({ where: { status: GlassesOrderStatus.SHIPPED } }),
    prisma.glassesOrder.count({ where: { status: GlassesOrderStatus.DELIVERED } }),
  ]);

  const orderStats = [
    { label: "Pedidos ativos", value: orderedCount + shippedCount },
    { label: "Aguardando envio", value: orderedCount },
    { label: "Enviados", value: shippedCount },
    { label: "Entregues", value: deliveredCount },
  ];

  return (
    <div className="pb-10">
      <div className="carelens-ambient" />
      <SiteHeader />
      <main className="container-width pt-8 sm:pt-12 space-y-5">
        <section className="grid gap-5 lg:grid-cols-[1.1fr,0.9fr]">
          <GlassCard className="glass-card-strong p-6 sm:p-7">
            <p className="pill-eyebrow">Pedidos de Óculos</p>
            <h1 className="mt-3 text-3xl font-semibold tracking-tight text-slate-900">
              Gestão de envios
            </h1>
            <p className="mt-2 text-sm text-muted">
              Atualize o status de envio e entrega dos óculos HeyCyan.
            </p>

            <div className="mt-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              {orderStats.map((stat) => (
                <div
                  key={stat.label}
                  className="rounded-xl border border-white/60 bg-white/65 p-4"
                >
                  <p className="text-xs uppercase tracking-wide text-slate-600">{stat.label}</p>
                  <p className="mt-1 text-2xl font-semibold text-slate-800">{stat.value}</p>
                </div>
              ))}
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-base font-semibold text-slate-900">Fila de envio</h2>
            <p className="mt-1 text-xs text-slate-500">Pedidos aguardando processamento.</p>

            <ul className="mt-4 space-y-3">
              {orders.filter((o) => o.status !== GlassesOrderStatus.DELIVERED).slice(0, 4).map((order) => (
                <li
                  key={order.id}
                  className="rounded-xl border border-white/60 bg-white/65 px-4 py-3"
                >
                  <p className="truncate text-sm font-semibold text-slate-900">{order.user.fullName ?? order.user.email}</p>
                  <p className="mt-0.5 text-xs text-slate-500">
                    Cor: {order.color === "BLACK" ? "Preto" : "Branco"} | Pedido: {formatPt(order.createdAt)}
                  </p>
                  <span
                    className={`mt-2 inline-flex rounded-full px-2.5 py-1 text-xs font-medium ${statusConfig[order.status].badge}`}
                  >
                    {statusConfig[order.status].label}
                  </span>
                </li>
              ))}
              {orders.length === 0 ? (
                <li className="rounded-xl border border-white/60 bg-white/65 px-4 py-3 text-sm text-slate-700">
                  Nenhum pedido para processar.
                </li>
              ) : null}
            </ul>
          </GlassCard>
        </section>

        <section>
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-base font-semibold text-slate-900">Pedidos recentes</h2>
            <p className="mt-1 text-xs text-slate-500">
              Atualize o fluxo de envio e entrega em tempo real.
            </p>

            {updated ? (
              <p className="mt-4 rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900">
                Status do pedido atualizado com sucesso.
              </p>
            ) : null}

            <div className="mt-5 overflow-x-auto">
              <table className="w-full text-sm text-slate-800">
                <thead>
                  <tr className="border-b border-white/70 text-left">
                    <th className="pb-3 pr-4 font-semibold text-slate-600">Pedido</th>
                    <th className="pb-3 pr-4 font-semibold text-slate-600">Cliente</th>
                    <th className="pb-3 pr-4 font-semibold text-slate-600">Cor</th>
                    <th className="pb-3 pr-4 font-semibold text-slate-600">Data</th>
                    <th className="pb-3 pr-4 font-semibold text-slate-600">Status</th>
                    <th className="pb-3 font-semibold text-slate-600">Ação</th>
                  </tr>
                </thead>
                <tbody>
                  {orders.map((order) => {
                    const config = statusConfig[order.status];
                    return (
                      <tr key={order.id} className="border-b border-white/50 last:border-0">
                        <td className="py-3 pr-4 font-mono text-xs text-slate-600">{order.id.slice(0, 8)}</td>
                        <td className="py-3 pr-4 font-medium">{order.user.fullName ?? order.user.email}</td>
                        <td className="py-3 pr-4 text-slate-600">{order.color === "BLACK" ? "Preto" : "Branco"}</td>
                        <td className="py-3 pr-4 text-slate-600">{formatPt(order.createdAt)}</td>
                        <td className="py-3 pr-4">
                          <span className={`rounded-full px-2.5 py-1 text-xs font-medium ${config.badge}`}>
                            {config.label}
                          </span>
                        </td>
                        <td className="py-3">
                          {config.next ? (
                            <form action="/api/pharmacy/orders/status" method="post">
                              <input type="hidden" name="orderId" value={order.id} />
                              <input type="hidden" name="status" value={config.next.status} />
                              <button
                                type="submit"
                                className="rounded-full border border-indigo-300 bg-indigo-50 px-3 py-1.5 text-xs font-medium text-indigo-800 hover:bg-indigo-100"
                              >
                                {config.next.label}
                              </button>
                            </form>
                          ) : (
                            <span className="text-xs text-slate-500">Concluído</span>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </GlassCard>
        </section>
      </main>
      <SiteFooter />
    </div>
  );
}
