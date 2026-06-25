type SectionHeadingProps = {
  eyebrow?: string;
  title: string;
  subtitle?: string;
  className?: string;
};

export function SectionHeading({ eyebrow, title, subtitle, className = "" }: SectionHeadingProps) {
  return (
    <header className={`max-w-2xl space-y-3 ${className}`.trim()}>
      {eyebrow && <p className="pill-eyebrow">{eyebrow}</p>}
      <h2 className="text-2xl font-semibold tracking-tight text-[#24393f] sm:text-3xl">{title}</h2>
      {subtitle && (
        <p className="text-base leading-relaxed text-muted">{subtitle}</p>
      )}
    </header>
  );
}
