import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { recordMission } from "./test-data";
const env = Object.fromEntries(
  readFileSync("../../.env", "utf8")
    .split("\n")
    .filter((l) => l.includes("="))
    .map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]),
);
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
test("programme préparé par le responsable, consultable par l’auditeur et verrouillé en revue", async ({
  page,
  browser,
}) => {
  await login(page, "manager");
  const session = await (await page.request.get("/api/session")).json();
  const people = await (await page.request.get("/api/auditors")).json();
  const assigned = people.find(
    (p: { subject: string }) =>
      p.subject === "00000000-0000-0000-0000-000000000002",
  );
  const response = await page.request.post("/api/missions", {
    headers: { Origin: "http://localhost:3000", "x-csrf-token": session.csrf },
    data: {
      title: "Programme achats test",
      description: "Scénario synthétique",
      perimeter: "Achats",
      assigneeSubjects: [assigned.subject],
      plannedStartDate: "2026-11-01",
      plannedEndDate: "2026-11-15",
    },
  });
  expect(response.status()).toBe(201);
  const mission = await response.json();
  recordMission(mission.id);
  await page.goto(`/missions/${mission.id}`);
  const program = page.getByRole("region", { name: "Programme d’audit" });
  await program
    .getByRole("button", { name: "Ajouter un risque", exact: true })
    .click();
  await program.getByLabel("Titre du risque").fill("Paiement en double");
  await program
    .getByLabel("Description du risque")
    .fill("Une facture peut être réglée deux fois.");
  await program.getByLabel("Probabilité", { exact: true }).selectOption("3");
  await program.getByLabel("Impact", { exact: true }).selectOption("4");
  await program
    .getByRole("button", { name: "Enregistrer le programme" })
    .click();
  await expect(
    program.getByRole("heading", { name: "Paiement en double", exact: true }),
  ).toBeVisible();
  await program
    .getByRole("button", { name: "Ajouter un contrôle", exact: true })
    .click();
  await program
    .getByLabel("Objectif du contrôle")
    .fill("Vérifier les doublons de factures");
  await program
    .getByLabel("Procédure de vérification")
    .fill(
      "Comparer fournisseur, référence et montant, puis examiner les doublons.",
    );
  await program
    .getByRole("checkbox", { name: "Paiement en double", exact: true })
    .check();
  await program
    .getByRole("button", { name: "Enregistrer le programme" })
    .click();
  await expect(
    program.getByRole("heading", {
      name: "Vérifier les doublons de factures",
      exact: true,
    }),
  ).toBeVisible();
  await program
    .getByRole("button", {
      name: "Modifier le risque Paiement en double",
      exact: true,
    })
    .click();
  await program
    .getByLabel("Description du risque")
    .fill("Risque financier lié aux doubles règlements.");
  await program
    .getByRole("button", { name: "Enregistrer le programme" })
    .click();
  await expect(
    program.getByText("Risque financier lié aux doubles règlements.", {
      exact: true,
    }),
  ).toBeVisible();
  await page.screenshot({
    path: "../../.local/design/program-desktop.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "../../.local/design/program-mobile.png",
    fullPage: true,
  });
  const context = await browser.newContext();
  const auditor = await context.newPage();
  try {
    await login(auditor, "auditor");
    await auditor.goto(`/missions/${mission.id}`);
    const read = auditor.getByRole("region", { name: "Programme d’audit" });
    await expect(
      read.getByRole("heading", { name: "Paiement en double", exact: true }),
    ).toBeVisible();
    await expect(
      read.getByRole("button", { name: "Ajouter un risque" }),
    ).toHaveCount(0);
    const auditorSession = await (
      await auditor.request.get("/api/session")
    ).json();
    const denied = await auditor.request.put(
      `/api/missions/${mission.id}/risks`,
      {
        headers: {
          Origin: "http://localhost:3000",
          "x-csrf-token": auditorSession.csrf,
        },
        data: {
          version: 3,
          title: "Interdit",
          description: "Test",
          probability: 1,
          impact: 1,
        },
      },
    );
    expect(denied.status()).toBe(403);
  } finally {
    await context.close();
  }
  let current = await (
    await page.request.get(`/api/missions/${mission.id}`)
  ).json();
  const headers = {
    Origin: "http://localhost:3000",
    "x-csrf-token": session.csrf,
  };
  const validators = await (await page.request.get("/api/validators")).json();
  const validator = validators.find((p: { name: string }) => p.name === "Thomas Laurent");
  const designation = await page.request.put(
    `/api/missions/${mission.id}/validator`,
    {
      headers,
      data: { version: current.version, validatorSubject: validator.subject },
    },
  );
  expect(designation.ok()).toBe(true);
  current = await designation.json();
  const started = await page.request.put(`/api/missions/${mission.id}/status`, {
    headers,
    data: { version: current.version, status: "IN_PROGRESS" },
  });
  expect(started.ok()).toBe(true);
  current = await started.json();
  const submitted = await page.request.put(
    `/api/missions/${mission.id}/submission`,
    { headers, data: { version: current.version } },
  );
  expect(submitted.ok()).toBe(true);
  await page.reload();
  await expect(
    program.getByText(
      "Programme en lecture seule pendant la validation et après approbation.",
    ),
  ).toBeVisible();
  await expect(
    program.getByRole("button", { name: "Ajouter un risque" }),
  ).toHaveCount(0);
});
