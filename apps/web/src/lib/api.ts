import "server-only";
import { rateLimit } from "./rate";
import { limitedText, BodyError } from "./body";
import { json, session, validMutation } from "./auth";
export async function missions(
  request: Request,
  id?: string,
  resource?:
    | "owners"
    | "program"
    | "risks"
    | "controls"
    | "results"
    | "assignments"
    | "participants"
    | "auditors"
    | "schedule"
    | "status"
    | "validators"
    | "validator"
    | "submission"
    | "review"
    | "withdrawal"
    | "override"
    | "action-log",
) {
  const rejection = rateLimit(request);
  if (rejection) return rejection;
  try {
    const data = await session();
    if (!data)
      return json({ error: "Votre session a expiré. Reconnectez-vous." }, 401);
    if (id && !/^[0-9a-f-]{36}$/i.test(id))
      return json({ error: "Mission introuvable" }, 404);
    if (
      (resource === "override" || resource === "action-log") &&
      !data.roles.includes("SUPER_ADMIN")
    )
      return json({ error: "Action réservée au superadministrateur" }, 403);
    const url = new URL(
      resource === "auditors" ||
        resource === "validators" ||
        resource === "owners"
        ? "/missions/" + resource
        : id
          ? "/missions/" + id + (resource ? "/" + resource : "")
          : "/missions",
      process.env.API_ORIGIN || "http://localhost:8080",
    );
    const headers: Record<string, string> = {
      Authorization: "Bearer " + data.accessToken,
    };
    let body: string | undefined;
    if (["POST", "PUT", "DELETE"].includes(request.method)) {
      if (!validMutation(request, data, request.headers.get("x-csrf-token")))
        return json({ error: "Requête refusée" }, 403);
      if (
        !data.roles.includes("SUPER_ADMIN") &&
        !data.roles.includes(
          resource === "results"
            ? "AUDITOR"
            : resource === "review"
              ? "AUDIT_VALIDATOR"
              : "AUDIT_MANAGER",
        )
      )
        return json({ error: "Votre rôle ne permet pas cette action" }, 403);
      const raw = await limitedText(request);
      if (Buffer.byteLength(raw) > 32768)
        return json({ error: "Formulaire trop volumineux" }, 413);
      let input;
      try {
        input = JSON.parse(raw);
      } catch {
        return json({ error: "Formulaire invalide" }, 400);
      }
      if (!input || typeof input !== "object" || Array.isArray(input))
        return json({ error: "Formulaire invalide" }, 400);
      body = JSON.stringify(
        resource === "results"
          ? {
              version: input.version,
              controlId: input.controlId,
              outcome: input.outcome,
              comment: input.comment,
            }
          : resource === "risks"
            ? {
                version: input.version,
                id: input.id,
                title: input.title,
                description: input.description,
                probability: input.probability,
                impact: input.impact,
              }
            : resource === "controls"
              ? {
                  version: input.version,
                  id: input.id,
                  objective: input.objective,
                  procedure: input.procedure,
                  riskIds: input.riskIds,
                }
              : resource === "override"
                ? {
                    version: input.version,
                    action: input.action,
                    reason: input.reason,
                  }
                : request.method === "DELETE" || resource === "withdrawal"
                  ? { version: input.version, reason: input.reason }
                  : request.method === "PUT"
                    ? resource === "review"
                      ? {
                          version: input.version,
                          decision: input.decision,
                          comment: input.comment,
                        }
                      : resource === "submission"
                        ? { version: input.version }
                        : resource === "validator"
                          ? {
                              version: input.version,
                              validatorSubject: input.validatorSubject,
                            }
                          : resource === "schedule"
                            ? {
                                version: input.version,
                                plannedStartDate: input.plannedStartDate,
                                plannedEndDate: input.plannedEndDate,
                              }
                            : resource === "status"
                              ? { version: input.version, status: input.status }
                              : {
                                  assigneeSubjects: input.assigneeSubjects,
                                  version: input.version,
                                }
                    : {
                        title: input.title,
                        description: input.description,
                        perimeter: input.perimeter,
                        assigneeSubjects: input.assigneeSubjects ?? [],
                        validatorSubject: input.validatorSubject ?? null,
                        plannedStartDate: input.plannedStartDate ?? null,
                        plannedEndDate: input.plannedEndDate ?? null,
                      },
      );
      headers["Content-Type"] = "application/json";
    } else if (
      resource === "auditors" ||
      resource === "validators" ||
      resource === "owners"
    ) {
      url.searchParams.set(
        "page",
        new URL(request.url).searchParams.get("page") || "0",
      );
    } else if (!id && !resource) {
      const query = new URL(request.url).searchParams;
      url.searchParams.set("page", query.get("page") || "0");
      url.searchParams.set("size", query.get("size") === "5" ? "5" : "20");
      for (const key of ["q", "status", "owner", "overdue", "sort"]) {
        const value = query.get(key);
        if (value) url.searchParams.set(key, value);
      }
    }
    const response = await fetch(url, {
      method: request.method,
      headers,
      body,
      cache: "no-store",
      signal: AbortSignal.timeout(10_000),
    });
    if (!response.ok)
      return json(
        {
          error:
            response.status === 409
              ? resource === "risks" || resource === "controls"
                ? "Programme verrouillé, limite de 100 éléments atteinte ou fiche modifiée. Actualisez la fiche avant de recommencer."
                : resource === "status" || resource === "schedule"
                  ? "Modification impossible : vérifiez les dates, le statut et la version. Rechargez la fiche avant de recommencer."
                  : "Cette mission a été modifiée. Rechargez la fiche avant de recommencer."
              : response.status === 404
                ? "Mission introuvable ou accès non autorisé"
                : response.status === 400
                  ? "Vérifiez les champs du formulaire"
                  : response.status === 401
                    ? "Votre session a expiré. Reconnectez-vous."
                    : "Impossible de traiter cette demande",
        },
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
