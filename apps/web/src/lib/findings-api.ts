import "server-only";
import { rateLimit, rateLimitAnonymous, rateLimitSession } from "./rate";
import { limitedText, BodyError } from "./body";
import { json, session, validMutation } from "./auth";
import {
  findingBody,
  findingError,
  findingPath,
  requiredRole,
  validIds,
  type FindingResource,
} from "./findings-rules";

export async function findings(
  request: Request,
  missionId: string,
  findingId?: string,
  resource?: FindingResource,
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
    if (!validIds(missionId, findingId))
      return json({ error: "Constat ou mission introuvable" }, 404);
    const headers: Record<string, string> = {
      Authorization: "Bearer " + data.accessToken,
    };
    let body: string | undefined;
    let version: number | undefined;
    let action: unknown;
    if (["POST", "PUT", "DELETE"].includes(request.method)) {
      if (!validMutation(request, data, request.headers.get("x-csrf-token")))
        return json({ error: "Requête refusée" }, 403);
      const raw = await limitedText(request);
      let input;
      try {
        input = JSON.parse(raw);
      } catch {
        return json({ error: "Formulaire invalide" }, 400);
      }
      if (!input || typeof input !== "object" || Array.isArray(input))
        return json({ error: "Formulaire invalide" }, 400);
      action = input.action;
      const role = requiredRole(resource, action);
      if (!data.roles.includes("SUPER_ADMIN") && !data.roles.includes(role))
        return json({ error: "Votre rôle ne permet pas cette action" }, 403);
      if (request.method === "DELETE") {
        if (!Number.isSafeInteger(input.version) || input.version < 0)
          return json({ error: "Formulaire invalide" }, 400);
        version = input.version;
      } else {
        body = JSON.stringify(findingBody(request.method, resource, input));
        headers["Content-Type"] = "application/json";
      }
    }
    const response = await fetch(
      new URL(
        findingPath(missionId, findingId, resource, version),
        process.env.API_ORIGIN || "http://localhost:8080",
      ),
      {
        method: request.method,
        headers,
        body,
        cache: "no-store",
        signal: AbortSignal.timeout(10_000),
      },
    );
    if (!response.ok)
      return json(
        { error: findingError(response.status, request.method, resource, action) },
        response.status,
      );
    if (response.status === 204) return new Response(null, { status: 204 });
    return json(await response.json(), response.status);
  } catch (error) {
    if (error instanceof BodyError)
      return json({ error: "Corps de requête refusé" }, error.status);
    return json(
      { error: "Service temporairement indisponible. Réessayez." },
      503,
    );
  }
}
