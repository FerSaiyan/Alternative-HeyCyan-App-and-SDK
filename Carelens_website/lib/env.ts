const requiredServerVars = [
  "STRIPE_SECRET_KEY",
  "NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY",
  "STRIPE_WEBHOOK_SECRET",
  "STRIPE_PRICE_MONTHLY_BRL_50",
  "STRIPE_PRICE_GLASSES_BRL_250",
  "NEXT_PUBLIC_APP_URL",
] as const;

export function missingServerVars(): string[] {
  return requiredServerVars.filter((key) => !process.env[key]);
}

export function assertEnvOrThrow(): void {
  const missing = missingServerVars();
  if (missing.length === 0) {
    return;
  }

  throw new Error(`Missing environment variables: ${missing.join(", ")}`);
}
