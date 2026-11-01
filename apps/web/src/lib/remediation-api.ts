import "server-only";
import { json, serviceError, session, validMutation } from "./auth";
import { limitedText, BodyError } from "./body";
import { rateLimit, rateLimitAnonymous, rateLimitSession } from "./rate";
import { acquireUpload, boundedBody } from "./upload-limits";
import {
  remediationBody,
  remediationError,
  remediationQuery,
  remediationRoute,
  validVersion,
} from "./remediation-rules";

const maxFile = 10 * 1024 * 1024;
const maxBody = 11 * 1024 * 1024;

export async function remediation(request: Request, segments: string[]) {
  const route = remediationRoute(request.method, segments);
  const upload = route?.kind === "upload";
  const rejection = rateLimit(request, upload ? maxBody : 32768);
  if (rejection) return rejection;
  let releaseUpload: (() => void) | null = null;
  try {
    const data = await session();
    if (!data) {
      const anonymousLimit = rateLimitAnonymous(request);
      if (anonymousLimit) return anonymousLimit;
      return json({ error: "Votre session a expiré. Reconnectez-vous." }, 401);
    }
    const limitedSession = rateLimitSession(data);
    if (limitedSession) return limitedSession;
    if (!route) return json({ error: "Suivi ou action introuvable" }, 404);
    const url = new URL(route.path, process.env.API_ORIGIN || "http://localhost:8080");
    remediationQuery(route.kind, new URL(request.url).searchParams).forEach((value, key) =>
      url.searchParams.set(key, value),
    );
    const headers: Record<string, string> = { Authorization: "Bearer " + data.accessToken };
    let body: string | FormData | undefined;
    let action: unknown;
    if (request.method !== "GET") {
      if (!validMutation(request, data, request.headers.get("x-csrf-token")))
        return json({ error: "Requête refusée" }, 403);
      if (upload) {
        if (!request.headers.get("content-type")?.startsWith("multipart/form-data;"))
          return json({ error: "Formulaire invalide" }, 400);
        releaseUpload = acquireUpload(data.subject);
        if (!releaseUpload)
          return Response.json(
            { error: "Trop de dépôts simultanés" },
            { status: 429, headers: { "Retry-After": "5", "Cache-Control": "no-store" } },
          );
        let bytes: Uint8Array;
        try {
          bytes = await boundedBody(request);
        } catch (error) {
          const reason = error instanceof Error ? error.message : "";
          return json(
            { error: reason === "timeout" ? "Délai de dépôt dépassé" : "Fichier invalide ou trop volumineux" },
            reason === "size" ? 413 : reason === "timeout" ? 408 : 400,
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
          !/^\d{1,15}$/.test(version)
        )
          return json({ error: "Fichier ou version invalide" }, 400);
        body = new FormData();
        body.set("file", file, file.name);
        body.set("version", version);
      } else {
        let input;
        try {
          input = JSON.parse(await limitedText(request));
        } catch (error) {
          if (error instanceof BodyError) throw error;
          return json({ error: "Formulaire invalide" }, 400);
        }
        if (!input || typeof input !== "object" || Array.isArray(input))
          return json({ error: "Formulaire invalide" }, 400);
        action = input.action;
        if (route.kind === "removeAttachment") {
          if (!validVersion(input.version)) return json({ error: "Formulaire invalide" }, 400);
          url.searchParams.set("version", String(input.version));
        } else {
          body = JSON.stringify(remediationBody(route.kind, input));
          headers["Content-Type"] = "application/json";
        }
      }
    }
    const response = await fetch(url, {
      method: request.method,
      headers,
      body,
      cache: "no-store",
      signal: AbortSignal.timeout(upload || route.kind === "download" ? 30_000 : 10_000),
    });
    if (!response.ok)
      return json({ error: remediationError(response.status, route.kind, action) }, response.status);
    if (response.status === 204) return new Response(null, { status: 204 });
    if (route.kind === "download") {
      const download = new Headers({
        "Content-Type": response.headers.get("content-type") || "application/octet-stream",
        "Content-Disposition": response.headers.get("content-disposition") || "attachment",
        "X-Content-Type-Options": "nosniff",
        "Cache-Control": "no-store",
      });
      const length = response.headers.get("content-length");
      if (length) download.set("Content-Length", length);
      return new Response(response.body, { status: response.status, headers: download });
    }
    return json(await response.json(), response.status);
  } catch (error) {
    if (error instanceof BodyError) return json({ error: "Corps de requête refusé" }, error.status);
    return serviceError(error, "Service temporairement indisponible. Réessayez.");
  } finally {
    releaseUpload?.();
  }
}
