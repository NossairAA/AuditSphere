import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
const env = Object.fromEntries(
  readFileSync("../../.env", "utf8")
    .split("\n")
    .filter((l) => l.includes("="))
    .map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]),
);
async function login(page: Page, name: string) {
  await page.goto("/");
  await page.getByRole("link", { name: "Se connecter", exact: true }).click();
  await page.locator("#username").fill(name);
  await page.locator("#password").fill(env[name.toUpperCase() + "_PASSWORD"]);
  await page.locator("#kc-login").click();
  await expect(page).toHaveURL(/\/missions$/);
  await expect(
    page.getByRole("heading", { name: "Répertoire des missions" }),
  ).toBeVisible();
}
test("dates, conflit de version, démarrage et clôture réservés au propriétaire", async ({
  page,
  browser,
}) => {
  await login(page, "manager");
  const directory = await (await page.request.get("/api/auditors")).json();
  const auditor = directory.find(
    (p: { subject: string }) =>
      p.subject === "00000000-0000-0000-0000-000000000002",
  );
  await page.goto("/missions/new");
  const title = "Cycle de vie " + Date.now();
  await page.getByLabel("Titre de la mission").fill(title);
  await page.getByLabel("Périmètre", { exact: true }).fill("Achats");
  await page
    .getByLabel("Objectifs et description")
    .fill("Validation synthétique du cycle de vie.");
  await page.getByLabel("Date de début prévue").fill("2026-11-01");
  await page.getByLabel("Date de fin prévue").fill("2026-11-10");
  await page.getByRole("checkbox", { name: auditor.name, exact: true }).check();
  await page
    .getByRole("button", { name: "Créer la mission", exact: true })
    .click();
  await expect(page.getByRole("heading", { name: title })).toBeVisible();
  const id = new URL(page.url()).pathname.split("/").pop()!;
  const section = page.getByRole("region", { name: "Planification et statut" });
  await expect(section.getByLabel("Date de début prévue")).toHaveValue(
    "2026-11-01",
  );
  const session = await (await page.request.get("/api/session")).json();
  expect(
    (
      await page.request.put(`/api/missions/${id}/status`, {
        headers: { Origin: "http://localhost:3000" },
        data: { version: 0, status: "IN_PROGRESS" },
      })
    ).status(),
  ).toBe(403);
  const changed = await page.request.put(`/api/missions/${id}/schedule`, {
    headers: { Origin: "http://localhost:3000", "x-csrf-token": session.csrf },
    data: {
      version: 0,
      plannedStartDate: "2026-11-02",
      plannedEndDate: "2026-11-12",
    },
  });
  expect(changed.status()).toBe(200);
  await section.getByRole("button", { name: "Démarrer la mission" }).click();
  await expect(section.getByRole("alert")).toContainText("version");
  await section.getByRole("button", { name: "Recharger la fiche" }).click();
  await expect(section.getByLabel("Date de début prévue")).toHaveValue(
    "2026-11-02",
  );
  await section.getByRole("button", { name: "Démarrer la mission" }).click();
  await expect(page.locator(".page-heading .status")).toHaveText("En cours");
  const observer = await browser.newContext();
  try {
    const other = await observer.newPage();
    await login(other, "auditor");
    await other.goto(`/missions/${id}`);
    await expect(other.locator(".page-heading .status")).toHaveText("En cours");
    await expect(
      other.getByRole("button", { name: "Clôturer la mission" }),
    ).toHaveCount(0);
    const auditorSession = await (
      await observer.request.get("/api/session")
    ).json();
    expect(
      (
        await observer.request.put(`/api/missions/${id}/status`, {
          headers: {
            Origin: "http://localhost:3000",
            "x-csrf-token": auditorSession.csrf,
          },
          data: { version: 2, status: "CLOSED" },
        })
      ).status(),
    ).toBe(403);
    await section
      .getByRole("button", { name: "Clôturer la mission", exact: true })
      .click();
    await section.getByRole("button", { name: "Confirmer la clôture" }).click();
    await expect(page.locator(".page-heading .status")).toHaveText("Clôturée");
    await expect(
      section.getByRole("button", { name: "Enregistrer les dates" }),
    ).toHaveCount(0);
    await other.reload();
    await expect(other.locator(".page-heading .status")).toHaveText("Clôturée");
    await page.goto("/missions");
    await expect(
      page.getByRole("row").filter({ hasText: title }),
    ).toContainText("Clôturée");
  } finally {
    await observer.close();
  }
});
