import Link from "next/link";

export function SiteFooter() {
  const year = new Date().getFullYear();

  return (
    <footer className="w-full pb-12 pt-16">
      <div className="glass-card px-6 py-7 sm:px-8 sm:py-8">
        <div className="grid gap-6 sm:grid-cols-[1fr,auto]">
          <div className="space-y-4">
            {/* Logo */}
            <span className="text-xl font-bold tracking-tight text-[#24393f]">CyanBridge</span>

            {/* Description */}
            <p className="text-sm leading-relaxed text-[#1f5b66]/88">
              CyanBridge is a software subscription for connecting supported
              smart glasses to chat, voice, and image AI models through a
              managed relay service. This website sells software access only,
              not hardware or medical services.
            </p>

            {/* Navigation links - pill style */}
            <nav className="flex flex-wrap gap-x-3 gap-y-2" aria-label="Footer navigation">
              <Link
                href="/pricing"
                className="rounded-full px-3 py-1.5 text-xs font-medium text-[#1f5b66]/80 transition-colors hover:bg-[#95b6a5]/22 hover:text-brand focus:outline-none focus:ring-2 focus:ring-brand/50 focus:ring-offset-2"
              >
                Pricing
              </Link>
              <Link
                href="/terms"
                className="rounded-full px-3 py-1.5 text-xs font-medium text-[#1f5b66]/80 transition-colors hover:bg-[#95b6a5]/22 hover:text-brand focus:outline-none focus:ring-2 focus:ring-brand/50 focus:ring-offset-2"
              >
                Terms of Use
              </Link>
              <Link
                href="/privacy"
                className="rounded-full px-3 py-1.5 text-xs font-medium text-[#1f5b66]/80 transition-colors hover:bg-[#95b6a5]/22 hover:text-brand focus:outline-none focus:ring-2 focus:ring-brand/50 focus:ring-offset-2"
              >
                Privacy Policy
              </Link>
              <Link
                href="/refund-policy"
                className="rounded-full px-3 py-1.5 text-xs font-medium text-[#1f5b66]/80 transition-colors hover:bg-[#95b6a5]/22 hover:text-brand focus:outline-none focus:ring-2 focus:ring-brand/50 focus:ring-offset-2"
              >
                Refund Policy
              </Link>
              <Link
                href="/support"
                className="rounded-full px-3 py-1.5 text-xs font-medium text-[#1f5b66]/80 transition-colors hover:bg-[#95b6a5]/22 hover:text-brand focus:outline-none focus:ring-2 focus:ring-brand/50 focus:ring-offset-2"
              >
                Support
              </Link>
            </nav>
          </div>
        </div>

        {/* Contact */}
        <div className="mt-6 flex flex-wrap items-center gap-x-6 gap-y-2 border-t border-[#cdbe98]/45 pt-5">
          <div className="text-xs text-[#1f5b66]/80">
            <a
              href="mailto:contato@fersaiyan.com"
              className="rounded text-[#1f5b66]/80 transition-colors hover:text-brand focus:outline-none focus:ring-2 focus:ring-brand/50 focus:ring-offset-2"
            >
              contato@fersaiyan.com
            </a>
          </div>
        </div>

        {/* Copyright */}
        <div className="mt-5 text-xs text-[#1f5b66]/64">
          CyanBridge &copy; {year}. All rights reserved.
        </div>
      </div>
    </footer>
  );
}
