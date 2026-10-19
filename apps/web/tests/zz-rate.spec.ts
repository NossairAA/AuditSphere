import { test, expect } from "@playwright/test";
test("limite de débit BFF renvoie 429 sans faire confiance aux adresses déclarées", async ({
  request,
}) => {
  const statuses = [];
  for (let i = 0; i < 125; i++)
    statuses.push(
      (
        await request.get("/api/session", {
          headers: {
            Cookie: "auditsphere-session=" + "z".repeat(43),
            "X-Forwarded-For": "192.0.2." + (i % 250),
          },
        })
      ).status(),
    );
  expect(statuses).toContain(401);
  expect(statuses).toContain(429);
});
