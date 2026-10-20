import { recordMission } from "./test-data";
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
  recordMission(id);
  const section = page.getByRole("region", { name: "Planification et statut" });
  await section
    .getByRole("button", { name: "Modifier les dates", exact: true })
    .click();
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
  await section
    .getByRole("button", { name: "Modifier les dates", exact: true })
    .click();
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
    expect(
      (
        await page.request.put(`/api/missions/${id}/status`, {
          headers: {
            Origin: "http://localhost:3000",
            "x-csrf-token": session.csrf,
          },
          data: { version: 2, status: "CLOSED" },
        })
      ).status(),
    ).toBe(409);
    await expect(
      section.getByRole("button", { name: "Clôturer la mission", exact: true }),
    ).toHaveCount(0);
    const designation = page.getByRole("region", {
      name: "Validateur de la mission",
    });
    await designation
      .getByLabel("Choisir un validateur")
      .selectOption({ label: "Thomas Laurent" });
    await designation
      .getByRole("button", { name: "Enregistrer le validateur", exact: true })
      .click();
    await expect(
      designation.getByText("Validateur désigné : Thomas Laurent"),
    ).toBeVisible();
    await section
      .getByRole("button", { name: "Soumettre à validation" })
      .click();
    await expect(page.locator(".page-heading .status")).toHaveText(
      "En attente de validation",
    );
    const reviewContext = await browser.newContext();
    try {
      const reviewer = await reviewContext.newPage();
      await login(reviewer, "validator");
      await expect
        .poll(
          async () => {
            const r = await reviewer.request.get("/api/notifications");
            const data = await r.json();
            return data.items?.some(
              (n: { missionId: string; eventType: string }) =>
                n.missionId === id &&
                n.eventType === "audit.mission.review-requested.v1",
            );
          },
          { timeout: 20000 },
        )
        .toBe(true);
      await reviewer.goto("/missions/notifications");
      const notification = reviewer
        .getByRole("row")
        .filter({ has: reviewer.locator(`a[href="/missions/${id}"]`) });
      await expect(notification).toContainText(
        "Une mission attend votre validation",
      );
      await notification.getByRole("link").click();
      const reviewSection = reviewer.getByRole("region", {
        name: "Planification et statut",
      });
      await reviewSection
        .getByLabel("Commentaire de validation")
        .fill("Préciser le périmètre avant clôture.");
      expect(
        (
          await reviewer.request.put(`/api/missions/${id}/review`, {
            headers: { Origin: "http://localhost:3000" },
            data: { version: 4, decision: "APPROVED", comment: "Test" },
          })
        ).status(),
      ).toBe(403);
      await reviewSection
        .getByRole("button", { name: "Demander des corrections" })
        .click();
      await expect(reviewer.locator(".page-heading .status")).toHaveText(
        "En cours",
      );
      await page.reload();
      await expect(
        page
          .getByRole("region", { name: "Décisions et suivi" })
          .getByText("Préciser le périmètre avant clôture."),
      ).toBeVisible();
      await section
        .getByRole("button", { name: "Soumettre à validation" })
        .click();
      await expect(page.locator(".page-heading .status")).toHaveText(
        "En attente de validation",
      );
      await reviewer.reload();
      await reviewSection
        .getByLabel("Commentaire de validation")
        .fill("Mission vérifiée et approuvée.");
      await reviewSection
        .getByRole("button", { name: "Approuver la mission" })
        .click();
      await expect(reviewer.locator(".page-heading .status")).toHaveText(
        "Approuvée",
      );
      await page.reload();
      await expect(page.locator(".page-heading .status")).toHaveText(
        "Approuvée",
      );
    } finally {
      await reviewContext.close();
    }
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
