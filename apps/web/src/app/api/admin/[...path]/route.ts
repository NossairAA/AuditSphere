import { json, session, validMutation } from "@/lib/auth";
import { rateLimit, rateLimitAnonymous, rateLimitSession } from "@/lib/rate";
import { limitedText, BodyError } from "@/lib/body";
async function handle(
  request: Request,
  { params }: { params: Promise<{ path: string[] }> },
) {
  const limited = rateLimit(request);
  if (limited) return limited;
  try {
    const user = await session();
    if (!user) {
      const anonymousLimit = rateLimitAnonymous(request);
      if (anonymousLimit) return anonymousLimit;
      return json({ error: "Reconnectez-vous." }, 401);
    }
    const limitedSession = rateLimitSession(user);
    if (limitedSession) return limitedSession;
    if (!user.roles.includes("SUPER_ADMIN"))
      return json({ error: "Accès réservé au superadministrateur." }, 403);
    const { path } = await params;
    const resource = path.join("/");
    if (
      !/^(summary|users(?:\/[0-9a-f-]{36}(?:\/(?:history|reset-password))?)?)$/i.test(
        resource,
      )
    )
      return json({ error: "Page introuvable." }, 404);
    let body: string | undefined;
    if (request.method !== "GET") {
      if (!validMutation(request, user, request.headers.get("x-csrf-token")))
        return json({ error: "Requête refusée." }, 403);
      const raw = await limitedText(request);
      let data;
      try {
        data = JSON.parse(raw);
      } catch {
        return json({ error: "Formulaire invalide." }, 400);
      }
      if (!data || typeof data !== "object" || Array.isArray(data))
        return json({ error: "Formulaire invalide." }, 400);
      body = JSON.stringify(
        Object.fromEntries(
          [
            "username",
            "firstName",
            "lastName",
            "email",
            "enabled",
            "roles",
            "reason",
          ]
            .filter((k) => k in data)
            .map((k) => [k, data[k]]),
        ),
      );
    }
    const url = new URL(
      "/admin/" + resource,
      process.env.API_ORIGIN || "http://localhost:8080",
    );
    for (const key of ["page", "q", "role", "enabled"]) {
      const v = new URL(request.url).searchParams.get(key);
      if (v) url.searchParams.set(key, v);
    }
    const response = await fetch(url, {
      method: request.method,
      headers: {
        Authorization: "Bearer " + user.accessToken,
        "Content-Type": "application/json",
      },
      body,
      cache: "no-store",
      signal: AbortSignal.timeout(30000),
    });
    if (!response.ok)
      return json(
        {
          error:
            response.status === 409
              ? "Une opération est déjà en cours. Actualisez avant de réessayer."
              : response.status === 400
                ? "Vérifiez les champs et conservez votre propre accès superadmin."
                : response.status === 404
                  ? "Compte introuvable."
                  : "Opération non confirmée. Rechargez l’annuaire et consultez le journal avant de réessayer.",
        },
        response.status,
      );
    return json(await response.json(), response.status);
  } catch (e) {
    return json(
      {
        error:
          e instanceof BodyError
            ? "Formulaire trop volumineux."
            : "Administration indisponible. Vérifiez le journal avant de réessayer.",
      },
      e instanceof BodyError ? 413 : 503,
    );
  }
}
export const GET = handle;
export const POST = handle;
export const PUT = handle;
