import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { recordMission } from "./test-data";
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
}
test("filtres globaux et navigation conservée", async ({ page }) => {
  await login(page, "manager");
  const session = await (await page.request.get("/api/session")).json();
  const title = "Navigation test " + Date.now();
  const r = await page.request.post("/api/missions", {
    headers: { Origin: "http://localhost:3000", "x-csrf-token": session.csrf },
    data: {
      title,
      description: "Scénario synthétique",
      perimeter: "Finance",
      assigneeSubjects: [],
    },
  });
  expect(r.status()).toBe(201);
  const mission = await r.json();
  recordMission(mission.id);
  await page.getByLabel("Rechercher", { exact: true }).fill(title);
  await page.getByLabel("Statut", { exact: true }).selectOption("DRAFT");
  await page.getByLabel("Trier par").selectOption("title");
  await page.getByRole("button", { name: "Rechercher", exact: true }).click();
  await expect(
    page.getByRole("link", { name: "Consulter " + title, exact: true }),
  ).toBeVisible();
  await expect(page).toHaveURL((url) => url.searchParams.get("q") === title && url.searchParams.get("status") === "DRAFT" && url.searchParams.get("sort") === "title");
  const filtered = page.url();
  await page
    .getByRole("link", { name: "Consulter " + title, exact: true })
    .click();
  await page.getByRole("link", { name: "Risques", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Ajouter un risque", exact: true }),
  ).toBeVisible();
  await page.reload();
  await expect(
    page.getByRole("link", { name: "Risques", exact: true }),
  ).toHaveAttribute("aria-current", "page");
  await page
    .getByRole("link", { name: "Toutes les missions", exact: true })
    .click();
  await expect(page).toHaveURL(filtered);
  expect((await page.request.get("/api/admin/summary")).status()).toBe(403);
});
