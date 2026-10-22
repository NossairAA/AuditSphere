import { test, expect, type Page } from "@playwright/test";
import { readFileSync, writeFileSync, existsSync, mkdirSync } from "node:fs";
import { execFileSync } from "node:child_process";
const env = Object.fromEntries(
  readFileSync("../../.env", "utf8")
    .split("\n")
    .filter((l) => l.includes("="))
    .map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]),
);
async function login(
  page: Page,
  name: string,
  password = env[name.toUpperCase() + "_PASSWORD"],
) {
  await page.goto("/");
  await page.getByRole("link", { name: "Se connecter", exact: true }).click();
  await page.locator("#username").fill(name);
  await page.locator("#password").fill(password);
  await page.locator("#kc-login").click();
}
test("administration des comptes, premier accès et révocation", async ({
  page,
  browser,
}) => {
  test.setTimeout(90000);
  await login(page, "superadmin");
  await expect(page).toHaveURL(/\/missions$/);
  await page.getByRole("link", { name: "Utilisateurs", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Utilisateurs", exact: true }),
  ).toBeVisible();
  const username = "ux-test-" + Date.now();
  page.on("dialog", (d) => d.accept());
  await page.getByRole("button", { name: "Créer un utilisateur" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Identifiant", { exact: true }).fill(username);
  await dialog.getByLabel("Prénom", { exact: true }).fill("Camille");
  await dialog.getByLabel("Nom", { exact: true }).fill("Test");
  await dialog
    .getByLabel("Email", { exact: true })
    .fill(username + "@example.invalid");
  await dialog.getByRole("checkbox", { name: "Auditeur", exact: true }).check();
  await dialog
    .getByLabel("Motif")
    .fill("Scénario synthétique de gestion des comptes");
  const created = page.waitForResponse(
    (r) =>
      r.url().endsWith("/api/admin/users") && r.request().method() === "POST",
  );
  await dialog
    .getByRole("button", { name: "Enregistrer", exact: true })
    .click();
  const response = await created;
  const body = await response.json();
  mkdirSync("../../.local", { recursive: true });
  const manifest = "../../.local/test-users.json";
  const entries = existsSync(manifest)
    ? JSON.parse(readFileSync(manifest, "utf8"))
    : [];
  entries.push({ id: body.id, username });
  writeFileSync(manifest, JSON.stringify(entries), { mode: 0o600 });
  expect(response.status()).toBe(201);
  const context = await browser.newContext();
  try {
    await expect(
      dialog.getByRole("heading", { name: "Mot de passe temporaire" }),
    ).toBeVisible();
    await dialog
      .getByRole("button", { name: "J’ai conservé le mot de passe" })
      .click();
    await expect(page.locator("code")).toHaveCount(0);
    const newcomer = await context.newPage();
    await login(newcomer, username, body.temporaryPassword);
    await expect(newcomer.locator("#password-new")).toBeVisible();
    const replacement = body.temporaryPassword + "x";
    await newcomer.locator("#password-new").fill(replacement);
    await newcomer.locator("#password-confirm").fill(replacement);
    await newcomer
      .locator("input[type=submit],button[type=submit]")
      .first()
      .click();
    await expect(newcomer).toHaveURL(/\/missions$/);
    expect((await newcomer.request.get("/api/admin/users")).status()).toBe(403);
    const firstSession = await (await newcomer.request.get("/api/session")).json();
    expect(firstSession.roles).toContain("AUDITOR");
    const adminSession = await (await page.request.get("/api/session")).json();
    const changedRoles = await page.request.put(`/api/admin/users/${body.id}`, {
      headers: {Origin:"http://localhost:3000", "x-csrf-token":adminSession.csrf},
      data: {username,firstName:"Camille",lastName:"Test",email:username+"@example.invalid",enabled:true,roles:["AUDIT_VALIDATOR"],reason:"Changement de rôle synthétique"},
    });
    expect(changedRoles.status()).toBe(200);
    expect((await newcomer.request.get("/api/session")).status()).toBe(401);
    // JWT iat has second precision. Cross the revocation second before a fresh login.
    const revokedAt = Math.floor(Date.now()/1000);
    await expect.poll(() => Math.floor(Date.now()/1000)).toBeGreaterThan(revokedAt);
    await login(newcomer,username,replacement);
    await expect(newcomer).toHaveURL(/\/missions$/);
    const changedSession = await (await newcomer.request.get("/api/session")).json();
    expect(changedSession.roles).toContain("AUDIT_VALIDATOR");
    expect(changedSession.roles).not.toContain("AUDITOR");

    await page.getByLabel("Rechercher", { exact: true }).fill(username);
    await page.getByRole("button", { name: "Rechercher", exact: true }).click();
    const row = page.getByRole("row").filter({ hasText: username });
    await row.getByRole("button", { name: "Gérer" }).click();
    await dialog.getByRole("checkbox", { name: "Compte actif" }).uncheck();
    await dialog.getByLabel("Motif").fill("Désactivation de test");
    await dialog
      .getByRole("button", { name: "Enregistrer", exact: true })
      .click();
    await expect(dialog).toHaveCount(0);
    expect((await newcomer.request.get("/api/session")).status()).toBe(401);
    const session = await (await page.request.get("/api/session")).json();
    const headers = {
      Origin: "http://localhost:3000",
      "x-csrf-token": session.csrf,
    };
    const reset = await page.request.post(
      `/api/admin/users/${body.id}/reset-password`,
      { headers, data: { reason: "Réinitialisation de test" } },
    );
    expect(reset.status()).toBe(200);
    const history = await (
      await page.request.get(`/api/admin/users/${body.id}/history`)
    ).json();
    expect(
      history.events.some(
        (e: { action: string; outcome: string }) =>
          e.action === "RESET_PASSWORD" && e.outcome === "SUCCESS",
      ),
    ).toBe(true);
    await page.setViewportSize({ width: 390, height: 844 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
  } finally {
    await context.close();
    execFileSync("python3", ["scripts/cleanup-test-users.py"], {
      cwd: "../..",
      stdio: "pipe",
    });
  }
});
