function firstHeaderValue(value: string | null): string {
  if (!value) {
    return "";
  }
  return value.split(",")[0]?.trim() ?? "";
}

function normalizeOrigin(value: string): string | null {
  try {
    const parsed = new URL(value);
    if (parsed.protocol === "http:" || parsed.protocol === "https:") {
      return parsed.origin;
    }
    return null;
  } catch {
    return null;
  }
}

export function getRequestOrigin(request: Request): string {
  const forwardedHost = firstHeaderValue(request.headers.get("x-forwarded-host"));
  const host = forwardedHost || firstHeaderValue(request.headers.get("host"));
  const forwardedProto = firstHeaderValue(request.headers.get("x-forwarded-proto"));

  if (host) {
    const protocol =
      forwardedProto ||
      (host.startsWith("localhost") || host.startsWith("127.0.0.1") ? "http" : "https");
    return `${protocol}://${host}`;
  }

  const originHeader = normalizeOrigin(firstHeaderValue(request.headers.get("origin")));
  if (originHeader) {
    return originHeader;
  }

  const refererHeader = normalizeOrigin(firstHeaderValue(request.headers.get("referer")));
  if (refererHeader) {
    return refererHeader;
  }

  return new URL(request.url).origin;
}
