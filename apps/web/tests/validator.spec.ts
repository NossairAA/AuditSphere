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
test("désignation, accès limité et retrait du validateur", async ({
  page,
  browser,
}) => {
  await login(page, "manager");
  const session = await (await page.request.get("/api/session")).json();
  const headers = {
    Origin: "http://localhost:3000",
    "x-csrf-token": session.csrf,
  };
  const title = "Validateur " + Date.now();
  const created = await page.request.post("/api/missions", {
    headers,
    data: {
      title,
      description: "Test",
      perimeter: "Achats",
      assigneeSubjects: [],
    },
  });
  expect(created.status()).toBe(201);
  const mission = await created.json();
  await page.goto(`/missions/${mission.id}`);
  const section = page.getByRole("region", {
    name: "Validateur de la mission",
  });
  await section
    .getByLabel("Choisir un validateur")
    .selectOption({ label: "Thomas Laurent" });
  expect(
    (
      await page.request.put(`/api/missions/${mission.id}/validator`, {
        headers: { Origin: "http://localhost:3000" },
        data: { version: 0, validatorSubject: null },
      })
    ).status(),
  ).toBe(403);
  await section
    .getByRole("button", { name: "Enregistrer le validateur", exact: true })
    .click();
  await expect(
    section.getByText("Validateur désigné : Thomas Laurent"),
  ).toBeVisible();
  const context = await browser.newContext();
  const reviewer = await context.newPage();
  try {
    await login(reviewer, "validator");
    await expect(
      reviewer.getByRole("link", { name: "Consulter " + title, exact: true }),
    ).toBeVisible();
    await reviewer.goto(`/missions/${mission.id}`);
    await expect(
      reviewer.getByRole("heading", { name: title, exact: true }),
    ).toBeVisible();
    await expect(
      reviewer.getByText("Validateur désigné", { exact: true }),
    ).toBeVisible();
    await expect(reviewer.getByLabel("Choisir un validateur")).toHaveCount(0);
    expect(
      (
        await page.request.put(`/api/missions/${mission.id}/validator`, {
          headers,
          data: { version: 0, validatorSubject: null },
        })
      ).status(),
    ).toBe(409);
    await section.getByLabel("Choisir un validateur").selectOption("");
    await section
      .getByRole("button", { name: "Enregistrer le validateur", exact: true })
      .click();
    await expect(section.getByText("Validateur désigné : Aucun")).toBeVisible();
    expect(
      (await reviewer.request.get(`/api/missions/${mission.id}`)).status(),
    ).toBe(404);
  } finally {
    await context.close();
  }
});
