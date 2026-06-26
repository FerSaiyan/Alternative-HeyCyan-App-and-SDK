import { NextResponse } from "next/server";
import { getHostedSubscriptionState, getSubscription, isAsaasConfigured } from "@/lib/asaas";

export async function GET(request: Request) {
  const { searchParams } = new URL(request.url);
  const subscriptionId = (searchParams.get("subscription_id") ?? "").trim();
  const returnUrl = (searchParams.get("return_url") ?? "").trim();

  if (!subscriptionId) {
    return NextResponse.json(
      { ok: false, confirmed: false, message: "Missing subscription_id" },
      { status: 400, headers: { "Cache-Control": "no-store" } },
    );
  }

  if (!isAsaasConfigured()) {
    return NextResponse.json(
      { ok: false, confirmed: false, message: "Payment provider not configured." },
      { status: 503, headers: { "Cache-Control": "no-store" } },
    );
  }

  try {
    const subscription = await getSubscription(subscriptionId);
    const paymentSummary = await getHostedSubscriptionState(subscription.id);
    const confirmed = paymentSummary.state === "active";

    if (confirmed && returnUrl) {
      const url = new URL(returnUrl);
      url.searchParams.set("donation_status", "confirmed");
      url.searchParams.set("subscription_id", subscriptionId);
      return NextResponse.redirect(url.toString(), 303);
    }

    return NextResponse.json(
      {
        ok: true,
        confirmed,
        state: paymentSummary.state,
        payment_status: paymentSummary.paymentStatus,
        asaas_status: subscription.status,
        message: confirmed
          ? "Donation confirmed. Thank you!"
          : paymentSummary.state === "pending"
            ? "Waiting for Asaas to confirm the payment."
            : "The donation payment is not active.",
      },
      { headers: { "Cache-Control": "no-store" } },
    );
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unknown verification error";
    return NextResponse.json(
      { ok: false, confirmed: false, message },
      {
        status: 502,
        headers: { "Cache-Control": "no-store" },
      },
    );
  }
}
