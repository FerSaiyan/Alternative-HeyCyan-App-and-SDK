import { NextResponse } from "next/server";
import { getRelayUserByToken, saveRelayUser } from "@/lib/relay-kv";
import { isAsaasConfigured, getSubscription } from "@/lib/asaas";
import { logInfo, logError } from "@/lib/logger";

function isCancelableFreeTrial(user: { plan: string; subscriptionStatus: string; expiresAtMs: number }): boolean {
  return user.plan === "free_trial" && user.subscriptionStatus === "active" && Number(user.expiresAtMs) > Date.now();
}

function cancelPageHtml(
  status: "form" | "success" | "error" | "not_found",
  message: string,
  apiToken: string,
): string {
  const bgColors = { form: "#1a1a2e", success: "#0a2e1a", error: "#2e0a0a", not_found: "#1a1a2e" };
  const titles = { form: "Cancel Subscription", success: "Subscription Cancelled", error: "Error", not_found: "Not Found" };
  const bg = bgColors[status];
  const title = titles[status];

  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>CyanBridge - ${title}</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#0a0a0a;color:#e0e0e0;display:flex;justify-content:center;align-items:center;min-height:100vh;padding:20px}
.card{background:${bg};border-radius:16px;padding:32px;max-width:400px;width:100%;text-align:center;box-shadow:0 8px 32px rgba(0,0,0,0.4)}
h1{font-size:24px;margin-bottom:16px;color:#fff}
p{font-size:14px;color:#aaa;margin-bottom:24px;line-height:1.5}
.btn{display:block;width:100%;padding:16px;border:none;border-radius:12px;font-size:16px;font-weight:600;cursor:pointer;text-decoration:none;margin-bottom:12px;transition:opacity 0.2s}
.btn-danger{background:#e53935;color:#fff}
.btn-secondary{background:transparent;color:#888;border:1px solid #333}
</style>
</head>
<body>
<div class="card">
<h1>${title}</h1>
<p>${message}</p>
${status === "form" ? `
<form method="POST" action="/web-subscribe/cancel">
<input type="hidden" name="api_token" value="${apiToken}">
<button class="btn btn-danger" type="submit">Yes, Cancel My Subscription</button>
<a class="btn btn-secondary" href="javascript:history.back()">Go Back</a>
</form>
` : `
<a class="btn btn-secondary" href="javascript:history.back()">Go Back</a>
`}
</div>
</body>
</html>`;
}

export async function GET(request: Request) {
  const { searchParams } = new URL(request.url);
  const apiToken = searchParams.get("api_token") ?? "";

  if (!apiToken) {
    return new NextResponse(
      cancelPageHtml("not_found", "No API token provided. Please open this link from the CyanBridge app.", ""),
      { status: 200, headers: { "Content-Type": "text/html; charset=utf-8" } },
    );
  }

  const user = await getRelayUserByToken(apiToken);
  if (!user) {
    return new NextResponse(
      cancelPageHtml("not_found", "User not found. Please try again from the app.", apiToken),
      { status: 200, headers: { "Content-Type": "text/html; charset=utf-8" } },
    );
  }

  if (!user.asaasSubscriptionId) {
    if (isCancelableFreeTrial(user)) {
      return new NextResponse(
        cancelPageHtml("form", "Are you sure you want to end your free trial now?", apiToken),
        { status: 200, headers: { "Content-Type": "text/html; charset=utf-8" } },
      );
    }
    return new NextResponse(
      cancelPageHtml("not_found", "No active subscription found for this account.", apiToken),
      { status: 200, headers: { "Content-Type": "text/html; charset=utf-8" } },
    );
  }

  // Check subscription status
  if (isAsaasConfigured()) {
    try {
      const sub = await getSubscription(user.asaasSubscriptionId);
      if (sub.status === "CANCELED" || sub.status === "EXPIRED") {
        return new NextResponse(
          cancelPageHtml("success", "Your subscription has already been cancelled. You will have access until the end of your current billing period.", apiToken),
          { status: 200, headers: { "Content-Type": "text/html; charset=utf-8" } },
        );
      }
    } catch {
      // continue to show cancel form
    }
  }

  return new NextResponse(
    cancelPageHtml(
      "form",
      `Are you sure you want to cancel your ${user.plan} subscription? You will keep access until the end of your current billing period.`,
      apiToken,
    ),
    { status: 200, headers: { "Content-Type": "text/html; charset=utf-8" } },
  );
}

export async function POST(request: Request) {
  let apiToken = "";

  const contentType = request.headers.get("content-type") ?? "";
  if (contentType.includes("application/x-www-form-urlencoded")) {
    const formData = await request.formData();
    apiToken = String(formData.get("api_token") ?? "");
  } else {
    const body = (await request.json()) as Record<string, unknown>;
    apiToken = String(body.api_token ?? "");
  }

  if (!apiToken) {
    return new NextResponse(
      cancelPageHtml("error", "Missing API token.", ""),
      { status: 200, headers: { "Content-Type": "text/html; charset=utf-8" } },
    );
  }

  const user = await getRelayUserByToken(apiToken);
  if (!user) {
    return new NextResponse(
      cancelPageHtml("error", "User not found.", apiToken),
      { status: 200, headers: { "Content-Type": "text/html; charset=utf-8" } },
    );
  }

  if (!user.asaasSubscriptionId) {
    if (isCancelableFreeTrial(user)) {
      await saveRelayUser({
        ...user,
        subscriptionStatus: "inactive",
        expiresAtMs: 0,
        updatedAt: new Date().toISOString(),
      });

      return new NextResponse(
        cancelPageHtml("success", "Your free trial has been ended. You can re-subscribe anytime from the app.", apiToken),
        { status: 200, headers: { "Content-Type": "text/html; charset=utf-8" } },
      );
    }
    return new NextResponse(
      cancelPageHtml("error", "No subscription to cancel.", apiToken),
      { status: 200, headers: { "Content-Type": "text/html; charset=utf-8" } },
    );
  }

  if (!isAsaasConfigured()) {
    return new NextResponse(
      cancelPageHtml("error", "Payment provider not configured.", apiToken),
      { status: 200, headers: { "Content-Type": "text/html; charset=utf-8" } },
    );
  }

  try {
    // Delete the subscription on Asaas
    const { deleteSubscription } = await import("@/lib/asaas");
    await deleteSubscription(user.asaasSubscriptionId);

    // Update local user status
    await saveRelayUser({
      ...user,
      subscriptionStatus: "inactive",
      expiresAtMs: 0,
      updatedAt: new Date().toISOString(),
    });

    logInfo("relay_subscription_cancelled", "Subscription cancelled by user", {
      relayUserId: user.id,
      asaasSubscriptionId: user.asaasSubscriptionId,
    });

    return new NextResponse(
      cancelPageHtml("success", "Your subscription has been cancelled. You will have access until the end of your current billing period. You can re-subscribe anytime from the app.", apiToken),
      { status: 200, headers: { "Content-Type": "text/html; charset=utf-8" } },
    );
  } catch (error) {
    logError("relay_cancel_failed", "Failed to cancel subscription", {
      relayUserId: user.id,
      asaasSubscriptionId: user.asaasSubscriptionId,
      error: String(error),
    });

    return new NextResponse(
      cancelPageHtml("error", `Failed to cancel subscription: ${error instanceof Error ? error.message : "Unknown error"}. Please contact support.`, apiToken),
      { status: 200, headers: { "Content-Type": "text/html; charset=utf-8" } },
    );
  }
}
