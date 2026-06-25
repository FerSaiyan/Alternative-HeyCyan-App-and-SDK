import Link from "next/link";

/**
 * SiteFooter - CareLens AI footer
 *
 * - Brand logo "CareLens AI"
 * - Required disclaimer
 * - Navigation links: Termos, Privacidade, Suporte
 * - Contact email: contato@carelens.com.br
 * - Copyright "CareLens AI © {year}"
 */
export function SiteFooter() {
  const year = new Date().getFullYear();

  return (
    <footer className="w-full pb-12 pt-16">
      <div className="glass-card px-6 py-7 sm:px-8 sm:py-8">
        <div className="grid gap-6 sm:grid-cols-[1fr,auto]">
          <div className="space-y-4">
            {/* Logo */}
            <span className="text-xl font-bold tracking-tight text-[#24393f]">CareLens AI</span>

            {/* Required disclaimer */}
            <p className="text-sm leading-relaxed text-[#1f5b66]/88">
              A CareLens é uma plataforma de tecnologia assistiva que utiliza inteligência artificial para apoiar idosos no dia a dia. Os óculos HeyCyan são dispositivos de assistência e não substituem acompanhamento médico profissional. Em caso de emergência, ligue para 190 (SAMU) ou procure o pronto-socorro mais próximo.
            </p>

            {/* Navigation links - pill style */}
            <nav className="flex flex-wrap gap-x-3 gap-y-2" aria-label="Navegação do rodapé">
              <Link
                href="/termos"
                className="rounded-full px-3 py-1.5 text-xs font-medium text-[#1f5b66]/80 transition-colors hover:bg-[#95b6a5]/22 hover:text-brand focus:outline-none focus:ring-2 focus:ring-brand/50 focus:ring-offset-2"
              >
                Termos
              </Link>
              <Link
                href="/privacidade"
                className="rounded-full px-3 py-1.5 text-xs font-medium text-[#1f5b66]/80 transition-colors hover:bg-[#95b6a5]/22 hover:text-brand focus:outline-none focus:ring-2 focus:ring-brand/50 focus:ring-offset-2"
              >
                Privacidade
              </Link>
              <Link
                href="/privacidade/dados"
                className="rounded-full px-3 py-1.5 text-xs font-medium text-[#1f5b66]/80 transition-colors hover:bg-[#95b6a5]/22 hover:text-brand focus:outline-none focus:ring-2 focus:ring-brand/50 focus:ring-offset-2"
              >
                Retenção de Dados
              </Link>
              <Link
                href="/support"
                className="rounded-full px-3 py-1.5 text-xs font-medium text-[#1f5b66]/80 transition-colors hover:bg-[#95b6a5]/22 hover:text-brand focus:outline-none focus:ring-2 focus:ring-brand/50 focus:ring-offset-2"
              >
                Suporte
              </Link>
            </nav>
          </div>
        </div>

        {/* Contact */}
        <div className="mt-6 flex flex-wrap items-center gap-x-6 gap-y-2 border-t border-[#cdbe98]/45 pt-5">
          <div className="text-xs text-[#1f5b66]/80">
            <a
              href="mailto:contato@carelens.com.br"
              className="rounded text-[#1f5b66]/80 transition-colors hover:text-brand focus:outline-none focus:ring-2 focus:ring-brand/50 focus:ring-offset-2"
            >
              contato@carelens.com.br
            </a>
          </div>
        </div>

        {/* Copyright */}
        <div className="mt-5 text-xs text-[#1f5b66]/64">
          CareLens AI &copy; {year}. Todos os direitos reservados.
        </div>
      </div>
    </footer>
  );
}
