"use client";

import Link from "next/link";
import { useState, useEffect, useRef } from "react";

export function SiteHeader() {
  const [isScrolled, setIsScrolled] = useState(false);
  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false);
  const mobileMenuRef = useRef<HTMLDetailsElement>(null);

  useEffect(() => {
    const handleScroll = () => {
      setIsScrolled(window.scrollY > 10);
    };
    window.addEventListener("scroll", handleScroll, { passive: true });
    handleScroll();
    return () => window.removeEventListener("scroll", handleScroll);
  }, []);

  // Close mobile menu when clicking a link
  const handleMobileNavClick = () => {
    setIsMobileMenuOpen(false);
    mobileMenuRef.current?.removeAttribute("open");
  };

  return (
    <header
      className="sticky top-0 z-50 w-full"
      style={{ paddingTop: "0.375rem", paddingBottom: "0.375rem" }}
    >
      <div
        className={`container-width relative flex items-center justify-between rounded-full px-3 py-1.5 transition-all duration-200 ${
          isScrolled
            ? "bg-white/80 backdrop-blur-md border border-white/60 shadow-sm"
            : "bg-white/60 backdrop-blur-sm border border-white/40"
        } sm:px-4`}
      >
        {/* Mobile: Menu button (left) / Desktop: Logo text (left) */}
        <div className="flex items-center gap-2">
          {/* Mobile menu button - visible only on small screens */}
          <details
            ref={mobileMenuRef}
            className="dropdown-menu relative"
            open={isMobileMenuOpen}
            onToggle={(e) => setIsMobileMenuOpen((e.target as HTMLDetailsElement).open)}
          >
            <summary
              aria-label="Open menu"
              aria-haspopup="menu"
              className="flex h-8 min-w-[32px] cursor-pointer items-center justify-center rounded-full border border-[#cdbe98]/60 bg-white/80 px-2.5 hover:bg-white focus:outline-none focus:ring-2 focus:ring-brand/50 focus:ring-offset-1"
            >
              <svg
                className="h-4 w-4 text-[#24393f]"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
                strokeWidth={2}
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  d="M4 6h16M4 12h16M4 18h16"
                />
              </svg>
            </summary>
            <div className="dropdown-panel absolute left-0 top-full mt-2 w-56 rounded-xl border border-white/70 bg-white/95 p-3 shadow-lg backdrop-blur">
              <nav
                role="menu"
                className="flex flex-col gap-1 text-sm text-[#24393f]"
                aria-label="Mobile menu"
              >
                <Link
                  href="/#servicos"
                  onClick={handleMobileNavClick}
                  className="rounded-lg px-3 py-2.5 hover:bg-[#95b6a5]/22 focus:outline-none focus:ring-2 focus:ring-brand/50"
                  role="menuitem"
                >
                  Product
                </Link>
                <Link
                  href="/#caminho"
                  onClick={handleMobileNavClick}
                  className="rounded-lg px-3 py-2.5 hover:bg-[#95b6a5]/22 focus:outline-none focus:ring-2 focus:ring-brand/50"
                  role="menuitem"
                >
                  Features
                </Link>
                <Link
                  href="/#precos"
                  onClick={handleMobileNavClick}
                  className="rounded-lg px-3 py-2.5 hover:bg-[#95b6a5]/22 focus:outline-none focus:ring-2 focus:ring-brand/50"
                  role="menuitem"
                >
                  Pricing
                </Link>
                <Link
                  href="/#confianca"
                  onClick={handleMobileNavClick}
                  className="rounded-lg px-3 py-2.5 hover:bg-[#95b6a5]/22 focus:outline-none focus:ring-2 focus:ring-brand/50"
                  role="menuitem"
                >
                  Use Cases
                </Link>
                <Link
                  href="/#faq"
                  onClick={handleMobileNavClick}
                  className="rounded-lg px-3 py-2.5 hover:bg-[#95b6a5]/22 focus:outline-none focus:ring-2 focus:ring-brand/50"
                  role="menuitem"
                >
                  FAQ
                </Link>
                <Link
                  href="/#cta-final"
                  onClick={handleMobileNavClick}
                  className="rounded-lg px-3 py-2.5 hover:bg-[#95b6a5]/22 focus:outline-none focus:ring-2 focus:ring-brand/50"
                  role="menuitem"
                >
                  Contact
                </Link>
                <hr className="my-1 border-white/70" />
                <Link
                  href="/support"
                  onClick={handleMobileNavClick}
                  className="rounded-lg px-3 py-2.5 hover:bg-[#95b6a5]/22 focus:outline-none focus:ring-2 focus:ring-brand/50"
                  role="menuitem"
                >
                  Support
                </Link>
              </nav>
            </div>
          </details>

          {/* Desktop logo - image based, visible on md+ */}
          <Link
            href="/"
            className="hidden md:flex md:items-center"
            aria-label="CyanBridge home"
          >
            <span className="text-xl font-bold tracking-tight text-[#24393f]">CyanBridge</span>
          </Link>
        </div>

        {/* Mobile: Logo (center) / CTA (right) */}
        <div className="flex items-center gap-2">
          {/* Mobile: Logo - visible only on small screens, centered */}
          <Link
            href="/"
            className="md:hidden flex items-center"
            aria-label="CyanBridge"
          >
            <span className="text-lg font-bold tracking-tight text-[#24393f]">CyanBridge</span>
          </Link>

          {/* Desktop CTA */}
          <Link
            href="/pricing"
            className="btn-primary px-4 py-1.5 text-xs focus:outline-none focus:ring-2 focus:ring-brand/50 focus:ring-offset-1 rounded-full hidden md:inline-flex"
          >
            View Pricing
          </Link>
        </div>
      </div>
    </header>
  );
}
