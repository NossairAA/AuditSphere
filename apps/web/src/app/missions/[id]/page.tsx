"use client";
import { use, useEffect, useState } from "react";
import Link from "next/link";
import MissionValidator from "@/components/mission-validator";
import MissionLifecycle from "@/components/mission-lifecycle";
import { Participants } from "@/components/assignments";
import {
  ArrowLeft,
  ClipboardList,
  CalendarDays,
  ShieldCheck,
} from "lucide-react";
import { ErrorNotice, useUser } from "@/components/workspace";
import { type Mission, dateLabel, statusLabel } from "@/lib/types";
export default function Detail({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = use(params),
    user = useUser(),
    [mission, setMission] = useState<Mission | null>(null),
    [error, setError] = useState(""),
    [retry, setRetry] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setError("");
    setMission(null);
    fetch("/api/missions/" + id, {
      cache: "no-store",
      signal: controller.signal,
    })
      .then(async (r) => {
        const body = await r.json();
        if (!r.ok) throw new Error(body.error);
        setMission(body);
      })
      .catch((e) => {
        if (e.name !== "AbortError") setError(e.message);
      });
    return () => controller.abort();
  }, [id, retry]);
  return (
    <>
      <Link href="/missions" className="back-link">
        <ArrowLeft size={16} /> Toutes les missions
      </Link>
      {error ? (
        <section className="panel empty">
          <ShieldCheck size={32} />
          <h1>Mission indisponible</h1>
          <ErrorNotice message={error} />
          <button
            className="button secondary"
            onClick={() => setRetry((v) => v + 1)}
          >
            Réessayer
          </button>
        </section>
      ) : !mission ? (
        <div className="loading" role="status">
          Chargement de la mission…
        </div>
      ) : (
        <>
          <div className="page-heading">
            <div>
              <span className="eyebrow">FICHE DE MISSION</span>
              <h1>{mission.title}</h1>
              <p>
                <CalendarDays size={16} /> Créée le{" "}
                {dateLabel(mission.createdAt)}
              </p>
            </div>
            <span className="status large">
              <span /> {statusLabel(mission.status)}
            </span>
          </div>
          <div className="detail-layout">
            <section className="panel detail-main">
              <div className="section-label">
                <ClipboardList size={20} />
                <h2>Le cadre de la mission</h2>
              </div>
              <h3>Périmètre</h3>
              <p className="preserve">{mission.perimeter}</p>
              <hr />
              <h3>Objectifs et description</h3>
              <p className="preserve">{mission.description}</p>
            </section>
            <aside className="panel detail-side">
              <span className="eyebrow">VOTRE PARTICIPATION</span>
              <h3>
                {mission.ownerSubject === user?.subject
                  ? "Responsable de la mission"
                  : mission.validatorSubject === user?.subject
                    ? "Validateur désigné"
                    : "Auditeur affecté"}
              </h3>
              <p>
                {mission.ownerSubject === user?.subject
                  ? "Vous avez créé cette mission et pouvez consulter son périmètre."
                  : "Cette mission fait partie de votre périmètre d’intervention."}
              </p>
              <div className="access-note">
                <ShieldCheck size={18} /> Accès limité aux participants
              </div>
            </aside>
          </div>
          <MissionLifecycle
            key={mission.version}
            mission={mission}
            onUpdated={setMission}
          />
          <MissionValidator
            key={"validator-" + mission.version}
            mission={mission}
            onUpdated={setMission}
          />
          <Participants mission={mission} onUpdated={setMission} />
        </>
      )}
    </>
  );
}
