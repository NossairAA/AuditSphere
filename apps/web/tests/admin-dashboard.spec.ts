import { test, expect, type Page } from "@playwright/test";

const summary = {
  total: 14,
  inProgress: 4,
  pending: 3,
  overdue: 2,
  byStatus: {
    DRAFT: 1,
    IN_PROGRESS: 4,
    PENDING_REVIEW: 3,
    APPROVED: 2,
    CLOSED: 4,
  },
};

const mission = {
  id: "11111111-1111-4111-8111-111111111111",
  version: 0,
  title: "Échéance dépassée",
  description: "Scénario synthétique",
  perimeter: "Finance",
  status: "IN_PROGRESS",
  plannedStartDate: "2026-01-01",
  plannedEndDate: "2026-01-10",
  startedAt: "2026-01-01T00:00:00Z",
  closedAt: null,
  withdrawnAt: null,
  withdrawalReason: null,
  submittedAt: null,
  reviewedAt: null,
  reviewedBy: null,
  reviewDecision: null,
  reviewComment: null,
  adminOverrideAction: null,
  adminOverrideReason: null,
  adminOverrideAt: null,
  ownerSubject: "manager",
  validatorSubject: null,
  assigneeSubjects: [],
  createdAt: "2026-01-01T00:00:00Z",
};

async function mockDashboard(
  page: Page,
  options: { listStatus?: number; empty?: boolean } = {},
) {
  const requests: string[] = [];
  await page.route("**/api/session", (route) =>
    route.fulfill({
      json: {
        subject: "admin",
        name: "Admin",
        roles: ["SUPER_ADMIN"],
        csrf: "test-csrf",
      },
    }),
  );
  await page.route("**/api/admin/summary", (route) =>
    route.fulfill({ json: summary }),
  );
  await page.route("**/api/missions/owners", (route) =>
    route.fulfill({ json: [] }),
  );
  await page.route(
    "**/api/missions/11111111-1111-4111-8111-111111111111",
    (route) => route.fulfill({ json: mission }),
  );
  await page.route("**/api/missions?**", (route) => {
    requests.push(new URL(route.request().url()).search);
    if (options.listStatus && options.listStatus >= 400)
      return route.fulfill({
        status: options.listStatus,
        json: { error: "Service indisponible." },
      });
    return route.fulfill({
      json: {
        items: options.empty ? [] : [mission],
        page: 0,
        size: 5,
        total: options.empty ? 0 : 1,
      },
    });
  });
  return requests;
}

test("dashboard links, overdue request and mission return preserve context", async ({
  page,
}) => {
  const requests = await mockDashboard(page);
  await page.goto("/missions/admin");

  await expect(
    page.getByRole("heading", { name: "Tableau de bord", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Toutes les missions", exact: true }),
  ).toHaveAttribute("href", "/missions");
  await expect(
    page.getByRole("link", { name: "En cours", exact: true }),
  ).toHaveAttribute("href", "/missions?status=IN_PROGRESS");
  await expect(
    page.getByRole("link", { name: "À valider", exact: true }),
  ).toHaveAttribute("href", "/missions?status=PENDING_REVIEW");
  await expect(
    page.getByRole("link", { name: "En retard", exact: true }),
  ).toHaveAttribute("href", "/missions?overdue=true");
  await expect(
    page.getByText("Échéance dépassée", { exact: true }),
  ).toBeVisible();
  await page.screenshot({ path: "../../.local/design/dashboard.png", fullPage: true });
  expect(requests).toContain("?overdue=true&sort=end&size=5");

  const openMission = page.getByRole("link", {
    name: "Ouvrir Échéance dépassée",
    exact: true,
  });
  await expect(openMission).toHaveAttribute(
    "href",
    "/missions/11111111-1111-4111-8111-111111111111?returnTo=%2Fmissions%2Fadmin",
  );
  await openMission.click();
  await expect(page).toHaveURL(
    /\/missions\/11111111-1111-4111-8111-111111111111\?/,
  );
  await expect(page).toHaveURL(/returnTo=.*missions/);
  const backToDashboard = page.getByRole("main").getByRole("link", {
    name: "Tableau de bord",
    exact: true,
  });
  await expect(backToDashboard).toHaveAttribute("href", "/missions/admin");
  await backToDashboard.click();
  await expect(page).toHaveURL("http://localhost:3000/missions/admin");
});

test("dashboard empty and failed priority list states are surfaced", async ({
  page,
}) => {
  const options = { listStatus: 503 as number | undefined, empty: false };
  await mockDashboard(page, options);
  await page.goto("/missions/admin");
  await expect(
    page.getByRole("heading", { name: "Tableau de bord", exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("main").getByRole("alert")).toBeVisible();
  options.listStatus = undefined;
  options.empty = true;
  await page.getByRole("button", { name: "Réessayer" }).click();
  await expect(
    page.getByRole("heading", {
      name: "Aucune mission en retard",
      exact: true,
    }),
  ).toBeVisible();
});

test("dashboard fits a narrow viewport", async ({ page }) => {
  await mockDashboard(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/missions/admin");
  await expect(
    page.getByRole("heading", { name: "Tableau de bord", exact: true }),
  ).toBeVisible();
  await expect(page.getByText("Échéance dépassée", { exact: true })).toBeVisible();
  await page.screenshot({ path: "../../.local/design/dashboard-mobile.png", fullPage: true });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});
