const FALLBACK_USD_BRL = 5.0;
const CACHE_TTL_MS = 10 * 60 * 1000;

let cachedRate: number | null = null;
let cachedAt = 0;

export async function getUsdToBrlRate(): Promise<number> {
  const now = Date.now();
  if (cachedRate && now - cachedAt < CACHE_TTL_MS) {
    return cachedRate;
  }

  try {
    const res = await fetch(
      "https://economia.awesomeapi.com.br/json/last/USD-BRL",
      { next: { revalidate: 600 } },
    );
    if (res.ok) {
      const data = (await res.json()) as { USDBRL?: { bid?: string } };
      const bid = parseFloat(data?.USDBRL?.bid ?? "");
      if (Number.isFinite(bid) && bid > 0) {
        cachedRate = bid;
        cachedAt = now;
        return bid;
      }
    }
  } catch {
    // fallback below
  }

  try {
    const res = await fetch(
      "https://open.er-api.com/v6/latest/USD",
      { next: { revalidate: 600 } },
    );
    if (res.ok) {
      const data = (await res.json()) as { rates?: { BRL?: number } };
      const rate = data?.rates?.BRL;
      if (rate && rate > 0) {
        cachedRate = rate;
        cachedAt = now;
        return rate;
      }
    }
  } catch {
    // fallback below
  }

  return FALLBACK_USD_BRL;
}

export async function usdToBrl(usdAmount: number): Promise<number> {
  const rate = await getUsdToBrlRate();
  return Math.round(usdAmount * rate * 100) / 100;
}
