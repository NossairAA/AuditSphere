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
test("suppression confirmée et retrait de soumission avec motif", async ({
  page,
}) => {
  await login(page, "manager");
  const session = await (await page.request.get("/api/session")).json();
  const headers = {
    Origin: "http://localhost:3000",
    "x-csrf-token": session.csrf,
  };
  const create = async () => {
    const r = await page.request.post("/api/missions", {
      headers,
      data: {
        title: "Retrait navigateur " + Date.now(),
        description: "Scénario synthétique",
        perimeter: "Tests",
        plannedStartDate: "2026-11-01",
        plannedEndDate: "2026-11-02",
      },
    });
    expect(r.status()).toBe(201);
    const created = await r.json();
    recordMission(created.id);
    return created;
  };
  const draft = await create();
  await page.goto(`/missions/${draft.id}`);
  await page.getByRole("button", { name: "Supprimer le brouillon" }).click();
  await expect(
    page.getByRole("button", { name: "Confirmer la suppression" }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "Annuler", exact: true }).click();
  expect((await page.request.get(`/api/missions/${draft.id}`)).status()).toBe(
    200,
  );
  expect(
    (
      await page.request.delete(`/api/missions/${draft.id}`, {
        data: { version: 0, reason: "Test" },
      })
    ).status(),
  ).toBe(403);
  await page.getByRole("button", { name: "Supprimer le brouillon" }).click();
  await page.getByLabel("Motif obligatoire").fill("Brouillon créé en double.");
  await page.getByRole("button", { name: "Confirmer la suppression" }).click();
  await expect(page).toHaveURL(/\/missions$/);
  expect((await page.request.get(`/api/missions/${draft.id}`)).status()).toBe(
    404,
  );
  let mission = await create();
  const mutate = async (resource: string, data: object) => {
    const r = await page.request.put(
      `/api/missions/${mission.id}/${resource}`,
      { headers, data: { version: mission.version, ...data } },
    );
    expect(r.status()).toBe(200);
    mission = await r.json();
  };
  const validators = await (await page.request.get("/api/validators")).json();
  await mutate("validator", { validatorSubject: validators[0].subject });
  await mutate("status", { status: "IN_PROGRESS" });
  await mutate("submission", {});
  await page.goto(`/missions/${mission.id}`);
  await expect(
    page.getByRole("button", { name: "Supprimer le brouillon" }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "Retirer la soumission" }).click();
  await page
    .getByLabel("Motif obligatoire")
    .fill("Périmètre à préciser avant nouvelle revue.");
  await page.getByRole("button", { name: "Confirmer le retrait" }).click();
  await expect(page.locator(".page-heading .status")).toHaveText("En cours");
  await page.getByRole("link", { name: "Suivi", exact: true }).click();
  await expect(
    page.getByText("Périmètre à préciser avant nouvelle revue.", {
      exact: true,
    }),
  ).toBeVisible();
  await page.getByRole("link", { name: "Vue d’ensemble", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Retirer la soumission" }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Soumettre à validation" }),
  ).toBeEnabled();
});
