import { session, json } from "@/lib/auth";
export async function GET() {
  try {
    const data = await session();
    return data
      ? json({
          subject: data.subject,
          name: data.name,
          roles: data.roles,
          csrf: data.csrf,
        })
      : json({ error: "Session expirée" }, 401);
  } catch {
    return json({ error: "Service temporairement indisponible" }, 503);
  }
}
