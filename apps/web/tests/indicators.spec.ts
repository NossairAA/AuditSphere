import { test, expect, type Page } from "@playwright/test";

const id = "22222222-2222-4222-8222-222222222222";
const mission = {
  id,
  version: 0,
  title: "Mission indicateurs",
  description: "",
  perimeter: "Achats",
  status: "APPROVED",
  ownerSubject: "manager",
  validatorSubject: null,
  assigneeSubjects: [],
  createdAt: "2026-01-01T00:00:00Z",
};
const program = {
  mission,
  risks: [
    { id: "r1", title: "Risque un", description: "", probability: 5, impact: 5 },
    { id: "r2", title: "Risque deux", description: "", probability: 5, impact: 5 },
    { id: "r3", title: "Risque trois", description: "", probability: 1, impact: 2 },
  ],
  controls: [
    { id: "c1", objective: "", procedure: "", riskIds: ["r1"], result: { outcome: "CONFORMING" } },
    { id: "c2", objective: "", procedure: "", riskIds: ["r2"], result: { outcome: "NON_CONFORMING" } },
    { id: "c3", objective: "", procedure: "", riskIds: ["r2"], result: null },
  ],
};
const followUps = {
  missionId: id,
  title: mission.title,
  issueCycle: 1,
  state: "ACTIVE",
  syncedAt: "2026-01-01T00:00:00Z",
  validatorSubject: null,
  followUps: [
    { status: "ACTION_PLAN", actions: [{ status: "TODO", overdue: true }, { status: "VERIFIED", overdue: false }] },
    { status: "WITHDRAWN", actions: [{ status: "TODO", overdue: true }] },
  ],
};

async function mockMission(page: Page, options: { findingsStatus?: number } = {}) {
  await page.route("**/api/session", (route) =>
    route.fulfill({
      json: { subject: "manager", name: "Responsable", roles: ["AUDIT_MANAGER"], csrf: "test-csrf" },
    }),
  );
  await page.route(`**/api/missions/${id}`, (route) => route.fulfill({ json: mission }));
  await page.route(`**/api/missions/${id}/program`, (route) => route.fulfill({ json: program }));
  await page.route(`**/api/missions/${id}/findings`, (route) =>
    options.findingsStatus
      ? route.fulfill({ status: options.findingsStatus, json: { error: "Service indisponible." } })
      : route.fulfill({
          json: [
            { severity: "CRITICAL", status: "VALIDATED" },
            { severity: "LOW", status: "DRAFT" },
            { severity: "HIGH", status: "REJECTED" },
          ],
        }),
  );
  await page.route(`**/api/remediation/missions/${id}/follow-ups`, (route) =>
    route.fulfill({ json: followUps }),
  );
}

test("indicators chart control results, risks, findings and actions", async ({ page }) => {
  await mockMission(page);
  await page.goto(`/missions/${id}`);
  await page.getByRole("link", { name: "Indicateurs", exact: true }).click();

  await expect(
    page.getByRole("img", {
      name: "Résultats des contrôles : Conforme 1, Non conforme 1, Sans résultat 1",
    }),
  ).toBeVisible();
  // Le constat rejeté n'est pas compté.
  await expect(
    page.getByRole("img", { name: "Constats par gravité : Faible 1, Critique 1" }),
  ).toBeVisible();
  // Les actions d'un suivi retiré ne sont pas comptées.
  await expect(
    page.getByRole("img", { name: "Actions correctives par statut : À faire 1, Vérifiée 1" }),
  ).toBeVisible();
  await expect(page.getByText("1 action est en retard.")).toBeVisible();

  const matrix = page.getByRole("table", {
    name: "Nombre de risques par impact et par probabilité",
  });
  await expect(
    matrix.getByRole("row", { name: "Critique" }).getByRole("cell").last(),
  ).toHaveText("2");
  await expect(page.getByText("1 risque n'est couvert par aucun contrôle.")).toBeVisible();
});

test("an unavailable indicator never shows false zeros and can be retried", async ({ page }) => {
  await mockMission(page, { findingsStatus: 503 });
  await page.goto(`/missions/${id}?tab=indicators`);

  await expect(page.getByText("Cet indicateur est temporairement indisponible.")).toBeVisible();
  await expect(page.getByRole("img", { name: /^Constats par gravité/ })).toHaveCount(0);
  await expect(page.getByRole("img", { name: /^Résultats des contrôles/ })).toBeVisible();

  await page.unroute(`**/api/missions/${id}/findings`);
  await page.route(`**/api/missions/${id}/findings`, (route) =>
    route.fulfill({ json: [{ severity: "HIGH", status: "VALIDATED" }] }),
  );
  await page.getByRole("button", { name: "Réessayer" }).click();
  await expect(page.getByRole("img", { name: "Constats par gravité : Élevée 1" })).toBeVisible();
});
