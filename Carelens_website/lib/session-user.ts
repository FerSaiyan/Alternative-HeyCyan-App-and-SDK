import { prisma } from "@/lib/prisma";
import { AuthProvider, Role, Sex } from "@prisma/client";

export type SessionUser = {
  id: string;
  email: string;
  role: Role;
};

export type SessionUserProfile = {
  id: string;
  email: string;
  role: Role;
  authProvider: AuthProvider;
  fullName: string | null;
  dateOfBirth: Date | null;
  sex: Sex | null;
  heightCm: number | null;
  weightKg: number | null;
  healthCondition: string | null;
  onboardingMedicalHistoryAnswer: string | null;
  onboardingGoalAnswer: string | null;
  onboardingShareHistoryAnswer: string | null;
  visionLevel: string | null;
  dailyRoutine: string | null;
  techComfortLevel: string | null;
  livingSituation: string | null;
  primaryConcern: string | null;
  medications: string | null;
  allergies: string | null;
  emergencyName: string | null;
  emergencyPhone: string | null;
  glassesColor: string | null;
};

export type RegisterPasswordInput = {
  email: string;
  passwordHash: string;
  fullName: string;
  dateOfBirth: Date;
};

type GoogleIdentityInput = {
  email: string;
  fullName?: string;
  googleSub?: string;
  dateOfBirth?: Date;
};

export async function ensureUserFromEmail(email: string): Promise<{ id: string; email: string }> {
  const normalized = email.trim().toLowerCase();

  const user = await prisma.user.upsert({
    where: { email: normalized },
    update: {},
    create: {
      email: normalized,
    },
    select: {
      id: true,
      email: true,
    },
  });

  return user;
}

export async function getUserAuthRecordByEmail(email: string): Promise<{
  id: string;
  email: string;
  role: Role;
  authProvider: AuthProvider;
  passwordHash: string | null;
} | null> {
  const normalized = email.trim().toLowerCase();

  return prisma.user.findUnique({
    where: { email: normalized },
    select: {
      id: true,
      email: true,
      role: true,
      authProvider: true,
      passwordHash: true,
    },
  });
}

export async function getUserAuthByIdentifier(identifier: string): Promise<{
  id: string;
  email: string;
  role: Role;
  authProvider: AuthProvider;
  passwordHash: string | null;
} | null> {
  const normalized = identifier.trim().toLowerCase();
  if (normalized.includes("@")) {
    return getUserAuthRecordByEmail(normalized);
  }
  return prisma.user.findUnique({
    where: { username: normalized },
    select: {
      id: true,
      email: true,
      role: true,
      authProvider: true,
      passwordHash: true,
    },
  });
}

export async function registerPasswordUser(input: RegisterPasswordInput): Promise<{ id: string; email: string }> {
  const normalized = input.email.trim().toLowerCase();
  const existing = await prisma.user.findUnique({
    where: { email: normalized },
    select: {
      id: true,
      email: true,
      authProvider: true,
    },
  });

  if (existing) {
    const err = new Error(
      existing.authProvider === AuthProvider.GOOGLE
        ? "Este e-mail já foi cadastrado com Google. Use Entrar com Google."
        : "Este e-mail já está cadastrado. Faça login para continuar.",
    );
    err.name = "EMAIL_ALREADY_EXISTS";
    throw err;
  }

  return prisma.user.create({
    data: {
      email: normalized,
      authProvider: AuthProvider.PASSWORD,
      passwordHash: input.passwordHash,
      fullName: input.fullName.trim(),
      dateOfBirth: input.dateOfBirth,
    },
    select: {
      id: true,
      email: true,
    },
  });
}

export async function ensureGoogleUserByEmail(input: GoogleIdentityInput): Promise<{ id: string; email: string }> {
  const normalized = input.email.trim().toLowerCase();
  const existing = await prisma.user.findUnique({
    where: { email: normalized },
    select: {
      id: true,
      email: true,
      authProvider: true,
      role: true,
    },
  });

  if (!existing) {
    return prisma.user.create({
      data: {
        email: normalized,
        authProvider: AuthProvider.GOOGLE,
        googleSub: input.googleSub || null,
        fullName: input.fullName?.trim() || null,
        dateOfBirth: input.dateOfBirth ?? null,
      },
      select: {
        id: true,
        email: true,
      },
    });
  }

  if (existing.authProvider === AuthProvider.PASSWORD) {
    const err = new Error("Este e-mail já foi cadastrado com senha. Use e-mail e senha para entrar.");
    err.name = "EMAIL_REGISTERED_WITH_PASSWORD";
    throw err;
  }

  const user = await prisma.user.update({
    where: { id: existing.id },
    data: {
      authProvider: AuthProvider.GOOGLE,
      googleSub: input.googleSub || undefined,
      fullName: input.fullName?.trim() || undefined,
      dateOfBirth: input.dateOfBirth ?? undefined,
    },
    select: {
      id: true,
      email: true,
    },
  });

  return user;
}

export function parseUserIdCookie(value: string | undefined): string | null {
  if (!value) {
    return null;
  }
  const userId = value.trim();
  return userId.length > 0 ? userId : null;
}

export function extractCookieValue(cookieHeader: string, key: string): string | undefined {
  return cookieHeader
    .split(";")
    .map((item) => item.trim())
    .find((item) => item.startsWith(`${key}=`))
    ?.split("=")[1];
}

export async function getSessionUserById(userId: string): Promise<SessionUser | null> {
  const user = await prisma.user.findUnique({
    where: { id: userId },
    select: {
      id: true,
      email: true,
      role: true,
    },
  });

  return user;
}

export async function getSessionUserProfileById(userId: string): Promise<SessionUserProfile | null> {
  const user = await prisma.user.findUnique({
    where: { id: userId },
    select: {
      id: true,
      email: true,
      role: true,
      authProvider: true,
      fullName: true,
      dateOfBirth: true,
      sex: true,
      heightCm: true,
      weightKg: true,
      healthCondition: true,
      onboardingMedicalHistoryAnswer: true,
      onboardingGoalAnswer: true,
      onboardingShareHistoryAnswer: true,
      visionLevel: true,
      dailyRoutine: true,
      techComfortLevel: true,
      livingSituation: true,
      primaryConcern: true,
      medications: true,
      allergies: true,
      emergencyName: true,
      emergencyPhone: true,
      glassesColor: true,
    },
  });

  return user;
}
