// La gateway limite les appels par utilisateur (TECH-011) : une limite atteinte n'est pas une panne.
export class GatewayLimitError extends Error {
  constructor() {
    super("Gateway rate limit");
  }
}

export function serviceError(error: unknown, message: string, status = 503) {
  if (error instanceof GatewayLimitError)
    return Response.json(
      { error: "Trop de requêtes. Réessayez dans une minute." },
      { status: 429, headers: { "Retry-After": "60", "Cache-Control": "no-store" } },
    );
  return Response.json({ error: message }, { status, headers: { "Cache-Control": "no-store" } });
}
