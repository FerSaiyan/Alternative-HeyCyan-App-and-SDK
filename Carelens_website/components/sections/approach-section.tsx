/**
 * ApproachSection - "Nossa abordagem" reference-like data section
 *
 * Section 2 of 8:
 * - Eyebrow + Heading + Body text
 * - 3 floating glass widgets with micro-UI details (lines/progress/dots)
 * - Subtle grid background, restrained glassmorphism
 * - Mobile-safe stacking, no overflow
 */
import { GlassCard } from "@/components/ui/glass-card";
import { SectionHeading } from "@/components/ui/section-heading";

// Floating widget data with micro-UI details
const widgets = [
  {
    id: "setup",
    metric: "< 15",
    unit: "minutos",
    label: "Configuração inicial",
    detail: "tempo para ativar a IA",
    microLine: true,
  },
  {
    id: "monitoring",
    metric: "24/7",
    unit: "horas",
    label: "Monitoramento contínuo",
    detail: "IA sempre ativa",
    microProgress: true,
  },
  {
    id: "support",
    metric: "suporte",
    unit: "dedicado",
    label: "Suporte técnico",
    detail: "resposta rápida",
    microDots: true,
  },
];

/**
 * MicroUI component - tiny visual details inside widgets
 * Renders mini lines, progress bar, or dots pattern
 */
function MicroUI({ type }: { type: "line" | "progress" | "dots" }) {
  if (type === "line") {
    return (
      <div className="mt-3 space-y-1.5" aria-hidden="true">
        <div className="h-0.5 w-full rounded-full bg-[#cdbe98]/50" />
        <div className="h-0.5 w-4/5 rounded-full bg-[#cdbe98]/35" />
        <div className="h-0.5 w-3/5 rounded-full bg-[#95b6a5]/35" />
      </div>
    );
  }
  if (type === "progress") {
    return (
      <div className="mt-3" aria-hidden="true">
        <div className="flex h-1 w-full items-center gap-1">
          {[...Array(5)].map((_, i) => (
            <div
              key={i}
              className={`h-full flex-1 rounded-full transition-all ${
                i < 4 ? "bg-[#95b6a5]/75" : "bg-[#cdbe98]/35"
              }`}
              style={{ width: `${18 + i * 2}%` }}
            />
          ))}
        </div>
      </div>
    );
  }
  // dots
  return (
    <div className="mt-3 flex gap-1" aria-hidden="true">
      {[...Array(6)].map((_, i) => (
        <div
          key={i}
          className={`h-1.5 w-1.5 rounded-full ${
            i % 2 === 0 ? "bg-[#95b6a5]/60" : "bg-[#cdbe98]/40"
          }`}
        />
      ))}
    </div>
  );
}

export function ApproachSection() {
  return (
    <section id="abordagem" className="relative -mt-2 py-section scroll-mt-20 sm:scroll-mt-24">
      {/* Subtle grid background */}
      <div
        className="pointer-events-none absolute inset-0 opacity-[0.03]"
        aria-hidden="true"
      >
        <div
          className="h-full w-full"
          style={{
            backgroundImage: `linear-gradient(#24393f 1px, transparent 1px),
                            linear-gradient(90deg, #24393f 1px, transparent 1px)`,
            backgroundSize: "clamp(32px, 6vw, 48px) clamp(32px, 6vw, 48px)",
          }}
        />
      </div>

      {/* Decorative atmospheric orbs for depth — stronger presence */}
      <div className="pointer-events-none absolute top-20 left-[5%] h-[400px] w-[400px] rounded-full bg-[#1f5b66]/10 blur-[130px]" aria-hidden="true" />
      <div className="pointer-events-none absolute bottom-10 right-[8%] h-[320px] w-[320px] rounded-full bg-[#95b6a5]/14 blur-[100px]" aria-hidden="true" />

      <div className="container-width relative">
        {/* Heading block */}
        <div className="mb-12 max-w-2xl sm:mb-14">
          <SectionHeading
            eyebrow="NOSSA ABORDAGEM"
            title="Tecnologia assistiva simples e eficaz."
            subtitle="A CareLens AI combina hardware inteligente com software adaptativo para cuidar de quem você ama."
          />
        </div>

        {/* Floating widgets - asymmetrical layout */}
        {/* Desktop: 3-column with offset positions, hiding on mobile */}
        <div className="hidden lg:block">
          <div className="grid grid-cols-3 gap-6">
            {/* Widget 1 - offset top */}
            <div className="relative">
              <GlassCard className="absolute -top-12 left-0 w-56 p-5">
                <p className="text-[10px] uppercase tracking-[0.14em] text-[#1f5b66]/68">
                  {widgets[0].label}
                </p>
                <p className="mt-1 text-3xl font-semibold text-[#24393f]">
                  {widgets[0].metric}
                  <span className="ml-1 text-base font-normal text-[#1f5b66]/80">
                    {widgets[0].unit}
                  </span>
                </p>
                <p className="mt-0.5 text-xs text-[#1f5b66]/80">{widgets[0].detail}</p>
                <MicroUI type="line" />
              </GlassCard>
            </div>

            {/* Widget 2 - middle offset down */}
            <div className="relative pt-8">
              <GlassCard className="absolute left-0 top-20 w-52 p-5">
                <p className="text-[10px] uppercase tracking-[0.14em] text-[#1f5b66]/68">
                  {widgets[1].label}
                </p>
                <p className="mt-1 text-3xl font-semibold text-[#24393f]">
                  {widgets[1].metric}
                  <span className="ml-1 text-base font-normal text-[#1f5b66]/80">
                    {widgets[1].unit}
                  </span>
                </p>
                <p className="mt-0.5 text-xs text-[#1f5b66]/80">{widgets[1].detail}</p>
                <MicroUI type="progress" />
              </GlassCard>
            </div>

            {/* Widget 3 - offset right */}
            <div className="relative">
              <GlassCard className="absolute -top-6 right-4 w-48 p-5">
                <p className="text-[10px] uppercase tracking-[0.14em] text-[#1f5b66]/68">
                  {widgets[2].label}
                </p>
                <p className="mt-1 text-3xl font-semibold text-[#24393f]">
                  {widgets[2].metric}
                  <span className="ml-1 text-base font-normal text-[#1f5b66]/80">
                    {widgets[2].unit}
                  </span>
                </p>
                <p className="mt-0.5 text-xs text-[#1f5b66]/80">{widgets[2].detail}</p>
                <MicroUI type="dots" />
              </GlassCard>
            </div>
          </div>
        </div>

        {/* Mobile: stacked cards with full width, no overflow */}
        <div className="lg:hidden space-y-4">
          {widgets.map((widget) => (
            <GlassCard key={widget.id} className="w-full p-4 sm:p-5">
              <div className="flex items-start justify-between">
                <div>
                  <p className="text-[10px] uppercase tracking-[0.14em] text-[#1f5b66]/68">
                    {widget.label}
                  </p>
                  <p className="mt-1 text-2xl font-semibold text-[#24393f] sm:text-3xl">
                    {widget.metric}
                    <span className="ml-1 text-base font-normal text-[#1f5b66]/80">
                      {widget.unit}
                    </span>
                  </p>
                  <p className="mt-0.5 text-xs text-[#1f5b66]/80">{widget.detail}</p>
                </div>
                <div className="shrink-0">
                  {widget.microLine && <MicroUI type="line" />}
                  {widget.microProgress && <MicroUI type="progress" />}
                  {widget.microDots && <MicroUI type="dots" />}
                </div>
              </div>
            </GlassCard>
          ))}
        </div>
      </div>
    </section>
  );
}
