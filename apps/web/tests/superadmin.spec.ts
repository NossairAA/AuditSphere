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
test("superadmin : refus de sa propre approbation et dérogations sur une autre mission", async ({
  page,
  browser,
}) => {
  await login(page, "manager");
  const managerSession = await (await page.request.get("/api/session")).json();
  const managerHeaders = {
    Origin: "http://localhost:3000",
    "x-csrf-token": managerSession.csrf,
  };
  const created = await page.request.post("/api/missions", {
    headers: managerHeaders,
    data: {
      title: "Admin global " + Date.now(),
      description: "Scénario synthétique",
      perimeter: "Tests",
      plannedStartDate: "2026-11-01",
      plannedEndDate: "2026-11-02",
    },
  });
  expect(created.status()).toBe(201);
  const other = await created.json();
  recordMission(other.id);
  expect(
    (
      await page.request.put(`/api/missions/${other.id}/override`, {
        headers: managerHeaders,
        data: { version: 0, action: "DELETE", reason: "Interdit" },
      })
    ).status(),
  ).toBe(403);
  await expect(
    page.getByText("Dérogations du superadministrateur"),
  ).toHaveCount(0);
  const context = await browser.newContext();
  try {
    const admin = await context.newPage();
    await login(admin, "superadmin");
    await expect(
      admin.getByRole("link", { name: "Nouvelle mission", exact: true }),
    ).toBeVisible();
    await admin.goto(`/missions/${other.id}`);
    await expect(
      admin.getByRole("heading", { name: other.title }),
    ).toBeVisible();
    await expect(
      admin.getByRole("button", { name: "Modifier les dates" }),
    ).toBeVisible();
    const session = await (await admin.request.get("/api/session")).json();
    const headers = {
      Origin: "http://localhost:3000",
      "x-csrf-token": session.csrf,
    };
    const own = await admin.request.post("/api/missions", {
      headers,
      data: {
        title: "Admin personnel " + Date.now(),
        description: "Scénario synthétique",
        perimeter: "Tests",
        plannedStartDate: "2026-11-01",
        plannedEndDate: "2026-11-02",
      },
    });
    expect(own.status()).toBe(201);
    let mission = await own.json();
    recordMission(mission.id);
    expect(
      (await page.request.get(`/api/missions/${mission.id}`)).status(),
    ).toBe(404);
    await admin.goto(`/missions/${mission.id}`);
    await admin.getByRole("button", { name: "Démarrer la mission" }).click();
    await expect(admin.locator(".page-heading .status")).toHaveText("En cours");
    expect((await admin.request.put(`/api/missions/${mission.id}/override`, {
      headers, data: { version: 1, action: "APPROVE", reason: "Autoapprobation refusée" },
    })).status()).toBe(409);
    await expect(admin.locator('option[value="APPROVE"]')).toHaveJSProperty("disabled", true);
    mission = other;
    expect((await page.request.put(`/api/missions/${mission.id}/status`, {
      headers: managerHeaders, data: { version: 0, status: "IN_PROGRESS" },
    })).status()).toBe(200);
    await admin.goto(`/missions/${mission.id}`);

    await admin
      .getByText("Dérogations du superadministrateur", { exact: true })
      .click();
    const panel = admin.getByRole("region", {
      name: "Administration de la mission",
    });
    await panel.getByLabel("Action administrative").selectOption("APPROVE");
    await expect(
      panel.getByRole("button", { name: "Exécuter la dérogation" }),
    ).toBeDisabled();
    await panel
      .getByLabel("Motif de la dérogation")
      .fill("Approbation exceptionnelle documentée.");
    await panel.getByRole("checkbox").check();
    expect(
      (
        await admin.request.put(`/api/missions/${mission.id}/override`, {
          data: { version: 1, action: "APPROVE", reason: "Sans CSRF" },
        })
      ).status(),
    ).toBe(403);
    await panel.getByRole("button", { name: "Exécuter la dérogation" }).click();
    await expect(admin.locator(".page-heading .status")).toHaveText(
      "Approuvée",
    );
    await expect(
      admin.getByRole("region", { name: "Dernière dérogation administrative" }),
    ).toContainText("Approbation exceptionnelle documentée.");
    for (const [action, status] of [
      ["REOPEN", "En cours"],
      ["CLOSE", "Clôturée"],
    ]) {
      await panel.getByLabel("Action administrative").selectOption(action);
      await panel
        .getByLabel("Motif de la dérogation")
        .fill("Correction administrative synthétique.");
      await panel.getByRole("checkbox").check();
      await panel
        .getByRole("button", { name: "Exécuter la dérogation" })
        .click();
      await expect(admin.locator(".page-heading .status")).toHaveText(status);
    }
    await panel
      .getByRole("button", { name: "Consulter le journal des actions" })
      .click();
    await expect(
      panel.getByText("Les 100 dernières actions sont affichées."),
    ).toBeVisible();
    expect(
      (
        await (
          await admin.request.get(`/api/missions/${mission.id}/action-log`)
        ).json()
      ).filter((e: { action: string }) => e.action === "ADMIN_OVERRIDE"),
    ).toHaveLength(3);
    await panel.getByLabel("Action administrative").selectOption("DELETE");
    await panel
      .getByLabel("Motif de la dérogation")
      .fill("Suppression synthétique.");
    await panel.getByRole("checkbox").check();
    await panel.getByRole("button", { name: "Exécuter la dérogation" }).click();
    await expect(admin).toHaveURL(/\/missions$/);
    expect(
      (await admin.request.get(`/api/missions/${mission.id}`)).status(),
    ).toBe(404);
    expect(
      (
        await (
          await admin.request.get(`/api/missions/${mission.id}/action-log`)
        ).json()
      ).filter((e: { action: string }) => e.action === "ADMIN_OVERRIDE"),
    ).toHaveLength(4);
  } finally {
    await context.close();
  }
});
