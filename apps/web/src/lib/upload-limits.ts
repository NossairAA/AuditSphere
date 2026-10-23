// Per-process admission control. Acquire only after session, role and CSRF checks.
const uploads = new Map<string, number>();
let active = 0;
export function acquireUpload(subject: string): (() => void) | null {
  if (active >= 8 || (uploads.get(subject) || 0) >= 2) return null;
  active++;
  uploads.set(subject, (uploads.get(subject) || 0) + 1);
  let released = false;
  return () => {
    if (released) return;
    released = true;
    active--;
    const count = (uploads.get(subject) || 1) - 1;
    if (count) uploads.set(subject, count);
    else uploads.delete(subject);
  };
}

export async function boundedBody(
  request: Request,
  maxBody = 11 * 1024 * 1024,
  timeoutMs = 30_000,
): Promise<Uint8Array> {
  if (Number(request.headers.get("content-length")) > maxBody)
    throw new Error("size");
  const reader = request.body?.getReader();
  if (!reader) throw new Error("invalid");
  let timer: ReturnType<typeof setTimeout> | undefined;
  const deadline = new Promise<never>((_, reject) => {
    timer = setTimeout(() => {
      reject(new Error("timeout"));
      void reader.cancel().catch(() => {});
    }, timeoutMs);
  });
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await Promise.race([reader.read(), deadline]);
      if (done) break;
      length += value.byteLength;
      if (length > maxBody) {
        void reader.cancel().catch(() => {});
        throw new Error("size");
      }
      chunks.push(value);
    }
    const result = new Uint8Array(length);
    let offset = 0;
    for (const chunk of chunks) {
      result.set(chunk, offset);
      offset += chunk.byteLength;
    }
    return result;
  } finally {
    clearTimeout(timer);
    reader.releaseLock();
  }
}
