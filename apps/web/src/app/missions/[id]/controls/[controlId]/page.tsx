"use client";
import { Suspense, use, useEffect, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import AuditProgram from "@/components/audit-program";
import { ErrorNotice } from "@/components/workspace";
import { type Mission, statusLabel } from "@/lib/types";
function Detail({
  params,
}: {
  params: Promise<{ id: string; controlId: string }>;
}) {
  const { id, controlId } = use(params);
  const query = useSearchParams();
  const requested = query.get("returnTo") || "/missions";
  const returnTo = /^\/missions(?:\/admin)?(?:\?[^#]*)?$/.test(requested)
    ? requested
    : "/missions";
  const [mission, setMission] = useState<Mission | null>(null),
    [error, setError] = useState("");
  useEffect(() => {
    const abort = new AbortController();
    fetch(`/api/missions/${id}`, { signal: abort.signal, cache: "no-store" })
      .then(async (r) => {
        const data = await r.json();
        if (!r.ok) throw Error(data.error);
        setMission(data);
      })
      .catch((e) => {
        if (e.name !== "AbortError") setError(e.message);
      });
    return () => abort.abort();
  }, [id]);
  return (
    <>
      <Link
        className="back-link"
        href={`/missions/${id}?tab=controls&returnTo=${encodeURIComponent(returnTo)}`}
      >
        Retour aux contrôles
      </Link>
      {error ? (
        <ErrorNotice message={error} />
      ) : mission ? (
        <>
          <div className="page-heading">
            <div>
              <span className="eyebrow">
                CONTRÔLE · {statusLabel(mission.status)}
              </span>
              <h1>{mission.title}</h1>
            </div>
          </div>
          <AuditProgram
            mission={mission}
            returnTo={returnTo}
            mode="controls"
            controlId={controlId}
            onUpdated={setMission}
          />
        </>
      ) : (
        <p role="status">Chargement…</p>
      )}
    </>
  );
}

export default function Page({
  params,
}: {
  params: Promise<{ id: string; controlId: string }>;
}) {
  return (
    <Suspense fallback={<p>Chargement…</p>}>
      <Detail params={params} />
    </Suspense>
  );
}
