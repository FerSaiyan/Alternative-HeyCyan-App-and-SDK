import Link from "next/link";
import { SiteHeader } from "@/components/sections/site-header";
import { GlassCard } from "@/components/ui/glass-card";

type CheckEmailPageProps = {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

export default async function CheckEmailPage({ searchParams }: CheckEmailPageProps) {
  const params = await searchParams;
  const email = String(params.email ?? "");
  const previewLink = String(params.preview ?? "");

  return (
    <div className="pb-10">
      <div className="carelens-ambient" />
      <SiteHeader />
      <main className="container-width pt-8 sm:pt-12">
        <section className="mx-auto max-w-xl space-y-5">
          <GlassCard className="glass-card-strong p-6 sm:p-7">
            <p className="pill-eyebrow">Verifique seu e-mail</p>
            <h1 className="mt-3 text-3xl font-semibold tracking-tight text-slate-900">Link enviado</h1>
            <p className="mt-2 text-sm text-muted">Acabamos de enviar um link de acesso para {email || "seu e-mail"}.</p>

            {previewLink ? (
              <div className="mt-5 rounded-xl border border-indigo-200 bg-indigo-50 p-4 text-sm text-indigo-900">
                <p className="font-semibold">Ambiente de teste</p>
                <p className="mt-1 text-xs">Use o link abaixo para concluir o login:</p>
                <Link href={previewLink} className="mt-2 inline-block text-xs font-semibold underline break-all">
                  {previewLink}
                </Link>
              </div>
            ) : null}
          </GlassCard>
        </section>
      </main>
    </div>
  );
}
