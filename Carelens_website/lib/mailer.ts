import nodemailer from "nodemailer";

type SendMagicLinkEmailPayload = {
  to: string;
  verifyUrl: string;
};

export type SendMagicLinkEmailResult = {
  delivered: boolean;
  previewUrl?: string;
};

function getMagicLinkMode(): "draft" | "live" {
  return (process.env.MAGIC_LINK_MODE ?? "draft").toLowerCase() === "live" ? "live" : "draft";
}

function canUseSmtp(): boolean {
  const host = process.env.SMTP_HOST?.trim();
  const user = process.env.SMTP_USER?.trim();
  const pass = process.env.SMTP_PASS?.trim();

  const hasPlaceholders =
    !host ||
    !user ||
    !pass ||
    host.includes("example.com") ||
    user === "replace_me" ||
    pass === "replace_me";

  return !hasPlaceholders;
}

function smtpPort(): number {
  const parsed = Number(process.env.SMTP_PORT ?? "587");
  return Number.isFinite(parsed) ? parsed : 587;
}

export async function sendMagicLinkEmail(
  payload: SendMagicLinkEmailPayload,
): Promise<SendMagicLinkEmailResult> {
  if (getMagicLinkMode() !== "live") {
    return {
      delivered: false,
      previewUrl: payload.verifyUrl,
    };
  }

  if (!canUseSmtp()) {
    throw new Error("Configuração SMTP ausente para envio em modo live.");
  }

  const port = smtpPort();
  const transporter = nodemailer.createTransport({
    host: process.env.SMTP_HOST,
    port,
    secure: port === 465,
    auth: {
      user: process.env.SMTP_USER,
      pass: process.env.SMTP_PASS,
    },
  });

  const from = process.env.SMTP_FROM?.trim() || "CareLens <no-reply@carelens.com.br>";
  try {
    await transporter.sendMail({
      from,
      to: payload.to,
      subject: "Seu link de acesso CareLens",
      text: `Acesse sua conta CareLens com este link: ${payload.verifyUrl}\n\nEste link expira em 20 minutos.`,
      html: `<p>Acesse sua conta CareLens com este link:</p><p><a href="${payload.verifyUrl}">${payload.verifyUrl}</a></p><p>Este link expira em 20 minutos.</p>`,
    });
  } catch {
    throw new Error("Falha ao enviar e-mail de acesso em modo live.");
  }

  return { delivered: true };
}
