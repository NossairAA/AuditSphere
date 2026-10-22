import { Suspense } from "react";
import MissionDirectory from "@/components/mission-directory";
export default function Page() {
  return (
    <Suspense fallback={<p>Chargement…</p>}>
      <MissionDirectory />
    </Suspense>
  );
}
