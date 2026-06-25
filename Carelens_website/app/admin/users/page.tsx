import Link from "next/link";
import { Role } from "@prisma/client";
import { SiteFooter } from "@/components/sections/site-footer";
import { SiteHeader } from "@/components/sections/site-header";
import { GlassCard } from "@/components/ui/glass-card";
import { UsersFilter } from "@/components/admin/users-filter";
import { prisma } from "@/lib/prisma";
import { requirePageRole } from "@/lib/role-guard";

type AdminUsersPageProps = {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

const roleLabel: Record<Role, string> = {
  [Role.ELDERLY]: "Idoso",
  [Role.FAMILY]: "Família",
  [Role.PHARMACY]: "Farmácia",
  [Role.ADMIN]: "Admin",
};

const roleBadge: Record<Role, string> = {
  [Role.ELDERLY]: "bg-sky-100 text-sky-800",
  [Role.FAMILY]: "bg-violet-100 text-violet-800",
  [Role.PHARMACY]: "bg-indigo-100 text-indigo-800",
  [Role.ADMIN]: "bg-emerald-100 text-emerald-800",
};

export default async function AdminUsersPage({ searchParams }: AdminUsersPageProps) {
  const params = await searchParams;
  const updated = String(params.updated ?? "") === "1";
  const created = String(params.created ?? "") === "1";
  const passwordReset = String(params.password_reset ?? "") === "1";
  const error = String(params.error ?? "");
  const adminUser = await requirePageRole([Role.ADMIN], "/admin/users");

  const users = await prisma.user.findMany({
    orderBy: [{ role: "asc" }, { createdAt: "desc" }],
    select: {
      id: true,
      email: true,
      username: true,
      role: true,
      createdAt: true,
    },
    take: 200,
  });

  return (
    <div className="pb-10">
      <div className="carelens-ambient" />
      <SiteHeader />
      <main className="container-width pt-8 sm:pt-12 space-y-5">
        <section className="grid gap-5 lg:grid-cols-[1.1fr,0.9fr]">
          <GlassCard className="glass-card-strong p-6 sm:p-7">
            <p className="pill-eyebrow">Administração de acesso</p>
            <h1 className="mt-3 text-3xl font-semibold tracking-tight text-slate-900">
              Usuários e perfis
            </h1>
            <p className="mt-2 text-sm text-muted">
              Gerencie os perfis de acesso para áreas de idoso, família, farmácia e admin.
            </p>
            <p className="mt-1 text-xs text-slate-600">Sessão administrativa: {adminUser.email}</p>

            <div className="mt-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <article className="rounded-xl border border-white/60 bg-white/65 p-4">
                <p className="text-xs uppercase tracking-wide text-slate-600">Total</p>
                <p className="mt-1 text-2xl font-semibold text-slate-800">{users.length}</p>
              </article>
              <article className="rounded-xl border border-white/60 bg-white/65 p-4">
                <p className="text-xs uppercase tracking-wide text-slate-600">Familiares</p>
                <p className="mt-1 text-2xl font-semibold text-slate-800">
                  {users.filter((u) => u.role === Role.FAMILY).length}
                </p>
              </article>
              <article className="rounded-xl border border-white/60 bg-white/65 p-4">
                <p className="text-xs uppercase tracking-wide text-slate-600">Idosos</p>
                <p className="mt-1 text-2xl font-semibold text-slate-800">
                  {users.filter((u) => u.role === Role.ELDERLY).length}
                </p>
              </article>
              <article className="rounded-xl border border-white/60 bg-white/65 p-4">
                <p className="text-xs uppercase tracking-wide text-slate-600">Farmácias</p>
                <p className="mt-1 text-2xl font-semibold text-slate-800">
                  {users.filter((u) => u.role === Role.PHARMACY).length}
                </p>
              </article>
            </div>
          </GlassCard>

          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-base font-semibold text-slate-900">Ações rápidas</h2>
            <p className="mt-1 text-xs text-slate-500">
              Ajustes de perfil entram em vigor no próximo acesso via magic link.
            </p>

            <div className="mt-4 space-y-2 text-sm">
              <Link href="/admin" className="block rounded-xl border border-white/60 bg-white/65 px-4 py-3 hover:bg-white/80">
                Voltar para painel administrativo
              </Link>
              <Link href="/signin" className="block rounded-xl border border-white/60 bg-white/65 px-4 py-3 hover:bg-white/80">
                Abrir login para testar perfis
              </Link>
            </div>

            {updated ? (
              <p className="mt-5 rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900">
                Perfil atualizado com sucesso.
              </p>
            ) : null}

            {passwordReset ? (
              <p className="mt-5 rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900">
                Senha redefinida com sucesso.
              </p>
            ) : null}

            {error === "self" ? (
              <p className="mt-5 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
                Você não pode remover seu próprio perfil de admin.
              </p>
            ) : null}

            {error === "invalid" || error === "notfound" ? (
              <p className="mt-5 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-900">
                Não foi possível atualizar o perfil solicitado.
              </p>
            ) : null}
          </GlassCard>
        </section>

        <section>
          <GlassCard className="glass-card-strong p-6 sm:p-7">
            <h2 className="text-base font-semibold text-slate-900">Criar conta de familiar</h2>
            <p className="mt-1 text-xs text-slate-500">
              Preencha os dados abaixo para criar uma nova conta de familiar. O familiar poderá fazer login com e-mail ou nome de usuário.
            </p>

            {created ? (
              <p className="mt-4 rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900">
                Familiar criado com sucesso.
              </p>
            ) : null}

            {error === "missing_fields" ? (
              <p className="mt-4 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-900">
                Preencha todos os campos obrigatórios.
              </p>
            ) : null}

            {error === "weak_password" ? (
              <p className="mt-4 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-900">
                A senha deve ter pelo menos 8 caracteres.
              </p>
            ) : null}

            {error === "invalid_username" ? (
              <p className="mt-4 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-900">
                Nome de usuário inválido. Use 3-30 caracteres: letras, números, pontos e underscores.
              </p>
            ) : null}

            {error === "email_exists" ? (
              <p className="mt-4 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
                Este e-mail já está cadastrado.
              </p>
            ) : null}

            {error === "username_exists" ? (
              <p className="mt-4 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
                Este nome de usuário já está em uso.
              </p>
            ) : null}

            <form action="/api/admin/family" method="post" className="mt-5 grid gap-3 sm:grid-cols-2">
              <label className="text-xs text-slate-700">
                Nome completo *
                <input
                  name="fullName"
                  required
                  placeholder="Nome do familiar"
                  className="mt-1 w-full rounded-xl border border-white/60 bg-white/80 px-3 py-2 text-sm text-slate-800"
                />
              </label>

              <label className="text-xs text-slate-700">
                E-mail *
                <input
                  name="email"
                  type="email"
                  required
                  placeholder="familiar@carelens.com.br"
                  className="mt-1 w-full rounded-xl border border-white/60 bg-white/80 px-3 py-2 text-sm text-slate-800"
                />
              </label>

              <label className="text-xs text-slate-700">
                Nome de usuário *
                <input
                  name="username"
                  required
                  minLength={3}
                  placeholder="familiar.carelens"
                  className="mt-1 w-full rounded-xl border border-white/60 bg-white/80 px-3 py-2 text-sm text-slate-800"
                />
              </label>

              <label className="text-xs text-slate-700">
                Senha temporária *
                <input
                  name="password"
                  type="text"
                  required
                  minLength={8}
                  placeholder="Mínimo 8 caracteres"
                  className="mt-1 w-full rounded-xl border border-white/60 bg-white/80 px-3 py-2 text-sm text-slate-800"
                />
              </label>

              <label className="text-xs text-slate-700 sm:col-span-2">
                Parentesco
                <input
                  name="relationship"
                  placeholder="Filho(a), neto(a), cuidador"
                  className="mt-1 w-full rounded-xl border border-white/60 bg-white/80 px-3 py-2 text-sm text-slate-800"
                />
              </label>

              <div className="sm:col-span-2">
                <button type="submit" className="btn-primary !py-2 text-xs">
                  Criar familiar
                </button>
              </div>
            </form>
          </GlassCard>
        </section>

        <section>
          <GlassCard className="p-6 sm:p-7">
            <h2 className="text-base font-semibold text-slate-900">Lista de usuários</h2>
            <p className="mt-1 text-xs text-slate-500">Atualize o perfil de cada usuário abaixo.</p>

            <div className="mt-5">
              <UsersFilter users={users} roleLabel={roleLabel} roleBadge={roleBadge} />
            </div>
          </GlassCard>
        </section>
      </main>
      <SiteFooter />
    </div>
  );
}
