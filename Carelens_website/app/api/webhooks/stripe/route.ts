import { NextResponse } from "next/server";
import { SubscriptionStatus } from "@prisma/client";
import { Prisma } from "@prisma/client";
import { prisma } from "@/lib/prisma";
import { getStripeClient } from "@/lib/stripe";
import { logWebhookReceived } from "@/lib/logger";

function isLiveStripeMode(): boolean {
  return (process.env.STRIPE_MODE ?? "live").toLowerCase() === "live";
}

async function resolveUserIdFromCheckoutSession(session: {
  client_reference_id: string | null;
  metadata: Record<string, string> | null;
  customer_email: string | null;
}): Promise<string | null> {
  const fromMetadata = session.metadata?.userId?.trim();
  if (fromMetadata) {
    return fromMetadata;
  }

  const fromClientReference = session.client_reference_id?.trim();
  if (fromClientReference) {
    return fromClientReference;
  }

  const email = session.customer_email?.trim().toLowerCase();
  if (!email) {
    return null;
  }

  const user = await prisma.user.findUnique({
    where: { email },
    select: { id: true },
  });

  return user?.id ?? null;
}

async function resolveUserIdFromCustomer(customerId: string | null): Promise<string | null> {
  if (!customerId) {
    return null;
  }

  const user = await prisma.user.findFirst({
    where: { stripeCustomerId: customerId },
    select: { id: true },
  });

  return user?.id ?? null;
}

export async function POST(request: Request) {
  if (!isLiveStripeMode()) {
    return NextResponse.json({
      ok: true,
      message: "Webhook Stripe ignorado em modo draft.",
    });
  }

  const signature = request.headers.get("stripe-signature");

  if (!signature) {
    return NextResponse.json(
      { ok: false, message: "Cabeçalho stripe-signature ausente." },
      { status: 400 },
    );
  }

  const webhookSecret = process.env.STRIPE_WEBHOOK_SECRET;
  if (!webhookSecret) {
    return NextResponse.json(
      {
        ok: false,
        message: "STRIPE_WEBHOOK_SECRET não configurado para validação do webhook.",
      },
      { status: 500 },
    );
  }

  const stripe = getStripeClient();
  if (!stripe) {
    return NextResponse.json(
      {
        ok: false,
        message: "Falha ao inicializar cliente Stripe em modo live.",
      },
      { status: 500 },
    );
  }

  const payload = await request.text();
  let event;

  try {
    event = stripe.webhooks.constructEvent(payload, signature, webhookSecret);
  } catch {
    return NextResponse.json({ ok: false, message: "Assinatura de webhook inválida." }, { status: 400 });
  }

  try {
    await prisma.stripeWebhookEvent.create({
      data: {
        eventId: event.id,
        eventType: event.type,
      },
    });
  } catch (error) {
    if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === "P2002") {
      return NextResponse.json({
        ok: true,
        message: "Evento Stripe duplicado ignorado por idempotência.",
      });
    }
    throw error;
  }

  logWebhookReceived({ eventType: event.type, eventId: event.id });

  if (event.type === "checkout.session.completed") {
    const session = event.data.object;
    const userId = await resolveUserIdFromCheckoutSession({
      client_reference_id: session.client_reference_id,
      metadata: session.metadata,
      customer_email: session.customer_email,
    });

    if (!userId) {
      return NextResponse.json(
        { ok: false, message: "Não foi possível identificar o usuário desta sessão de checkout." },
        { status: 400 },
      );
    }

    const stripeSubscriptionId = typeof session.subscription === "string" ? session.subscription : null;
    const stripeCustomerId = typeof session.customer === "string" ? session.customer : null;

    if (stripeSubscriptionId) {
      await prisma.subscription.upsert({
        where: { stripeSubscriptionId },
        update: {
          userId,
          status: SubscriptionStatus.ACTIVE,
          stripeCustomerId,
        },
        create: {
          userId,
          status: SubscriptionStatus.ACTIVE,
          stripeCustomerId,
          stripeSubscriptionId,
        },
      });
    } else {
      await prisma.subscription.create({
        data: {
          userId,
          status: SubscriptionStatus.ACTIVE,
          stripeCustomerId,
        },
      });
    }
  }

  if (event.type === "payment_intent.succeeded") {
    const paymentIntent = event.data.object;
    const metadataUserId = paymentIntent.metadata?.userId?.trim() || null;
    const customerId = typeof paymentIntent.customer === "string" ? paymentIntent.customer : null;
    const userId = metadataUserId ?? (await resolveUserIdFromCustomer(customerId));

    if (userId) {
      await prisma.subscription.create({
        data: {
          userId,
          status: SubscriptionStatus.ACTIVE,
          stripeCustomerId: customerId,
        },
      });

      try {
        await prisma.user.update({
          where: { id: userId },
          data: {
            stripeCustomerId: customerId ?? undefined,
            stripeDefaultPaymentMethodId:
              typeof paymentIntent.payment_method === "string" ? paymentIntent.payment_method : undefined,
          },
        });
      } catch {
        // Keeps webhook resilient if runtime Prisma client is stale.
      }
    }
  }

  if (event.type === "invoice.paid") {
    const invoice = event.data.object;
    const invoiceMetadataUserId = invoice.metadata?.userId?.trim() || null;
    const customerId = typeof invoice.customer === "string" ? invoice.customer : null;
    const userId = invoiceMetadataUserId ?? (await resolveUserIdFromCustomer(customerId));
    const sourceSubscription = invoice.parent?.subscription_details?.subscription;
    const stripeSubscriptionId = typeof sourceSubscription === "string" ? sourceSubscription : null;

    if (userId && stripeSubscriptionId) {
      await prisma.subscription.upsert({
        where: { stripeSubscriptionId },
        update: {
          userId,
          status: SubscriptionStatus.ACTIVE,
          stripeCustomerId: customerId,
        },
        create: {
          userId,
          status: SubscriptionStatus.ACTIVE,
          stripeCustomerId: customerId,
          stripeSubscriptionId,
        },
      });
    }
  }

  if (event.type === "customer.subscription.deleted") {
    const sub = event.data.object;
    await prisma.subscription.updateMany({
      where: { stripeSubscriptionId: sub.id },
      data: { status: SubscriptionStatus.CANCELED },
    });
  }

  if (event.type === "invoice.payment_failed") {
    const invoice = event.data.object;
    const sourceSubscription = invoice.parent?.subscription_details?.subscription;
    const stripeSubscriptionId = typeof sourceSubscription === "string" ? sourceSubscription : null;
    if (stripeSubscriptionId) {
      await prisma.subscription.updateMany({
        where: { stripeSubscriptionId },
        data: { status: SubscriptionStatus.REFUND_PENDING },
      });
    }
  }

  return NextResponse.json({
    ok: true,
    message: "Webhook Stripe processado com sucesso.",
  });
}
