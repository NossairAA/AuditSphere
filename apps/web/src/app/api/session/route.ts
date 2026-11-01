import { rateLimit, rateLimitAnonymous, rateLimitSession } from "@/lib/rate";
import { session, json, serviceError } from "@/lib/auth";
export async function GET(request: Request) {
  const rejection = rateLimit(request);
  if (rejection) return rejection;
  try {
    const data = await session();
    const limited = data ? rateLimitSession(data) : rateLimitAnonymous(request);
    if (limited) return limited;
    return data
      ? json({
          subject: data.subject,
          name: data.name,
          roles: data.roles,
          csrf: data.csrf,
        })
      : json({ error: "Session expirée" }, 401);
  } catch (error) {
    return serviceError(error, "Service temporairement indisponible");
  }
}
