import { test, expect, type Page, type Browser } from "@playwright/test";
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
const day = (offset: number) => new Date(Date.now() + offset * 86400000).toISOString().slice(0, 10);
async function login(page: Page, username: string, landing = /\/missions$/) {
  await page.goto("/");
  await page.getByRole("link", { name: "Se connecter", exact: true }).click();
  await page.locator("#username").fill(username);
  await page.locator("#password").fill(env[username.toUpperCase() + "_PASSWORD"]);
  await page.locator("#kc-login").click();
  await expect(page).toHaveURL(landing);
}
async function headers(page: Page) {
  return { Origin: origin, "x-csrf-token": (await (await page.request.get("/api/session")).json()).csrf };
}
async function session(browser: Browser, username: string, landing?: RegExp) {
  const context = await browser.newContext();
  const page = await context.newPage();
  await login(page, username, landing);
  return { context, page };
}

test("action corrective confiée à l’audité, justifiée, vérifiée puis suivi clôturé", async ({ page, browser }) => {
  // Mission approuvée avec un constat validé, préparée par l'API du BFF.
  await login(page, "manager");
  const manager = await headers(page);
  const created = await page.request.post("/api/missions", {
    headers: manager,
    data: {
      title: "Actions correctives test",
      description: "Scénario synthétique",
      perimeter: "Achats",
      assigneeSubjects: [auditorSubject],
      plannedStartDate: day(0),
      plannedEndDate: day(14),
    },
  });
  expect(created.status()).toBe(201);
  let mission = await created.json();
  recordMission(mission.id);
  const base = `/api/missions/${mission.id}`;
  let program = await (
    await page.request.put(`${base}/risks`, {
      headers: manager,
      data: { version: mission.version, title: "Paiement en double", description: "Facture réglée deux fois.", probability: 3, impact: 4 },
    })
  ).json();
  program = await (
    await page.request.put(`${base}/controls`, {
      headers: manager,
      data: {
        version: program.mission.version,
        objective: "Vérifier les doublons de factures",
        procedure: "Comparer fournisseur, référence et montant.",
        riskIds: [program.risks[0].id],
      },
    })
  ).json();
  const validator = (await (await page.request.get("/api/validators")).json()).find(
    (p: { name: string }) => p.name === "Thomas Laurent",
  );
  mission = await (
    await page.request.put(`${base}/validator`, {
      headers: manager,
      data: { version: program.mission.version, validatorSubject: validator.subject },
    })
  ).json();
  mission = await (
    await page.request.put(`${base}/status`, { headers: manager, data: { version: mission.version, status: "IN_PROGRESS" } })
  ).json();

  const auditor = await session(browser, "auditor");
  const validatorSession = await session(browser, "validator");
  const auditee = await session(browser, "auditee", /\/(missions|actions)$/);
  const outsider = await session(browser, "outsider");
  try {
    const auditorHeaders = await headers(auditor.page);
    expect(
      (
        await auditor.page.request.put(`${base}/results`, {
          headers: auditorHeaders,
          data: { version: mission.version, controlId: program.controls[0].id, outcome: "NON_CONFORMING", comment: "Doublon constaté." },
        })
      ).ok(),
    ).toBe(true);
    const finding = await (
      await auditor.page.request.post(`${base}/findings`, {
        headers: auditorHeaders,
        data: {
          title: "Factures réglées deux fois",
          condition: "Deux paiements pour la facture F-104.",
          cause: "Aucun contrôle de doublon.",
          recommendation: "Bloquer les paiements dont la référence existe déjà.",
          severity: "HIGH",
          controlIds: [program.controls[0].id],
        },
      })
    ).json();
    const submitted = await (
      await auditor.page.request.put(`${base}/findings/${finding.id}/transition`, {
        headers: auditorHeaders,
        data: { version: finding.version, action: "SUBMIT" },
      })
    ).json();
    expect(
      (
        await page.request.put(`${base}/findings/${finding.id}/transition`, {
          headers: manager,
          data: { version: submitted.version, action: "VALIDATE" },
        })
      ).ok(),
    ).toBe(true);
    mission = await (await page.request.get(base)).json();
    mission = await (await page.request.put(`${base}/submission`, { headers: manager, data: { version: mission.version } })).json();
    const approved = await validatorSession.page.request.put(`${base}/review`, {
      headers: await headers(validatorSession.page),
      data: { version: mission.version, decision: "APPROVED", comment: "Mission approuvée pour test." },
    });
    expect(approved.ok()).toBe(true);

    // Le suivi arrive par Kafka : la liste est rechargée jusqu'à son apparition.
    await expect
      .poll(async () => (await page.request.get(`/api/remediation/missions/${mission.id}/follow-ups`)).status(), { timeout: 60_000 })
      .toBe(200);
    await page.goto(`/missions/${mission.id}?tab=remediation`);
    const tab = page.getByRole("region", { name: "Actions correctives" });
    const card = tab.getByRole("article", { name: /^Suivi C-01 / });
    await expect(card.getByText("À planifier", { exact: true })).toBeVisible();
    await card.getByRole("button", { name: "Ajouter une action au suivi C-01" }).click();
    let dialog = page.getByRole("dialog");
    await dialog.getByLabel("Description").fill("Paramétrer le blocage des références de factures en double.");
    await dialog.getByLabel("Rechercher un audité").fill("Nadia");
    await dialog.getByLabel("Responsable de l’action").selectOption({ label: "Nadia Rousseau" });
    await dialog.getByLabel("Échéance").fill(day(10));
    await dialog.getByRole("button", { name: "Créer l’action" }).click();
    await expect(tab.getByText("Action créée et notifiée à son responsable.")).toBeVisible();
    await expect(card.getByText("Plan d’action", { exact: true })).toBeVisible();
    await expect(card.getByRole("button", { name: "Demander la clôture du suivi C-01" })).toBeDisabled();

    // L'audité arrive sur ses actions, la notification ouvre la fiche.
    await auditee.page.goto("/missions");
    await expect(auditee.page).toHaveURL(/\/actions$/);
    await expect(auditee.page.getByRole("link", { name: "Missions d’audit" })).toHaveCount(0);
    const mine = auditee.page.getByRole("article", { name: /Paramétrer le blocage/ });
    await expect(mine.getByText("À faire", { exact: true })).toBeVisible();
    await auditee.page.goto("/missions/notifications");
    await auditee.page.getByRole("row", { name: /Une action corrective vous est confiée/ }).first().click();
    await expect(auditee.page).toHaveURL(/\/actions\/[0-9a-f-]{36}$/);
    const actionId = auditee.page.url().split("/").pop()!;
    await expect(auditee.page.getByRole("heading", { name: /Paramétrer le blocage/ })).toBeVisible();
    await expect(auditee.page.getByText("Bloquer les paiements dont la référence existe déjà.")).toBeVisible();
    await expect(auditee.page.getByRole("button", { name: "Déclarer réalisée" })).toBeDisabled();
    await auditee.page.getByLabel("Nouvel avancement").fill("Paramétrage demandé à l’éditeur.");
    await auditee.page.getByRole("button", { name: "Enregistrer l’avancement" }).click();
    await expect(auditee.page.getByText("Avancement enregistré.")).toBeVisible();
    await expect(auditee.page.locator(".status.large").getByText("En cours")).toBeVisible();
    const pdf = Buffer.from("%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n");
    await auditee.page.getByLabel(/Ajouter un justificatif/).setInputFiles({ name: "parametrage.pdf", mimeType: "application/pdf", buffer: pdf });
    await auditee.page.getByRole("button", { name: "Déposer" }).click();
    await expect(auditee.page.getByText("Justificatif déposé.")).toBeVisible();
    const download = auditee.page.waitForEvent("download");
    await auditee.page.getByRole("link", { name: "parametrage.pdf" }).click();
    expect((await download).suggestedFilename()).toBe("parametrage.pdf");
    await auditee.page.getByRole("button", { name: "Déclarer réalisée" }).click();
    dialog = auditee.page.getByRole("dialog");
    await dialog.getByLabel("Commentaire obligatoire").fill("Blocage actif depuis la version 4.2.");
    await dialog.getByRole("button", { name: "Déclarer réalisée" }).click();
    await expect(auditee.page.getByText("Réalisation déclarée.")).toBeVisible();
    await expect(auditee.page.getByRole("button", { name: "Retirer parametrage.pdf" })).toHaveCount(0);

    // L'auditeur renvoie, l'audité complète, l'auditeur vérifie.
    await auditor.page.goto("/actions/" + actionId);
    await auditor.page.getByRole("button", { name: "Renvoyer" }).click();
    dialog = auditor.page.getByRole("dialog");
    await dialog.getByLabel("Commentaire obligatoire").fill("Joindre la capture du paramétrage en production.");
    await dialog.getByRole("button", { name: "Renvoyer" }).click();
    await expect(auditor.page.getByText("Action renvoyée à son responsable.")).toBeVisible();
    await auditee.page.reload();
    await expect(
      auditee.page.getByRole("region", { name: "Constat à corriger" }).getByText("Joindre la capture du paramétrage en production."),
    ).toBeVisible();
    await auditee.page.getByRole("button", { name: "Déclarer réalisée" }).click();
    dialog = auditee.page.getByRole("dialog");
    await dialog.getByLabel("Commentaire obligatoire").fill("Capture ajoutée au justificatif existant.");
    await dialog.getByRole("button", { name: "Déclarer réalisée" }).click();
    await expect(auditee.page.getByText("Réalisation déclarée.")).toBeVisible();
    await expect(auditee.page.getByRole("button", { name: "Vérifier" })).toHaveCount(0);
    await auditor.page.reload();
    await auditor.page.getByRole("button", { name: "Vérifier" }).click();
    await auditor.page.getByRole("dialog").getByRole("button", { name: "Vérifier" }).click();
    await expect(auditor.page.getByText("Action vérifiée.")).toBeVisible();
    await expect(auditor.page.getByRole("list", { name: "Historique de l’action" }).getByRole("listitem")).toHaveCount(7);

    // Séparation des fonctions : le responsable demande, le validateur clôture.
    await page.reload();
    await card.getByRole("button", { name: "Demander la clôture du suivi C-01" }).click();
    await page.getByRole("dialog").getByRole("button", { name: "Demander la clôture" }).click();
    await expect(tab.getByText("Clôture demandée au validateur.")).toBeVisible();
    await expect(card.getByRole("button", { name: "Clôturer le suivi C-01" })).toHaveCount(0);
    await validatorSession.page.goto(`/missions/${mission.id}?tab=remediation`);
    const validated = validatorSession.page.getByRole("article", { name: /^Suivi C-01 / });
    await validated.getByRole("button", { name: "Clôturer le suivi C-01" }).click();
    await validatorSession.page.getByRole("dialog").getByRole("button", { name: "Clôturer" }).click();
    await expect(validated.getByText("Clôturé", { exact: true })).toBeVisible();

    // Hors périmètre : 404 pour l'extérieur, aucune liste de mission pour l'audité.
    expect((await outsider.page.request.get(`/api/remediation/actions/${actionId}`)).status()).toBe(404);
    expect((await auditee.page.request.get(`/api/remediation/missions/${mission.id}/follow-ups`)).status()).toBeGreaterThanOrEqual(403);
    expect((await auditee.page.request.get(`/api/remediation/internal/missions/${mission.id}`)).status()).toBe(404);

    await auditee.page.reload();
    await expect(auditee.page.locator(".status.large").getByText("Vérifiée")).toBeVisible();
    await auditee.page.screenshot({ path: "../../.local/design/action-desktop.png", fullPage: true });
    await auditee.page.setViewportSize({ width: 390, height: 844 });
    expect(await auditee.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await auditee.page.screenshot({ path: "../../.local/design/action-mobile.png", fullPage: true });
    await page.screenshot({ path: "../../.local/design/remediation-desktop.png", fullPage: true });
  } finally {
    for (const s of [auditor, validatorSession, auditee, outsider]) await s.context.close();
  }
});
