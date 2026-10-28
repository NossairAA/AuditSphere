import "server-only";
import { randomUUID } from "node:crypto";
import { SignJWT, importPKCS8 } from "jose";

// One-click entry of the demo profile (ADR-0035). Keycloak links only these accounts to the demo-entry provider.
export const demoAccounts = {
  auditor: { label: "Auditeur", detail: "Réalise les contrôles d’une mission" },
  validator: {
    label: "Validateur",
    detail: "Approuve ou rejette les missions soumises",
  },
  auditee: { label: "Audité", detail: "Suit ses actions correctives" },
} as const;
export type DemoAccount = keyof typeof demoAccounts;
// The banner follows the profile. The buttons also need the signing key created by scripts/k3s-setup-keycloak.py.
export const demoProfile = () => process.env.DEMO_ENTRY === "true";
export const demoEnabled = () =>
  demoProfile() && !!process.env.DEMO_ASSERTION_KEY;
export const isDemoAccount = (value: unknown): value is DemoAccount =>
  typeof value === "string" && Object.hasOwn(demoAccounts, value);
export const jwtBearer = "urn:ietf:params:oauth:grant-type:jwt-bearer";
let key: ReturnType<typeof importPKCS8> | undefined;
// Single-use assertion for the JWT authorization grant: no password, no Keycloak session in the browser.
export async function demoAssertion(
  account: DemoAccount,
  origin: string,
  issuer: string,
) {
  if (!demoEnabled()) throw new Error("Demo entry disabled");
  key ??= importPKCS8(process.env.DEMO_ASSERTION_KEY!, "ES256");
  return new SignJWT({})
    .setProtectedHeader({ alg: "ES256", typ: "JWT" })
    .setIssuer(origin + "/auth/demo")
    .setSubject(account)
    .setAudience(issuer)
    .setIssuedAt()
    .setExpirationTime("60s")
    .setJti(randomUUID())
    .sign(await key);
}
