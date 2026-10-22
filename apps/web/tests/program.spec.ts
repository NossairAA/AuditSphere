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
  await page.goto(`/missions/${mission.id}?tab=risks`);
  const program = page.getByRole("region", { name: "Programme d’audit" });
  await program
    .getByRole("button", { name: "Ajouter un risque", exact: true })
    .click();
  await program.getByLabel("Titre du risque").fill("Paiement en double");
  await program
    .getByLabel("Description du risque")
    .fill("Une facture peut être réglée deux fois.");
  await page
    .getByRole("dialog")
    .getByLabel("Probabilité", { exact: true })
    .selectOption("3");
  await page
    .getByRole("dialog")
    .getByLabel("Impact", { exact: true })
    .selectOption("4");
  await program
    .getByRole("button", { name: "Enregistrer le programme" })
    .click();
  await expect(
    program.getByText("Paiement en double", { exact: true }),
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
  await expect(program.getByText("Programme enregistré.")).toBeVisible();
  const savedProgram = await (
    await page.request.get(`/api/missions/${mission.id}/program`)
  ).json();
  const controlId = savedProgram.controls[0].id;
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
  await program
    .locator("summary")
    .filter({ hasText: "Paiement en double" })
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
  const beforeStart = await (
    await page.request.get(`/api/missions/${mission.id}`)
  ).json();
  const started = await page.request.put(`/api/missions/${mission.id}/status`, {
    headers: { Origin: "http://localhost:3000", "x-csrf-token": session.csrf },
    data: { version: beforeStart.version, status: "IN_PROGRESS" },
  });
  expect(started.ok()).toBe(true);
  const context = await browser.newContext();
  const auditor = await context.newPage();
  try {
    await login(auditor, "auditor");
    await auditor.goto(`/missions/${mission.id}/controls/${controlId}`);
    const read = auditor.getByRole("region", { name: "Programme d’audit" });
    await expect(
      read.getByText("Paiement en double", { exact: true }),
    ).toBeVisible();
    await expect(
      read.getByRole("button", { name: "Ajouter un risque" }),
    ).toHaveCount(0);
    await read
      .getByRole("button", { name: "Renseigner le résultat", exact: true })
      .click();
    await read
      .getByLabel("Conclusion", { exact: true })
      .selectOption("NON_CONFORMING");
    await read
      .getByLabel("Justification du résultat")
      .fill("Deux factures identiques ont été réglées.");
    await read
      .getByRole("button", { name: "Enregistrer le résultat", exact: true })
      .click();
    await expect(
      read.getByText("Deux factures identiques ont été réglées.", {
        exact: true,
      }),
    ).toBeVisible();
    await expect(read.getByText(/Renseigné par Julien Moreau/)).toBeVisible();
    await read
      .getByRole("button", { name: "Corriger le résultat", exact: true })
      .click();
    await read
      .getByLabel("Justification du résultat")
      .fill("Deux factures identiques, références confirmées.");
    await read
      .getByRole("button", { name: "Enregistrer le résultat", exact: true })
      .click();
    await expect(
      read.getByText("Deux factures identiques, références confirmées.", {
        exact: true,
      }),
    ).toBeVisible();
    await read.screenshot({ path: "../../.local/design/control-result.png" });
    await read.getByText("Preuves du contrôle", { exact: true }).click();
    const fixture = Buffer.concat([
      Buffer.from("%PDF-1.4\n% Synthetic test fixture\n"),
      Buffer.alloc(40000, 32),
      Buffer.from("\n%%EOF"),
    ]);
    await read
      .getByLabel("Ajouter une preuve")
      .setInputFiles({
        name: "facture-test.pdf",
        mimeType: "application/pdf",
        buffer: fixture,
      });
    await read
      .getByRole("button", { name: "Joindre le fichier", exact: true })
      .click();
    const proof = read.getByRole("link", {
      name: "facture-test.pdf",
      exact: true,
    });
    await expect(proof).toBeVisible();
    const href = (await proof.getAttribute("href"))!;
    const downloaded = await auditor.request.get(href);
    expect(downloaded.status()).toBe(200);
    expect(downloaded.headers()["content-disposition"]).toContain("attachment");
    expect(downloaded.headers()["x-content-type-options"]).toBe("nosniff");
    expect(await downloaded.body()).toEqual(fixture);
    const ownerDownload = await page.request.get(href);
    expect(ownerDownload.status()).toBe(200);
    const fresh = await browser.newContext();
    try {
      await login(await fresh.newPage(), "outsider");
      expect((await fresh.request.get(href)).status()).toBe(404);
    } finally {
      await fresh.close();
    }
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
  const validator = validators.find(
    (p: { name: string }) => p.name === "Thomas Laurent",
  );
  const designation = await page.request.put(
    `/api/missions/${mission.id}/validator`,
    {
      headers,
      data: { version: current.version, validatorSubject: validator.subject },
    },
  );
  expect(designation.ok()).toBe(true);
  current = await designation.json();
  const submitted = await page.request.put(
    `/api/missions/${mission.id}/submission`,
    { headers, data: { version: current.version } },
  );
  expect(submitted.ok()).toBe(true);
  await page.goto(`/missions/${mission.id}/controls/${controlId}`);
  await expect(
    program.getByText("Deux factures identiques, références confirmées.", {
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    program.getByRole("button", { name: "Corriger le résultat" }),
  ).toHaveCount(0);
  await expect(
    program.getByText(
      "Programme en lecture seule pendant la validation et après approbation.",
    ),
  ).toBeVisible();
  await expect(
    program.getByRole("button", { name: "Ajouter un risque" }),
  ).toHaveCount(0);
});
