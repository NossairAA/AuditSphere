"use client";
import AuditProgram from "@/components/audit-program";
import MissionFindings from "@/components/mission-findings";
import MissionRemediation from "@/components/mission-remediation";
import AdminMissionActions from "@/components/admin-mission-actions";
import { isSuperAdmin } from "@/lib/types";
import { Suspense, use, useEffect, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import MissionValidator from "@/components/mission-validator";
import MissionLifecycle from "@/components/mission-lifecycle";
import { Participants } from "@/components/assignments";
import {
  ArrowLeft,
  ClipboardList,
  CalendarDays,
  ShieldCheck,
  Check,
  Users,
} from "lucide-react";
import { ErrorNotice, useUser } from "@/components/workspace";
import {
  type Mission,
  dateLabel,
  statusLabel,
  plannedDateLabel,
} from "@/lib/types";
function Detail({ params }: { params: Promise<{ id: string }> }) {
  const search = useSearchParams();
  const requestedTab = search.get("tab") || "overview";
  const tab = ["overview", "risks", "controls", "findings", "remediation", "team", "followup"].includes(
    requestedTab,
  )
    ? requestedTab
    : "overview";
  const requestedReturn = search.get("returnTo") || "/missions";
  const returnTo = /^\/missions(?:\/admin)?(?:\?[^#]*)?$/.test(requestedReturn)
    ? requestedReturn
    : "/missions";
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
      <Link href={returnTo} className="back-link">
        <ArrowLeft size={16} />{" "}
        {returnTo.split("?")[0] === "/missions/admin"
          ? "Tableau de bord"
          : "Toutes les missions"}
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
          <div className="page-heading mission-heading">
            <div>
              <span className="eyebrow">FICHE DE MISSION</span>
              <h1>{mission.title}</h1>
              <p>
                <CalendarDays size={16} /> Créée le{" "}
                {dateLabel(mission.createdAt)}
              </p>
            </div>
            <span
              className={`status large mission-status-${mission.status.toLowerCase()}`}
            >
              <span /> {statusLabel(mission.status)}
            </span>
          </div>
          {tab === "overview" && (
            <>
              <ol
                className="mission-progress"
                aria-label="Avancement de la mission"
              >
                {(
                  [
                    "DRAFT",
                    "IN_PROGRESS",
                    "PENDING_REVIEW",
                    "APPROVED",
                    "CLOSED",
                  ] as const
                ).map((status, index, steps) => {
                  const current = steps.indexOf(mission.status);
                  return (
                    <li
                      key={status}
                      className={
                        index < current
                          ? "complete"
                          : index === current
                            ? "current"
                            : ""
                      }
                      aria-current={index === current ? "step" : undefined}
                    >
                      <span className="step-dot">
                        {index < current ? (
                          <Check size={15} aria-hidden="true" />
                        ) : (
                          index + 1
                        )}
                      </span>
                      <span>{statusLabel(status)}</span>
                    </li>
                  );
                })}
              </ol>
              <div className="mission-facts">
                <div>
                  <span>Début prévu</span>
                  <strong>{plannedDateLabel(mission.plannedStartDate)}</strong>
                </div>
                <div>
                  <span>Fin prévue</span>
                  <strong>{plannedDateLabel(mission.plannedEndDate)}</strong>
                </div>
                <div>
                  <span>Équipe d’audit</span>
                  <strong>
                    {mission.assigneeSubjects.length} auditeur
                    {mission.assigneeSubjects.length !== 1 ? "s" : ""}
                  </strong>
                </div>
                <div>
                  <span>Votre rôle sur cette mission</span>
                  <strong>
                    {isSuperAdmin(user)
                      ? "Superadministrateur"
                      : mission.ownerSubject === user?.subject
                        ? "Responsable"
                        : mission.validatorSubject === user?.subject
                          ? "Validateur désigné"
                          : "Auditeur affecté"}
                  </strong>
                </div>
              </div>
            </>
          )}
          {tab === "overview" && mission.adminOverrideAt && (
            <section
              className="notice"
              aria-label="Dernière dérogation administrative"
            >
              <strong>
                Dérogation administrative :{" "}
                {(
                  {
                    APPROVE: "Approbation forcée",
                    CLOSE: "Clôture forcée",
                    REOPEN: "Réouverture",
                    DELETE: "Suppression",
                  } as Record<string, string>
                )[mission.adminOverrideAction || ""] ||
                  mission.adminOverrideAction}
              </strong>
              <p>
                {dateLabel(mission.adminOverrideAt)} ·{" "}
                {mission.adminOverrideReason}
              </p>
            </section>
          )}
          <nav
            className="mission-section-nav"
            aria-label="Sections de la fiche"
          >
            {[
              ["overview", "Vue d’ensemble"],
              ["risks", "Risques"],
              ["controls", "Contrôles"],
              ["findings", "Constats"],
              ["remediation", "Actions correctives"],
              ["team", "Équipe"],
              ["followup", "Suivi"],
            ]
              // Les suivis n'existent qu'après l'approbation de la mission.
              .filter(([key]) => key !== "remediation" || mission.status === "APPROVED" || mission.status === "CLOSED")
              .map(([key, label]) => (
              <Link
                key={key}
                aria-current={tab === key ? "page" : undefined}
                href={`/missions/${id}?tab=${key}&returnTo=${encodeURIComponent(returnTo)}`}
                scroll={false}
              >
                {label}
              </Link>
            ))}
          </nav>
          <div
            className={
              "mission-workspace" +
              (tab !== "overview" ? " mission-single-column" : "")
            }
          >
            <div className="mission-content">
              {tab === "overview" && (
                <section
                  id="mission-cadre"
                  tabIndex={-1}
                  className="panel detail-main"
                  aria-label="Le cadre de la mission"
                >
                  <div className="section-label">
                    <ClipboardList size={20} aria-hidden="true" />
                    <h2>Le cadre de la mission</h2>
                  </div>
                  <h3>Périmètre</h3>
                  <p className="mission-perimeter preserve">
                    {mission.perimeter}
                  </p>
                  <h3>Objectifs et description</h3>
                  <p className="preserve">{mission.description}</p>
                </section>
              )}
              {(tab === "risks" || tab === "controls") && (
                <AuditProgram
                  key={mission.id + tab}
                  returnTo={returnTo}
                  mode={tab as "risks" | "controls"}
                  mission={mission}
                  onUpdated={setMission}
                />
              )}
              {tab === "findings" && (
                <MissionFindings key={mission.id} mission={mission} />
              )}
              {tab === "remediation" && (
                <MissionRemediation key={mission.id} mission={mission} />
              )}
              {tab === "team" && (
                <>
                  <div
                    id="mission-equipe"
                    tabIndex={-1}
                    className="mission-team-heading"
                  >
                    <Users size={20} aria-hidden="true" />
                    <h2>L’équipe et la validation</h2>
                  </div>
                  <p className="mission-section-help">
                    Qui intervient sur la mission et qui approuve sa clôture.
                  </p>
                  <Participants mission={mission} onUpdated={setMission} />
                  <MissionValidator
                    key={"validator-" + mission.version}
                    mission={mission}
                    onUpdated={setMission}
                  />
                </>
              )}
              {tab === "followup" && (
                <section
                  id="mission-suivi"
                  tabIndex={-1}
                  className="panel mission-followup"
                  aria-label="Décisions et suivi"
                >
                  <div className="section-label">
                    <CalendarDays size={20} aria-hidden="true" />
                    <h2>Décisions et suivi</h2>
                  </div>
                  <p className="mission-section-help">
                    Les derniers repères enregistrés pour cette mission.
                  </p>
                  {mission.reviewDecision && (
                    <div
                      className={`mission-decision ${mission.reviewDecision === "APPROVED" ? "approved" : "corrections"}`}
                    >
                      <h3>
                        {mission.reviewDecision === "APPROVED"
                          ? "Mission approuvée"
                          : "Corrections demandées"}
                      </h3>
                      {mission.reviewedAt && (
                        <p className="decision-date">
                          Décision du {dateLabel(mission.reviewedAt)}
                        </p>
                      )}
                      <p className="preserve">{mission.reviewComment}</p>
                    </div>
                  )}
                  {mission.withdrawnAt && (
                    <div className="mission-decision">
                      <h3>Dernier retrait de soumission</h3>
                      <p className="decision-date">
                        Retirée le {dateLabel(mission.withdrawnAt)}
                      </p>
                      <p className="preserve">{mission.withdrawalReason}</p>
                    </div>
                  )}
                  <dl className="mission-dates">
                    <div>
                      <dt>Création</dt>
                      <dd>{dateLabel(mission.createdAt)}</dd>
                    </div>
                    <div>
                      <dt>Démarrage</dt>
                      <dd>
                        {mission.startedAt
                          ? dateLabel(mission.startedAt)
                          : "Pas encore démarrée"}
                      </dd>
                    </div>
                    {mission.submittedAt && (
                      <div>
                        <dt>Dernière soumission</dt>
                        <dd>{dateLabel(mission.submittedAt)}</dd>
                      </div>
                    )}
                    <div>
                      <dt>Clôture</dt>
                      <dd>
                        {mission.closedAt
                          ? dateLabel(mission.closedAt)
                          : "Non clôturée"}
                      </dd>
                    </div>
                  </dl>
                </section>
              )}
            </div>
            {tab === "overview" && (
              <aside
                id="mission-pilotage"
                tabIndex={-1}
                className="mission-actions"
                aria-label="Pilotage de la mission"
              >
                <div className="mission-next-step">
                  <span className="eyebrow">PROCHAINE ÉTAPE</span>
                  <h2>
                    {mission.status === "DRAFT"
                      ? "Préparer le démarrage"
                      : mission.status === "IN_PROGRESS"
                        ? "Préparer la validation"
                        : mission.status === "PENDING_REVIEW"
                          ? "Une décision est attendue"
                          : mission.status === "APPROVED"
                            ? "Prête à être clôturée"
                            : "Mission terminée"}
                  </h2>
                  <p>
                    {mission.status === "DRAFT"
                      ? "Le responsable définit les dates et l’équipe, puis démarre la mission."
                      : mission.status === "IN_PROGRESS"
                        ? "Une fois les travaux terminés, le responsable soumet la mission au validateur désigné."
                        : mission.status === "PENDING_REVIEW"
                          ? mission.validatorSubject === user?.subject
                            ? "Vous pouvez approuver la mission ou demander des corrections ci-dessous."
                            : "Le validateur désigné doit approuver la mission ou demander des corrections."
                          : mission.status === "APPROVED"
                            ? "L’approbation est enregistrée. Le responsable peut maintenant clôturer la mission."
                            : "La mission est clôturée. Vous pouvez consulter son contenu et sa décision de validation."}
                  </p>
                </div>
                <MissionLifecycle
                  key={mission.version}
                  mission={mission}
                  onUpdated={setMission}
                />
                {isSuperAdmin(user) && (
                  <AdminMissionActions
                    mission={mission}
                    onUpdated={setMission}
                  />
                )}
                <p className="mission-privacy">
                  <ShieldCheck size={15} aria-hidden="true" /> Accès contrôlé
                  selon votre rôle
                </p>
              </aside>
            )}
          </div>
        </>
      )}
    </>
  );
}

export default function Page({ params }: { params: Promise<{ id: string }> }) {
  return (
    <Suspense fallback={<p>Chargement…</p>}>
      <Detail params={params} />
    </Suspense>
  );
}
