import "server-only";
import { json, session, validMutation } from "@/lib/auth";
import { rateLimit, rateLimitAnonymous, rateLimitSession } from "@/lib/rate";
export async function notificationRequest(
  request: Request,
  resource?: "unread-count" | "read",
  id?: string,
) {
  const rejection = rateLimit(request);
  if (rejection) return rejection;
  try {
    const data = await session();
    if (!data) {
      const anonymousLimit = rateLimitAnonymous(request);
      if (anonymousLimit) return anonymousLimit;
      return json({ error: "Votre session a expiré. Reconnectez-vous." }, 401);
    }
    const limitedSession = rateLimitSession(data);
    if (limitedSession) return limitedSession;
    if (resource === "read") {
      if (!validMutation(request, data, request.headers.get("x-csrf-token")))
        return json({ error: "Requête refusée" }, 403);
      if (
        !id ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
          id,
        )
      )
        return json({ error: "Notification introuvable" }, 404);
    }
    const url = new URL(
      resource === "read"
        ? `/notifications/${id}/read`
        : resource === "unread-count"
          ? "/notifications/unread-count"
          : "/notifications",
      process.env.API_ORIGIN || "http://localhost:8080",
    );
    if (!resource) {
      url.searchParams.set(
        "page",
        new URL(request.url).searchParams.get("page") || "0",
      );
      url.searchParams.set("size", "20");
    }
    const response = await fetch(url, {
      method: resource === "read" ? "PUT" : "GET",
      headers: { Authorization: "Bearer " + data.accessToken },
      cache: "no-store",
      signal: AbortSignal.timeout(10000),
    });
    if (!response.ok)
      return json(
        {
          error:
            response.status === 401
              ? "Votre session a expiré. Reconnectez-vous."
              : response.status === 404
                ? "Notification introuvable"
                : "Les notifications sont temporairement indisponibles.",
        },
        response.status,
      );
    const body = await response.json();
    if (!resource) {
      // Resolve only this page, with the same user's permissions and no shared cache.
      const ids = [
        ...new Set<string>(
          body.items.map((n: { missionId: string }) => n.missionId),
        ),
      ];
      let titles: Record<string, string> = {};
      if (ids.length) {
        try {
          const titleUrl = new URL("/missions/titles", url.origin);
          titleUrl.searchParams.set("ids", ids.join(","));
          const result = await fetch(titleUrl, {
            headers: { Authorization: "Bearer " + data.accessToken },
            cache: "no-store", signal: AbortSignal.timeout(5000),
          });
          if (result.ok) titles = await result.json();
        } catch { /* Titles are optional when the mission service is unavailable. */ }
      }
      body.items = body.items.map((n: { missionId: string }) => ({
        ...n,
        missionTitle: titles[n.missionId] ?? null,
      }));
    }
    return json(body);
  } catch {
    return json(
      {
        error:
          "Les notifications sont temporairement indisponibles. Réessayez.",
      },
      503,
    );
  }
}
