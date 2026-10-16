import "server-only";
export class BodyError extends Error {
  constructor(public status: number) {
    super("Invalid request body");
  }
}
export async function limitedText(
  request: Request,
  limit = 32768,
): Promise<string> {
  const length = request.headers.get("content-length");
  if (length && (!/^\d+$/.test(length) || Number(length) > limit))
    throw new BodyError(413);
  const reader = request.body?.getReader();
  if (!reader) return "";
  const chunks: Uint8Array[] = [];
  let bytes = 0;
  const deadline = setTimeout(() => {
    void reader.cancel().catch(() => {});
  }, 5000);
  const started = Date.now();
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (Date.now() - started >= 5000) throw new BodyError(408);
      if (done) break;
      bytes += value.byteLength;
      if (bytes > limit) {
        await reader.cancel();
        throw new BodyError(413);
      }
      chunks.push(value);
    }
    return Buffer.concat(chunks).toString("utf8");
  } finally {
    clearTimeout(deadline);
    reader.releaseLock();
  }
}
