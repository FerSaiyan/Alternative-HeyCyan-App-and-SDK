"use client";

import { useState } from "react";
import { Role } from "@prisma/client";

const roleOptions = [
  { value: "ALL", label: "Todos" },
  { value: Role.ELDERLY, label: "Idoso" },
  { value: Role.FAMILY, label: "Família" },
  { value: Role.PHARMACY, label: "Farmácia" },
  { value: Role.ADMIN, label: "Admin" },
];

type User = {
  id: string;
  email: string;
  username: string | null;
  role: Role;
  createdAt: Date;
};

type Props = {
  users: User[];
  roleLabel: Record<Role, string>;
  roleBadge: Record<Role, string>;
};

export function UsersFilter({ users, roleLabel, roleBadge }: Props) {
  const [filter, setFilter] = useState<string>("ALL");
  const [editingId, setEditingId] = useState<string | null>(null);
  const [resettingId, setResettingId] = useState<string | null>(null);

  const filtered = filter === "ALL" ? users : users.filter((u) => u.role === filter);

  return (
    <div>
      <div className="mb-4 flex items-center gap-3">
        <label className="text-xs text-slate-700">
          Filtrar por perfil:
        </label>
        <select
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          className="onboarding-field-select !w-auto"
        >
          {roleOptions.map((opt) => (
            <option key={opt.value} value={opt.value}>{opt.label}</option>
          ))}
        </select>
        <span className="text-xs text-slate-500">{filtered.length} usuários</span>
      </div>

      <div className="overflow-x-auto">
        <table className="w-full text-sm text-slate-800">
          <thead>
            <tr className="border-b border-white/70 text-left">
              <th className="pb-3 pr-4 font-semibold text-slate-600">E-mail</th>
              <th className="pb-3 pr-4 font-semibold text-slate-600">Username</th>
              <th className="pb-3 pr-4 font-semibold text-slate-600">Perfil atual</th>
              <th className="pb-3 pr-4 font-semibold text-slate-600">Criado em</th>
              <th className="pb-3 pr-4 font-semibold text-slate-600">Alterar perfil</th>
              <th className="pb-3 font-semibold text-slate-600">Ações</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((user) => (
              <tr key={user.id} className="border-b border-white/50 last:border-0">
                <td className="py-3 pr-4 font-medium text-slate-900">{user.email}</td>
                <td className="py-3 pr-4 text-xs text-slate-600">{user.username || "—"}</td>
                <td className="py-3 pr-4">
                  <span className={`rounded-full px-2.5 py-1 text-xs font-semibold ${roleBadge[user.role]}`}>
                    {roleLabel[user.role]}
                  </span>
                </td>
                <td className="py-3 pr-4 text-xs text-slate-600">
                  {new Intl.DateTimeFormat("pt-BR", {
                    day: "2-digit", month: "2-digit", year: "numeric",
                    hour: "2-digit", minute: "2-digit",
                  }).format(user.createdAt)}
                </td>
                <td className="py-3">
                  <form action="/api/admin/users/role" method="post" className="flex flex-wrap items-center gap-2">
                    <input type="hidden" name="userId" value={user.id} />
                      <select
                        name="role"
                        defaultValue={user.role}
                        className="rounded-full border border-white/60 bg-white/80 px-3 py-1.5 text-xs font-medium text-slate-700"
                      >
                        <option value={Role.ELDERLY}>Idoso</option>
                        <option value={Role.FAMILY}>Familiar</option>
                        <option value={Role.PHARMACY}>Farmácia</option>
                        <option value={Role.ADMIN}>Admin</option>
                      </select>
                    <button
                      type="submit"
                      className="rounded-full border border-indigo-300 bg-indigo-50 px-3 py-1.5 text-xs font-medium text-indigo-800 hover:bg-indigo-100"
                    >
                      Salvar
                    </button>
                  </form>
                </td>
                <td className="py-3">
                  <div className="flex flex-wrap gap-1.5">
                    <button
                      type="button"
                      onClick={() => {
                        setEditingId(editingId === user.id ? null : user.id);
                        setResettingId(null);
                      }}
                      className="rounded-full border border-slate-300 bg-white/80 px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-white"
                    >
                      Editar
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setResettingId(resettingId === user.id ? null : user.id);
                        setEditingId(null);
                      }}
                      className="rounded-full border border-amber-300 bg-amber-50 px-2.5 py-1 text-xs font-medium text-amber-800 hover:bg-amber-100"
                    >
                      Resetar senha
                    </button>
                  </div>

                  {/* Inline Edit Form */}
                  {editingId === user.id && (
                    <div className="mt-3 rounded-xl border border-white/60 bg-white/80 p-4">
                      <form action="/api/admin/users/edit" method="post" className="space-y-3">
                        <input type="hidden" name="userId" value={user.id} />
                        <div>
                          <label className="onboarding-field-label">Nome completo</label>
                          <input
                            name="fullName"
                            type="text"
                            placeholder="Nome completo"
                            className="onboarding-field-input"
                          />
                        </div>
                        <div>
                          <label className="onboarding-field-label">E-mail</label>
                          <input
                            name="email"
                            type="email"
                            defaultValue={user.email}
                            required
                            className="onboarding-field-input"
                          />
                        </div>
                        <div>
                          <label className="onboarding-field-label">Username</label>
                          <input
                            name="username"
                            type="text"
                            defaultValue={user.username ?? ""}
                            placeholder="username"
                            className="onboarding-field-input"
                          />
                        </div>
                        <div className="flex gap-2">
                          <button type="submit" className="btn-primary !py-1.5 text-xs">
                            Salvar alterações
                          </button>
                          <button
                            type="button"
                            onClick={() => setEditingId(null)}
                            className="rounded-full border border-white/60 bg-white/80 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-white"
                          >
                            Cancelar
                          </button>
                        </div>
                      </form>
                    </div>
                  )}

                  {/* Inline Reset Password Form */}
                  {resettingId === user.id && (
                    <div className="mt-3 rounded-xl border border-white/60 bg-white/80 p-4">
                      <form action="/api/admin/users/reset-password" method="post" className="space-y-3">
                        <input type="hidden" name="userId" value={user.id} />
                        <div>
                          <label className="onboarding-field-label">Nova senha (mín. 8 caracteres)</label>
                          <input
                            name="newPassword"
                            type="text"
                            required
                            minLength={8}
                            placeholder="Nova senha temporária"
                            className="onboarding-field-input"
                          />
                        </div>
                        <div className="flex gap-2">
                          <button type="submit" className="btn-primary !py-1.5 text-xs">
                            Definir nova senha
                          </button>
                          <button
                            type="button"
                            onClick={() => setResettingId(null)}
                            className="rounded-full border border-white/60 bg-white/80 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-white"
                          >
                            Cancelar
                          </button>
                        </div>
                      </form>
                    </div>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
