import { createCipheriv, createDecipheriv, randomBytes, hkdfSync } from "node:crypto";

export type StoredSession<T> = Omit<T, "accessToken" | "refreshToken"> & {
  tokens: { version: 1; iv: string; ciphertext: string; tag: string };
};

function key(): Buffer {
  const configured = process.env.SESSION_ENCRYPTION_KEY;
  if (configured) {
    const decoded = Buffer.from(configured, "base64");
    if (decoded.length !== 32 || decoded.toString("base64") !== configured)
      throw new Error("SESSION_ENCRYPTION_KEY must be a base64-encoded 32-byte key");
    return decoded;
  }
  // Existing localhost installations can run until setup-web provisions a dedicated key.
  if (
    process.env.ALLOW_INSECURE_LOCAL === "true" &&
    process.env.APP_ORIGIN === "http://localhost:3000" &&
    process.env.OIDC_ISSUER?.startsWith("http://localhost:") &&
    process.env.OIDC_CLIENT_SECRET
  )
    return Buffer.from(hkdfSync("sha256", process.env.OIDC_CLIENT_SECRET, "AuditSphere local BFF", "web-sessions-v1", 32));
  throw new Error("SESSION_ENCRYPTION_KEY is required");
}

export function encryptSession<T extends { accessToken: string; refreshToken: string }>(data: T, idHash: string): StoredSession<T> {
  const { accessToken, refreshToken, ...safe } = data;
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key(), iv);
  cipher.setAAD(Buffer.from(idHash));
  const ciphertext = Buffer.concat([cipher.update(JSON.stringify({ accessToken, refreshToken }), "utf8"), cipher.final()]);
  return { ...safe, tokens: { version: 1, iv: iv.toString("base64"), ciphertext: ciphertext.toString("base64"), tag: cipher.getAuthTag().toString("base64") } } as StoredSession<T>;
}

export function decryptSession<T extends { accessToken: string; refreshToken: string }>(stored: StoredSession<T>, idHash: string): T {
  if (!stored?.tokens || stored.tokens.version !== 1) throw new Error("Unsupported session record");
  const { tokens, ...safe } = stored;
  const iv = Buffer.from(tokens.iv, "base64");
  const tag = Buffer.from(tokens.tag, "base64");
  if (iv.length !== 12 || tag.length !== 16) throw new Error("Invalid session envelope");
  const decipher = createDecipheriv("aes-256-gcm", key(), iv);
  decipher.setAAD(Buffer.from(idHash));
  decipher.setAuthTag(tag);
  const value = JSON.parse(Buffer.concat([decipher.update(Buffer.from(tokens.ciphertext, "base64")), decipher.final()]).toString("utf8"));
  if (typeof value.accessToken !== "string" || typeof value.refreshToken !== "string") throw new Error("Invalid session tokens");
  return { ...safe, accessToken: value.accessToken, refreshToken: value.refreshToken } as unknown as T;
}
