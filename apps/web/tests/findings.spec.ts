import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { recordMission } from "./test-data";
const env = Object.fromEntries(
  readFileSync("../../.env", "utf8")
    .split("\n")
    .filter((l) => l.includes("="))
    .map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]),
);
const origin = "http://localhost:3000";
const auditorSubject = "00000000-0000-0000-0000-000000000002";
async function login(page: Page, username: string) {
  await page.goto("/");
  await page.getByRole("link", { name: "Se connecter", exact: true }).click();
  await page.locator("#username").fill(username);
  await page
    .locator("#password")
    .fill(env[username.toUpperCase() + "_PASSWORD"]);
  await page.locator("#kc-login").click();
  await expect(page).toHaveURL(/\/missions$/);
}
async function csrf(page: Page) {
  return (await (await page.request.get("/api/session")).json()).csrf;
}

test("constat rédigé par l’auditeur, renvoyé puis validé avant la soumission de mission", async ({
  page,
  browser,
}) => {
  await login(page, "manager");
  const headers = { Origin: origin, "x-csrf-token": await csrf(page) };
  const created = await page.request.post("/api/missions", {
    headers,
    data: {
      title: "Constats achats test",
      description: "Scénario synthétique",
      perimeter: "Achats",
      assigneeSubjects: [auditorSubject],
      plannedStartDate: "2026-11-01",
      plannedEndDate: "2026-11-15",
    },
  });
  expect(created.status()).toBe(201);
  let mission = await created.json();
  recordMission(mission.id);
  const base = `/api/missions/${mission.id}`;
  let program = await (
    await page.request.put(`${base}/risks`, {
      headers,
      data: {
        version: mission.version,
        title: "Paiement en double",
        description: "Une facture peut être réglée deux fois.",
        probability: 3,
        impact: 4,
      },
    })
  ).json();
  program = await (
    await page.request.put(`${base}/controls`, {
      headers,
      data: {
        version: program.mission.version,
        objective: "Vérifier les doublons de factures",
        procedure: "Comparer fournisseur, référence et montant.",
        riskIds: [program.risks[0].id],
      },
    })
  ).json();
  const controlId = program.controls[0].id;
  const validators = await (await page.request.get("/api/validators")).json();
  const validator = validators.find(
    (p: { name: string }) => p.name === "Thomas Laurent",
  );
  mission = await (
    await page.request.put(`${base}/validator`, {
      headers,
      data: {
        version: program.mission.version,
        validatorSubject: validator.subject,
      },
    })
  ).json();
  const started = await page.request.put(`${base}/status`, {
    headers,
    data: { version: mission.version, status: "IN_PROGRESS" },
  });
  expect(started.ok()).toBe(true);
  mission = await started.json();

  const auditorContext = await browser.newContext();
  const auditor = await auditorContext.newPage();
  try {
    await login(auditor, "auditor");
    const auditorHeaders = { Origin: origin, "x-csrf-token": await csrf(auditor) };
    const result = await auditor.request.put(`${base}/results`, {
      headers: auditorHeaders,
      data: {
        version: mission.version,
        controlId,
        outcome: "NON_CONFORMING",
        comment: "Deux factures identiques ont été réglées.",
      },
    });
    expect(result.ok()).toBe(true);

    await auditor.goto(`/missions/${mission.id}?tab=findings`);
    const section = auditor.getByRole("region", { name: "Constats" });
    await section.getByRole("button", { name: "Nouveau constat" }).click();
    const drawer = auditor.getByRole("dialog");
    await drawer.getByLabel("Titre du constat").fill("Factures réglées deux fois");
    await drawer
      .getByLabel("Condition observée")
      .fill("Deux paiements ont été émis pour la facture F-104.");
    await drawer.getByLabel("Gravité").selectOption("HIGH");
    await drawer
      .getByRole("checkbox", { name: /Vérifier les doublons de factures/ })
      .check();
    await drawer.getByRole("button", { name: "Enregistrer le constat" }).click();
    const card = section.getByRole("article", { name: /^C-01 / });
    await expect(card.getByText("Brouillon", { exact: true })).toBeVisible();
    await expect(card.getByText("Gravité élevée")).toBeVisible();
    await expect(card.getByRole("button", { name: "Valider C-01" })).toHaveCount(0);

    await card.getByRole("button", { name: "Soumettre C-01" }).click();
    await expect(section.getByText(/Soumission impossible/)).toBeVisible();

    await card.getByRole("button", { name: "Modifier C-01" }).click();
    await drawer.getByLabel("Cause").fill("Aucun contrôle de doublon avant paiement.");
    await drawer
      .getByLabel("Recommandation")
      .fill("Bloquer les paiements dont la référence existe déjà.");
    await drawer.getByRole("button", { name: "Enregistrer le constat" }).click();
    await expect(section.getByText("Constat enregistré.")).toBeVisible();
    await card.getByRole("button", { name: "Soumettre C-01" }).click();
    await expect(card.getByText("Soumis", { exact: true })).toBeVisible();

    await page.goto(`/missions/${mission.id}`);
    await page.getByRole("button", { name: "Soumettre à validation" }).click();
    await expect(
      page.getByText(/Un constat attend encore une décision/),
    ).toBeVisible();
    await page.getByRole("link", { name: "Voir les constats" }).click();
    const managed = page.getByRole("region", { name: "Constats" });
    const managedCard = managed.getByRole("article", { name: /^C-01 / });
    await managedCard.getByRole("button", { name: "Renvoyer C-01" }).click();
    const decision = page.getByRole("dialog");
    await decision
      .getByLabel("Commentaire obligatoire")
      .fill("Préciser le montant payé deux fois.");
    await decision.getByRole("button", { name: "Renvoyer", exact: true }).click();
    await expect(managed.getByText("Constat renvoyé en brouillon.")).toBeVisible();
    await expect(
      managedCard.getByText("Préciser le montant payé deux fois.", { exact: true }),
    ).toBeVisible();

    await auditor.reload();
    await card.getByRole("button", { name: "Soumettre C-01" }).click();
    await expect(card.getByText("Soumis", { exact: true })).toBeVisible();

    await page.reload();
    await managedCard.getByRole("button", { name: "Valider C-01" }).click();
    await decision.getByRole("button", { name: "Valider", exact: true }).click();
    await expect(managedCard.getByText("Validé", { exact: true })).toBeVisible();
    await managedCard.getByRole("button", { name: "Historique de C-01" }).click();
    const history = managedCard.getByRole("list", { name: "Historique de C-01" });
    await expect(history.getByRole("listitem")).toHaveCount(6);
    await expect(history.getByText("Renvoi en brouillon")).toBeVisible();

    await page.screenshot({
      path: "../../.local/design/findings-desktop.png",
      fullPage: true,
    });
    await page.setViewportSize({ width: 390, height: 844 });
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
    ).toBe(true);
    await page.screenshot({
      path: "../../.local/design/findings-mobile.png",
      fullPage: true,
    });
    await page.setViewportSize({ width: 1280, height: 720 });

    const outsiderContext = await browser.newContext();
    try {
      await login(await outsiderContext.newPage(), "outsider");
      expect((await outsiderContext.request.get(`${base}/findings`)).status()).toBe(404);
    } finally {
      await outsiderContext.close();
    }

    await page.goto(`/missions/${mission.id}`);
    await page.getByRole("button", { name: "Soumettre à validation" }).click();
    await expect(
      page.locator(".status.large").getByText("En attente de validation"),
    ).toBeVisible();
    await auditor.goto(`/missions/${mission.id}?tab=findings`);
    await expect(
      section.getByText("Constats en lecture seule pendant la validation et après approbation."),
    ).toBeVisible();
    await expect(section.getByRole("button", { name: "Nouveau constat" })).toHaveCount(0);
  } finally {
    await auditorContext.close();
  }
});
