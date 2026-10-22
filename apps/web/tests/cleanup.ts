import { existsSync } from "node:fs";
import { resolve } from "node:path";
import { execFileSync } from "node:child_process";
export default function cleanup() {
  if (existsSync(resolve("../../.local/test-users.json")))
    execFileSync("python3", [resolve("../../scripts/cleanup-test-users.py")], {
      stdio: "inherit",
      timeout: 30000,
    });
  const manifest = resolve("../../.local/browser-test-missions.txt");
  if (existsSync(manifest))
    execFileSync(
      "python3",
      [resolve("../../scripts/cleanup-test-data.py"), manifest],
      { stdio: "inherit", timeout: 120000 },
    );
}
