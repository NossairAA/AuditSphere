import "server-only";
import { json, session, validMutation } from "./auth";
import { rateLimit } from "./rate";

const maxFile = 10 * 1024 * 1024;
const maxBody = 11 * 1024 * 1024;
const uuid = /^[0-9a-f-]{36}$/i;

async function boundedBody(request: Request): Promise<Uint8Array> {
  if (Number(request.headers.get("content-length")) > maxBody)
    throw new Error("size");
  const reader = request.body?.getReader();
  if (!reader) throw new Error("invalid");
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > maxBody) {
        await reader.cancel();
        throw new Error("size");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const result = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return result;
}

export async function evidence(
  request: Request,
  missionId: string,
  controlId: string,
  evidenceId?: string,
) {
  const rejection = rateLimit(
    request,
    request.method === "POST" ? maxBody : 32768,
  );
  if (rejection) return rejection;
  if (
    ![missionId, controlId, evidenceId]
      .filter(Boolean)
      .every((id) => uuid.test(id!))
  )
    return json({ error: "Preuve introuvable" }, 404);
  try {
    const data = await session();
    if (!data)
      return json({ error: "Votre session a expiré. Reconnectez-vous." }, 401);
    const upload = request.method === "POST";
    if (upload) {
      if (!validMutation(request, data, request.headers.get("x-csrf-token")))
        return json({ error: "Requête refusée" }, 403);
      if (
        !data.roles.includes("SUPER_ADMIN") &&
        !data.roles.includes("AUDITOR")
      )
        return json({ error: "Votre rôle ne permet pas cette action" }, 403);
      if (
        !request.headers.get("content-type")?.startsWith("multipart/form-data;")
      )
        return json({ error: "Formulaire invalide" }, 400);
    }
    const url = new URL(
      `/missions/${missionId}/controls/${controlId}/evidence${evidenceId ? "/" + evidenceId : ""}`,
      process.env.API_ORIGIN || "http://localhost:8080",
    );
    let body: FormData | undefined;
    if (upload) {
      let bytes: Uint8Array;
      try {
        bytes = await boundedBody(request);
      } catch (error) {
        return json(
          { error: "Fichier trop volumineux" },
          error instanceof Error && error.message === "size" ? 413 : 400,
        );
      }
      const parsed = await new Request("http://localhost/", {
        method: "POST",
        headers: { "content-type": request.headers.get("content-type")! },
        body: Buffer.from(bytes),
      }).formData();
      const file = parsed.get("file");
      const version = parsed.get("version");
      if (
        !(file instanceof File) ||
        file.size < 1 ||
        file.size > maxFile ||
        !["application/pdf", "image/jpeg", "image/png"].includes(file.type) ||
        typeof version !== "string" ||
        !/^\d+$/.test(version)
      )
        return json({ error: "Fichier ou version invalide" }, 400);
      body = new FormData();
      body.set("file", file, file.name);
      body.set("version", version);
    }
    const response = await fetch(url, {
      method: request.method,
      headers: { Authorization: "Bearer " + data.accessToken },
      body,
      cache: "no-store",
      signal: AbortSignal.timeout(30_000),
    });
    if (!response.ok)
      return json(
        {
          error:
            response.status === 409
              ? "La mission a changé ou les preuves sont verrouillées. Actualisez la fiche."
              : response.status === 404
                ? "Preuve introuvable ou accès non autorisé"
                : response.status === 413
                  ? "Fichier trop volumineux"
                  : response.status === 400
                    ? "Seuls les fichiers PDF, JPEG et PNG valides sont acceptés"
                    : "Service temporairement indisponible",
        },
        response.status,
      );
    if (evidenceId) {
      const headers = new Headers({
        "Content-Type":
          response.headers.get("content-type") || "application/octet-stream",
        "Content-Disposition":
          response.headers.get("content-disposition") || "attachment",
        "X-Content-Type-Options": "nosniff",
        "Cache-Control": "no-store",
      });
      const length = response.headers.get("content-length");
      if (length) headers.set("Content-Length", length);
      return new Response(response.body, { status: response.status, headers });
    }
    return json(await response.json(), response.status);
  } catch {
    return json(
      { error: "Service temporairement indisponible. Réessayez." },
      503,
    );
  }
}
