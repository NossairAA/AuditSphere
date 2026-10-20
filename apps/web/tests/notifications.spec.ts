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
test("notification réelle après affectation, navigation et isolation", async ({
  browser,
}) => {
  const manager = await browser.newContext();
  const auditor = await browser.newContext();
  const outsider = await browser.newContext();
  try {
    const m = await manager.newPage();
    await login(m, "manager");
    const session = await (await manager.request.get("/api/session")).json();
    const response = await manager.request.post("/api/missions", {
      headers: {
        Origin: "http://localhost:3000",
        "x-csrf-token": session.csrf,
      },
      data: {
        title: "Notification navigateur " + Date.now(),
        description: "Test synthétique",
        perimeter: "Tests",
        assigneeSubjects: ["00000000-0000-0000-0000-000000000002"],
      },
    });
    expect(response.status()).toBe(201);
    const mission = await response.json();
    recordMission(mission.id);
    const a = await auditor.newPage();
    const bearerRequests: string[] = [];
    a.on("request", (r) => {
      if (r.headers()["authorization"]?.startsWith("Bearer "))
        bearerRequests.push(r.url());
    });
    await login(a, "auditor");
    await expect
      .poll(
        async () => {
          const r = await auditor.request.get("/api/notifications");
          if (r.status() !== 200) return false;
          return (await r.json()).items.some(
            (n: { missionId: string }) => n.missionId === mission.id,
          );
        },
        { timeout: 30000 },
      )
      .toBe(true);
    await a.getByRole("link", { name: "Notifications", exact: true }).click();
    await expect(
      a.getByRole("heading", { name: "Votre activité" }),
    ).toBeVisible();
    const link = a.locator('a[href="/missions/' + mission.id + '"]');
    await expect(link).toHaveCount(1);
    const notifications = await (
      await auditor.request.get("/api/notifications")
    ).json();
    const notification = notifications.items.find(
      (n: { missionId: string }) => n.missionId === mission.id,
    );
    expect(notification.readAt).toBeNull();
    const readUrl = `/api/notifications/${notification.id}/read`;
    const auditorSession = await (
      await auditor.request.get("/api/session")
    ).json();
    expect(
      (
        await auditor.request.put(readUrl, {
          headers: { Origin: "http://localhost:3000" },
        })
      ).status(),
    ).toBe(403);
    expect(
      (
        await auditor.request.put(readUrl, {
          headers: {
            Origin: "https://untrusted.example",
            "x-csrf-token": auditorSession.csrf,
          },
        })
      ).status(),
    ).toBe(403);
    const before = (
      await (
        await auditor.request.get("/api/notifications/unread-count")
      ).json()
    ).count;
    const row = a.getByRole("row").filter({ has: link });
    await expect(row).toContainText(mission.title);
    await expect(link).toHaveAccessibleName(
      `Ouvrir la mission : ${mission.title}`,
    );
    await expect(
      a.getByRole("columnheader", { name: "LECTURE", exact: true }),
    ).toHaveCount(0);
    const timestamps = notifications.items.map((n: { receivedAt: string }) =>
      Date.parse(n.receivedAt),
    );
    expect(timestamps).toEqual([...timestamps].sort((a, b) => b - a));
    await a.route("**" + readUrl, (route) =>
      route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({ error: "Lecture indisponible" }),
      }),
    );
    await link.click();
    await expect(
      a.getByRole("region", { name: "Vos notifications" }).getByRole("alert"),
    ).toContainText("Lecture indisponible");
    await expect(a).toHaveURL(/notifications$/);
    await a.unroute("**" + readUrl);
    await link.click();
    await expect(a.getByRole("heading", { name: mission.title })).toBeVisible();
    await expect
      .poll(
        async () =>
          (
            await (
              await auditor.request.get("/api/notifications/unread-count")
            ).json()
          ).count,
      )
      .toBe(before - 1);
    if (before > 1)
      await expect(
        a.locator("header").getByRole("status", {
          name: `${before - 1} notifications non lues`,
          exact: true,
        }),
      ).toBeVisible();
    else await expect(a.locator("header .notification-badge")).toHaveCount(0);
    await a.goto("/missions/notifications");
    await expect(row.getByText("Non lue", { exact: true })).toHaveCount(0);
    const saved = (
      await (await auditor.request.get("/api/notifications")).json()
    ).items.find((n: { id: string }) => n.id === notification.id);
    expect(saved.readAt).not.toBeNull();
    await link.click();
    await expect(a.getByRole("heading", { name: mission.title })).toBeVisible();
    expect(
      (
        await (
          await auditor.request.get("/api/notifications/unread-count")
        ).json()
      ).count,
    ).toBe(before - 1);
    const o = await outsider.newPage();
    await login(o, "outsider");
    const list = await (
      await outsider.request.get(
        "/api/notifications?recipientSubject=00000000-0000-0000-0000-000000000002",
      )
    ).json();
    expect(
      list.items.some((n: { missionId: string }) => n.missionId === mission.id),
    ).toBe(false);
    const outsiderSession = await (
      await outsider.request.get("/api/session")
    ).json();
    expect(
      (
        await outsider.request.put(readUrl, {
          headers: {
            Origin: "http://localhost:3000",
            "x-csrf-token": outsiderSession.csrf,
          },
        })
      ).status(),
    ).toBe(404);
    const revoked = await manager.request.put(
      `/api/missions/${mission.id}/assignments`,
      {
        headers: {
          Origin: "http://localhost:3000",
          "x-csrf-token": session.csrf,
        },
        data: { version: mission.version, assigneeSubjects: [] },
      },
    );
    expect(revoked.status()).toBe(200);
    const afterRevocation = (
      await (await auditor.request.get("/api/notifications")).json()
    ).items.find((n: { id: string }) => n.id === notification.id);
    expect(afterRevocation.missionTitle).toBeNull();
    expect(bearerRequests).toEqual([]);
  } finally {
    await manager.close();
    await auditor.close();
    await outsider.close();
  }
});
test("notifications refusées sans session et erreur temporaire récupérable", async ({
  page,
}) => {
  expect((await page.request.get("/api/notifications")).status()).toBe(401);
  await login(page, "auditor");
  await page.route("**/api/notifications*", (route) =>
    route.fulfill({
      status: 503,
      contentType: "application/json",
      body: JSON.stringify({
        error: "Notifications temporairement indisponibles.",
      }),
    }),
  );
  await page.getByRole("link", { name: "Notifications", exact: true }).click();
  await expect(
    page.getByRole("region", { name: "Vos notifications" }).getByRole("alert"),
  ).toContainText("temporairement indisponibles");
  await page.unroute("**/api/notifications*");
  await page.getByRole("button", { name: "Actualiser", exact: true }).click();
  await expect(
    page.getByRole("region", { name: "Vos notifications" }).getByRole("alert"),
  ).toHaveCount(0);
  await expect(page.locator("tbody tr").first()).toBeVisible();
});

test("nouvelle notification en direct sur une liste déjà ouverte", async ({
  browser,
}) => {
  const manager = await browser.newContext();
  const auditor = await browser.newContext();
  const outsider = await browser.newContext();
  try {
    const m = await manager.newPage();
    await login(m, "manager");
    const a = await auditor.newPage();
    await login(a, "auditor");
    const o = await outsider.newPage();
    await login(o, "outsider");
    const opened = a.waitForResponse(
      (r) =>
        r.url().endsWith("/api/notifications/stream") && r.status() === 200,
    );
    await a.goto("/missions/notifications");
    await opened;
    await o.goto("/missions/notifications");
    await expect(a.locator("tbody")).toBeVisible();
    const before = (
      await (
        await auditor.request.get("/api/notifications/unread-count")
      ).json()
    ).count;
    const session = await (await manager.request.get("/api/session")).json();
    const title = "Notification direct " + Date.now();
    const created = await manager.request.post("/api/missions", {
      headers: {
        Origin: "http://localhost:3000",
        "x-csrf-token": session.csrf,
      },
      data: {
        title,
        description: "Test synthétique temps réel",
        perimeter: "Tests",
        assigneeSubjects: ["00000000-0000-0000-0000-000000000002"],
      },
    });
    expect(created.status()).toBe(201);
    const mission = await created.json();
    recordMission(mission.id);
    await expect(a.locator("tbody tr").filter({ hasText: title })).toBeVisible({
      timeout: 8000,
    });
    await expect(
      a.locator("header").getByRole("status", {
        name: `${before + 1} notifications non lues`,
        exact: true,
      }),
    ).toBeVisible({ timeout: 8000 });
    await expect(o.locator("tbody tr").filter({ hasText: title })).toHaveCount(
      0,
    );
    // Reconnect resynchronizes persisted state without waiting for the 30-second fallback.
    await auditor.setOffline(true);
    await auditor.setOffline(false);
    const otherTab = await auditor.newPage();
    await otherTab.goto("/missions/notifications");
    await otherTab.locator(`a[href="/missions/${mission.id}"]`).click();
    if (before === 0) {
      // The badge is hidden when the last unread notification has been opened.
      await expect(a.locator("header .notification-badge")).toHaveCount(0, { timeout: 8000 });
    } else {
      await expect(
        a.locator("header").getByRole("status", {
          name: `${before} notifications non lues`,
          exact: true,
        }),
      ).toBeVisible({ timeout: 8000 });
    }
    await expect(
      a
        .locator("tbody tr")
        .filter({ hasText: title })
        .getByText("Non lue", { exact: true }),
    ).toHaveCount(0, { timeout: 8000 });
  } finally {
    await manager.close();
    await auditor.close();
    await outsider.close();
  }
});
