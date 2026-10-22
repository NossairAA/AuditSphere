import { recordMission } from "./test-data";
import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { Pool } from "pg";
const env = Object.fromEntries(
  readFileSync("../../.env", "utf8")
    .split("\n")
    .filter((x) => x.includes("="))
    .map((x) => {
      const n = x.indexOf("=");
      return [x.slice(0, n), x.slice(n + 1)];
    }),
);
const db = new Pool({
  connectionString: `postgresql://web:${env.WEB_DB_PASSWORD}@localhost:5544/web`,
});
test.afterAll(() => db.end());
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
async function sessionId(page: Page) {
  return (await page.context().cookies()).find(
    (c) => c.name === "auditsphere-session",
  )!.value;
}
const hashed = (s: string) => createHash("sha256").update(s).digest("hex");

test("responsable : connexion, création, détail, liste, session opaque et déconnexion", async ({
  page,
}) => {
  const bearerRequests: string[] = [];
  page.on("request", (r) => {
    if (r.headers()["authorization"]?.startsWith("Bearer "))
      bearerRequests.push(r.url());
  });
  await login(page, "manager");
  const cookie = (await page.context().cookies()).find(
    (c) => c.name === "auditsphere-session",
  )!;
  expect(cookie.httpOnly).toBe(true);
  expect(cookie.sameSite).toBe("Lax");
  expect(cookie.value).toMatch(/^[A-Za-z0-9_-]{43}$/);
  expect(await page.evaluate(() => document.cookie)).not.toContain(
    "auditsphere-session",
  );
  expect(await page.evaluate(() => localStorage.length)).toBe(0);
  await page
    .getByRole("link", { name: "Nouvelle mission", exact: true })
    .click();
  const title = "Audit navigateur " + Date.now();
  await page.getByLabel("Titre de la mission").fill(title);
  await page.getByLabel("Périmètre", { exact: true }).fill("Achats Europe");
  await page
    .getByLabel("Objectifs et description")
    .fill("Vérifier le circuit de validation avec des données synthétiques.");
  await page
    .getByRole("button", { name: "Créer la mission", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: title, exact: true }),
  ).toBeVisible();
  recordMission(new URL(page.url()).pathname.split("/").pop()!);
  await page.getByRole("link", { name: "Toutes les missions" }).click();
  await expect(
    page.getByRole("link", { name: "Consulter " + title, exact: true }),
  ).toBeVisible();
  await page.screenshot({ path: "test-results/dashboard.png", fullPage: true });
  expect(bearerRequests).toEqual([]);
  const identity = await (await page.request.get("/api/session")).json();
  expect(identity.accessToken).toBeUndefined();
  expect(identity.refreshToken).toBeUndefined();
  await page.getByRole("button", { name: "Se déconnecter" }).click();
  await page.waitForURL((url) => url.port === "8180" || url.pathname === "/");
  if (new URL(page.url()).port === "8180")
    await page.locator("#kc-logout").click();
  await expect(page).toHaveURL("http://localhost:3000/");
  const replay = await page.request.get("/api/session", {
    headers: { Cookie: "auditsphere-session=" + cookie.value },
  });
  expect(replay.status()).toBe(401);
});

test("auditeur : missions affectées seulement, création interdite et formulaire protégé", async ({
  page,
}) => {
  const tokenResponse = await fetch(
    "http://localhost:8180/realms/auditsphere/protocol/openid-connect/token",
    {
      method: "POST",
      body: new URLSearchParams({
        client_id: "auditsphere-demo",
        grant_type: "password",
        username: "manager",
        password: env.MANAGER_PASSWORD,
      }),
    },
  );
  const tokens = await tokenResponse.json();
  const title = "Mission affectée " + Date.now();
  const created = await fetch("http://localhost:8080/missions", {
    method: "POST",
    headers: {
      Authorization: "Bearer " + tokens.access_token,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      title,
      description: "Test affectation",
      perimeter: "Finance",
      assigneeSubjects: ["00000000-0000-0000-0000-000000000002"],
    }),
  });
  expect(created.status).toBe(201);
  const mission = await created.json();
  recordMission(mission.id);
  await login(page, "auditor");
  await expect(
    page.getByRole("link", { name: "Nouvelle mission", exact: true }),
  ).toHaveCount(0);
  await expect(page.getByText(title, { exact: true })).toBeVisible();
  await page.goto("/missions/" + mission.id);
  await expect(
    page.getByRole("heading", { name: title, exact: true }),
  ).toBeVisible();
  await page.goto("/missions/new");
  await expect(
    page.getByRole("heading", { name: "Création non autorisée" }),
  ).toBeVisible();
  const s = await (await page.request.get("/api/session")).json();
  expect(
    (
      await page.request.post("/api/missions", {
        headers: { Origin: "http://localhost:3000", "x-csrf-token": s.csrf },
        data: { title: "Interdit" },
      })
    ).status(),
  ).toBe(403);
  await page.context().clearCookies();
  await login(page, "outsider");
  expect(await page.getByText(title, { exact: true }).count()).toBe(0);
  await page.goto("/missions/" + mission.id);
  await expect(
    page.getByRole("heading", { name: "Mission indisponible" }),
  ).toBeVisible();
  expect((await page.request.get("/api/missions/" + mission.id)).status()).toBe(
    404,
  );
});

test("CSRF, renouvellement concurrent et session expirée", async ({ page }) => {
  await login(page, "manager");
  const s = await (await page.request.get("/api/session")).json();
  for (const headers of [
    { Origin: "http://evil.example", "x-csrf-token": s.csrf },
    { Origin: "http://localhost:3000" },
    { Origin: "http://localhost:3000", "x-csrf-token": "incorrect" },
  ] as Record<string, string>[])
    expect(
      (
        await page.request.post("/api/missions", {
          headers,
          data: { title: "Refus" },
        })
      ).status(),
    ).toBe(403);
  expect(
    (
      await page.request.post("/auth/logout", {
        headers: { Origin: "http://evil.example" },
        form: { csrf: s.csrf },
      })
    ).status(),
  ).toBe(403);
  const id = hashed(await sessionId(page));
  await db.query(
    `UPDATE web_sessions SET data=jsonb_set(data,'{expiresAt}','0') WHERE id_hash=$1`,
    [id],
  );
  const responses = await Promise.all(
    Array.from({ length: 4 }, () => page.request.get("/api/missions")),
  );
  expect(responses.map((r) => r.status())).toEqual([200, 200, 200, 200]);
  const stored = await db.query(
    "SELECT data FROM web_sessions WHERE id_hash=$1",
    [id],
  );
  expect(stored.rows[0].data.expiresAt).toBeGreaterThan(Date.now());
  await db.query(
    "UPDATE web_sessions SET expires_at=now()-interval '1 second' WHERE id_hash=$1",
    [id],
  );
  expect((await page.request.get("/api/session")).status()).toBe(401);
  await page.reload();
  await expect(
    page.getByRole("link", { name: "Se reconnecter" }),
  ).toBeVisible();
});

test("callback falsifié et visiteurs anonymes refusés, affichage mobile", async ({
  page,
}) => {
  await page.goto("/auth/callback?code=invalid&state=invalid");
  await expect(page).toHaveURL(/error=login/);
  expect((await page.request.get("/api/missions")).status()).toBe(401);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await expect(
    page.getByRole("link", { name: "Se connecter", exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: "test-results/login-mobile.png",
    fullPage: true,
  });
  await login(page, "manager");
  await page.screenshot({
    path: "test-results/dashboard-mobile.png",
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await expect(
    page.getByRole("link", { name: "Nouvelle mission", exact: true }),
  ).toBeVisible();
});

test("échec de renouvellement, durée absolue et indisponibilité affichée", async ({
  page,
}) => {
  await login(page, "manager");
  let id = hashed(await sessionId(page));
  await db.query(
    `UPDATE web_sessions SET data=jsonb_set(jsonb_set(data,'{expiresAt}','0'),'{refreshToken}','"invalid-refresh"') WHERE id_hash=$1`,
    [id],
  );
  expect((await page.request.get("/api/session")).status()).toBe(401);
  await page.context().clearCookies();
  await login(page, "manager");
  id = hashed(await sessionId(page));
  await db.query(
    `UPDATE web_sessions SET data=jsonb_set(data,'{absoluteExpiry}','0') WHERE id_hash=$1`,
    [id],
  );
  expect((await page.request.get("/api/session")).status()).toBe(401);
  await page.context().clearCookies();
  await login(page, "manager");
  const identity = await (await page.request.get("/api/session")).json();
  expect(
    (
      await page.request.post("/api/missions", {
        headers: {
          Origin: "http://localhost:3000",
          "x-csrf-token": identity.csrf,
        },
        data: { title: "", description: "Test", perimeter: "Test" },
      })
    ).status(),
  ).toBe(400);
  await page.route("**/api/missions?*", (route) =>
    route.fulfill({
      status: 503,
      contentType: "application/json",
      body: JSON.stringify({
        error: "Service temporairement indisponible. Réessayez.",
      }),
    }),
  );
  await page.reload();
  await expect(
    page.getByRole("alert").filter({ hasText: "temporairement indisponible" }),
  ).toContainText("temporairement indisponible");
  await expect(page.getByRole("button", { name: "Réessayer" })).toBeVisible();
});

test("affecter à la création, modifier les participants et révoquer une session déjà ouverte", async ({
  page,
  browser,
}) => {
  await login(page, "manager");
  const directory = await (await page.request.get("/api/auditors")).json();
  const auditor = directory.find(
    (p: { subject: string }) =>
      p.subject === "00000000-0000-0000-0000-000000000002",
  );
  const outsider = directory.find(
    (p: { subject: string }) =>
      p.subject === "00000000-0000-0000-0000-000000000003",
  );
  expect(auditor).toBeTruthy();
  expect(outsider).toBeTruthy();
  await page.goto("/missions/new");
  const title = "Mission équipe " + Date.now();
  await page.getByLabel("Titre de la mission").fill(title);
  await page.getByLabel("Périmètre", { exact: true }).fill("Équipe achats");
  await page
    .getByLabel("Objectifs et description")
    .fill("Vérifier les affectations depuis deux sessions.");
  await page.getByRole("checkbox", { name: auditor.name, exact: true }).check();
  await page
    .getByRole("button", { name: "Créer la mission", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: title, exact: true }),
  ).toBeVisible();
  const id = new URL(page.url()).pathname.split("/").pop()!;
  recordMission(id);
  await page.getByRole("link", { name: "Équipe", exact: true }).click();
  await expect(
    page.locator(".participants").getByText(auditor.name, { exact: true }),
  ).toBeVisible();
  const auditorContext = await browser.newContext({
    baseURL: "http://localhost:3000",
  });
  const auditorPage = await auditorContext.newPage();
  try {
    await login(auditorPage, "auditor");
    await auditorPage.goto("/missions/" + id);
    await expect(
      auditorPage.getByRole("heading", { name: title, exact: true }),
    ).toBeVisible();
    await expect(
      auditorPage.getByRole("button", { name: "Modifier les affectations" }),
    ).toHaveCount(0);
    expect((await auditorPage.request.get("/api/auditors")).status()).toBe(403);
    const identity = await (await page.request.get("/api/session")).json();
    expect(
      (
        await page.request.put(`/api/missions/${id}/assignments`, {
          headers: {
            Origin: "http://evil.example",
            "x-csrf-token": identity.csrf,
          },
          data: { assigneeSubjects: [] },
        })
      ).status(),
    ).toBe(403);
    expect(
      (
        await page.request.put(`/api/missions/${id}/assignments`, {
          headers: {
            Origin: "http://localhost:3000",
            "x-csrf-token": identity.csrf,
          },
          data: { assigneeSubjects: ["unknown-user"] },
        })
      ).status(),
    ).toBe(400);
    await page
      .getByRole("button", { name: "Modifier les affectations" })
      .click();
    await page
      .getByRole("checkbox", { name: auditor.name, exact: true })
      .uncheck();
    await page
      .getByRole("checkbox", { name: outsider.name, exact: true })
      .check();
    await page
      .getByRole("button", { name: "Enregistrer les affectations" })
      .click();
    await expect(page.getByText("Affectations enregistrées.")).toBeVisible();
    await expect(
      page.locator(".participants").getByText(outsider.name, { exact: true }),
    ).toBeVisible();
    await page.screenshot({
      path: "test-results/participants-desktop.png",
      fullPage: true,
    });
    expect(
      (await auditorPage.request.get(`/api/missions/${id}`)).status(),
    ).toBe(404);
    expect(
      (
        await auditorPage.request.get(`/api/missions/${id}/participants`)
      ).status(),
    ).toBe(404);
    const list = await (await auditorPage.request.get("/api/missions")).json();
    expect(list.items.some((m: { id: string }) => m.id === id)).toBe(false);
    await auditorPage.reload();
    await expect(
      auditorPage.getByRole("heading", { name: "Mission indisponible" }),
    ).toBeVisible();
    await auditorContext.clearCookies();
    await login(auditorPage, "outsider");
    await auditorPage.goto("/missions/" + id);
    await expect(
      auditorPage.getByRole("heading", { name: title, exact: true }),
    ).toBeVisible();
    await page.setViewportSize({ width: 390, height: 844 });
    await page
      .getByRole("button", { name: "Modifier les affectations" })
      .click();
    await expect(
      page.getByRole("checkbox", { name: outsider.name, exact: true }),
    ).toBeChecked();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true);
    await page.screenshot({
      path: "test-results/participants-mobile.png",
      fullPage: true,
    });
    await page
      .getByRole("checkbox", { name: outsider.name, exact: true })
      .uncheck();
    await page
      .getByRole("button", { name: "Enregistrer les affectations" })
      .click();
    await expect(page.getByText("Aucun auditeur affecté.")).toBeVisible();
    expect(
      (await auditorPage.request.get(`/api/missions/${id}`)).status(),
    ).toBe(404);
  } finally {
    await auditorContext.close();
  }
});

test("conflit de version, session sans réécriture et corps borné", async ({
  page,
}) => {
  await login(page, "manager");
  const identity = await (await page.request.get("/api/session")).json();
  const cookie = await sessionId(page),
    idHash = hashed(cookie);
  const before = await db.query(
    "SELECT xmin::text FROM web_sessions WHERE id_hash=$1",
    [idHash],
  );
  for (let i = 0; i < 3; i++)
    expect((await page.request.get("/api/session")).status()).toBe(200);
  const after = await db.query(
    "SELECT xmin::text FROM web_sessions WHERE id_hash=$1",
    [idHash],
  );
  expect(after.rows[0].xmin).toBe(before.rows[0].xmin);
  const absolute = Date.now() + 10 * 60_000;
  await db.query(
    "UPDATE web_sessions SET data=jsonb_set(data,'{absoluteExpiry}',to_jsonb($2::bigint)), expires_at=to_timestamp(($2::double precision)/1000) WHERE id_hash=$1",
    [idHash, absolute],
  );
  const cappedBefore = await db.query(
    "SELECT xmin::text FROM web_sessions WHERE id_hash=$1",
    [idHash],
  );
  expect((await page.request.get("/api/session")).status()).toBe(200);
  const cappedAfter = await db.query(
    "SELECT xmin::text FROM web_sessions WHERE id_hash=$1",
    [idHash],
  );
  expect(cappedAfter.rows[0].xmin).toBe(cappedBefore.rows[0].xmin);

  const headers = {
    Origin: "http://localhost:3000",
    "x-csrf-token": identity.csrf,
  };
  const created = await page.request.post("/api/missions", {
    headers,
    data: {
      title: "Conflit contrôlé",
      description: "Test de version",
      perimeter: "Test",
      assigneeSubjects: [],
    },
  });
  expect(created.status()).toBe(201);
  const mission = await created.json();
  recordMission(mission.id);
  const first = await page.request.put(
    `/api/missions/${mission.id}/assignments`,
    { headers, data: { version: mission.version, assigneeSubjects: [] } },
  );
  expect(first.status()).toBe(200);
  const stale = await page.request.put(
    `/api/missions/${mission.id}/assignments`,
    { headers, data: { version: mission.version, assigneeSubjects: [] } },
  );
  expect(stale.status()).toBe(409);
  const body = new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode("x".repeat(20000)));
      controller.enqueue(new TextEncoder().encode("y".repeat(20000)));
      controller.close();
    },
  });
  const large = await fetch("http://localhost:3000/api/missions", {
    method: "POST",
    headers: { ...headers, Cookie: "auditsphere-session=" + cookie },
    body,
    duplex: "half",
  } as RequestInit);
  expect(large.status).toBe(413);
});
