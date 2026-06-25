import { randomBytes, scrypt as scryptCb, timingSafeEqual } from "node:crypto";
import { promisify } from "node:util";

const scrypt = promisify(scryptCb);

const MIN_PASSWORD_LENGTH = 8;

export function isValidPasswordShape(password: string): boolean {
  return password.trim().length >= MIN_PASSWORD_LENGTH;
}

export async function hashPassword(password: string): Promise<string> {
  const normalized = password.trim();
  const salt = randomBytes(16).toString("hex");
  const derived = (await scrypt(normalized, salt, 64)) as Buffer;
  return `scrypt$${salt}$${derived.toString("hex")}`;
}

export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const [method, salt, expectedHex] = stored.split("$");
  if (method !== "scrypt" || !salt || !expectedHex) {
    return false;
  }

  const candidate = (await scrypt(password.trim(), salt, 64)) as Buffer;
  const expected = Buffer.from(expectedHex, "hex");
  if (candidate.length !== expected.length) {
    return false;
  }

  return timingSafeEqual(candidate, expected);
}
