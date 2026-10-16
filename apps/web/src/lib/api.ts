import "server-only";
import { json, session, validMutation } from "./auth";
export async function missions(request: Request, id?: string) {
  try {
    const data = await session();
    if (!data)
      return json({ error: "Votre session a expiré. Reconnectez-vous." }, 401);
    if (id && !/^[0-9a-f-]{36}$/i.test(id))
      return json({ error: "Mission introuvable" }, 404);
    const url = new URL(
      id ? "/missions/" + id : "/missions",
      process.env.API_ORIGIN || "http://localhost:8080",
    );
    const headers: Record<string, string> = {
      Authorization: "Bearer " + data.accessToken,
    };
    let body: string | undefined;
    if (request.method === "POST") {
      if (!validMutation(request, data, request.headers.get("x-csrf-token")))
        return json({ error: "Requête refusée" }, 403);
      if (!data.roles.includes("AUDIT_MANAGER"))
        return json({ error: "Création réservée aux responsables" }, 403);
      const raw = await request.text();
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
      body = JSON.stringify({
        title: input.title,
        description: input.description,
        perimeter: input.perimeter,
        assigneeSubjects: [],
      });
      headers["Content-Type"] = "application/json";
    } else if (!id) {
      const query = new URL(request.url).searchParams;
      url.searchParams.set("page", query.get("page") || "0");
      url.searchParams.set("size", "20");
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
            response.status === 404
              ? "Mission introuvable ou accès non autorisé"
              : response.status === 400
                ? "Vérifiez les champs du formulaire"
                : response.status === 401
                  ? "Votre session a expiré. Reconnectez-vous."
                  : "Impossible de traiter cette demande",
        },
        response.status,
      );
    return json(await response.json(), response.status);
  } catch {
    return json(
      { error: "Service temporairement indisponible. Réessayez." },
      503,
    );
  }
}
