import { appendFileSync, mkdirSync } from "node:fs";
import { resolve } from "node:path";
export function recordMission(id: string) {
  if (!/^[0-9a-f-]{36}$/i.test(id)) throw new Error("Invalid test mission ID");
  mkdirSync(resolve("../../.local"), { recursive: true, mode: 0o700 });
  appendFileSync(resolve("../../.local/browser-test-missions.txt"), id + "\n", {
    mode: 0o600,
  });
}
