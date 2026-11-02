"use client";
import { useEffect, useState, type ReactNode } from "react";
import { impacts, probabilities } from "./audit-program";
import {
  chartColors,
  progressRamp,
  RiskMatrix,
  severityRamp,
  StackedBar,
} from "./charts";
import { severityLabels } from "./mission-findings";
import { ErrorNotice } from "./workspace";
import {
  actionStatusLabels,
  readJson,
  type ActionStatus,
  type MissionFollowUps,
} from "@/lib/remediation";
import type { Mission } from "@/lib/types";

type Program = {
  risks: { id: string; title: string; probability: number; impact: number }[];
  controls: {
    riskIds: string[];
    result: { outcome: "CONFORMING" | "NON_CONFORMING" | "NOT_APPLICABLE" } | null;
  }[];
};
type Finding = {
  severity: keyof typeof severityLabels;
  status: string;
};
type Indicators = {
  program: Program | null;
  findings: Finding[] | null;
  followUps: MissionFollowUps | null;
};

const severities = ["LOW", "MODERATE", "HIGH", "CRITICAL"] as const;
const actionColors: Record<ActionStatus, string> = {
  TODO: progressRamp[0],
  IN_PROGRESS: progressRamp[1],
  DONE: progressRamp[3],
  VERIFIED: progressRamp[4],
  CANCELLED: chartColors.empty,
};

function Indicator({
  title,
  help,
  children,
}: {
  title: string;
  help?: string;
  children: ReactNode;
}) {
  return (
    <section className="panel" aria-label={title}>
      <div className="panel-title">
        <h2>{title}</h2>
      </div>
      {help && <p className="dashboard-description">{help}</p>}
      <div className="chart-panel-body">{children}</div>
    </section>
  );
}

const unavailable = (
  <p className="chart-note">Cet indicateur est temporairement indisponible.</p>
);

export default function MissionIndicators({ mission }: { mission: Mission }) {
  const [data, setData] = useState<Indicators | null>(null);
  const [retry, setRetry] = useState(0);
  // Les suivis n'existent qu'après l'approbation de la mission.
  const followed = mission.status === "APPROVED" || mission.status === "CLOSED";
  useEffect(() => {
    const abort = new AbortController();
    const load = <T,>(url: string) =>
      fetch(url, { cache: "no-store", signal: abort.signal })
        .then((r) => readJson<T>(r))
        .catch((): T | null => null);
    setData(null);
    Promise.all([
      load<Program>(`/api/missions/${mission.id}/program`),
      load<Finding[]>(`/api/missions/${mission.id}/findings`),
      followed
        ? load<MissionFollowUps>(
            `/api/remediation/missions/${mission.id}/follow-ups`,
          )
        : Promise.resolve(null),
    ]).then(([program, findings, followUps]) => {
      if (!abort.signal.aborted) setData({ program, findings, followUps });
    });
    return () => abort.abort();
  }, [mission.id, mission.version, followed, retry]);

  if (!data)
    return (
      <p className="panel panel-message" role="status">
        Chargement des indicateurs…
      </p>
    );

  const { program, findings, followUps } = data;
  const controls = program?.controls ?? [];
  const outcome = (value: string | null) =>
    controls.filter((c) => (c.result?.outcome ?? null) === value).length;
  const risks = program?.risks ?? [];
  const uncovered = risks.filter(
    (r) => !controls.some((c) => c.riskIds.includes(r.id)),
  ).length;
  const retained = findings?.filter((f) => f.status !== "REJECTED") ?? [];
  const actions =
    followUps?.followUps
      .filter((f) => f.status !== "WITHDRAWN")
      .flatMap((f) => f.actions) ?? [];
  const overdue = actions.filter((a) => a.overdue).length;
  const failed = !program || !findings || (followed && !followUps);

  return (
    <div className="indicators">
      {failed && (
        <section className="panel panel-message">
          <ErrorNotice message="Certains indicateurs n'ont pas pu être chargés." />
          <button
            className="button secondary"
            onClick={() => setRetry((n) => n + 1)}
          >
            Réessayer
          </button>
        </section>
      )}
      <div className="indicators-grid">
        <Indicator title="Résultats des contrôles">
          {!program ? (
            unavailable
          ) : !controls.length ? (
            <p className="chart-note">Aucun contrôle dans le programme.</p>
          ) : (
            <StackedBar
              label="Résultats des contrôles"
              segments={[
                { key: "ok", label: "Conforme", value: outcome("CONFORMING"), color: chartColors.good },
                { key: "ko", label: "Non conforme", value: outcome("NON_CONFORMING"), color: chartColors.critical },
                { key: "na", label: "Non applicable", value: outcome("NOT_APPLICABLE"), color: chartColors.neutral },
                { key: "none", label: "Sans résultat", value: outcome(null), color: chartColors.empty },
              ]}
            />
          )}
        </Indicator>
        <Indicator
          title="Constats par gravité"
          help="Les constats rejetés ne sont pas comptés."
        >
          {!findings ? (
            unavailable
          ) : !retained.length ? (
            <p className="chart-note">Aucun constat pour cette mission.</p>
          ) : (
            <StackedBar
              label="Constats par gravité"
              segments={severities.map((severity, index) => ({
                key: severity,
                label: severityLabels[severity],
                value: retained.filter((f) => f.severity === severity).length,
                color: severityRamp[index],
              }))}
            />
          )}
        </Indicator>
        <Indicator
          title="Matrice des risques"
          help="La couleur indique le nombre de risques de la case, pas leur criticité."
        >
          {!program ? (
            unavailable
          ) : !risks.length ? (
            <p className="chart-note">Aucun risque dans le programme.</p>
          ) : (
            <>
              <RiskMatrix
                risks={risks}
                probabilities={probabilities}
                impacts={impacts}
              />
              <p className="chart-note">
                {uncovered === 0
                  ? "Tous les risques sont couverts par au moins un contrôle."
                  : uncovered === 1
                    ? "1 risque n'est couvert par aucun contrôle."
                    : `${uncovered} risques ne sont couverts par aucun contrôle.`}
              </p>
            </>
          )}
        </Indicator>
        {followed && (
          <Indicator title="Actions correctives">
            {!followUps ? (
              unavailable
            ) : !actions.length ? (
              <p className="chart-note">Aucune action corrective enregistrée.</p>
            ) : (
              <>
                <StackedBar
                  label="Actions correctives par statut"
                  segments={(Object.keys(actionColors) as ActionStatus[]).map(
                    (status) => ({
                      key: status,
                      label: actionStatusLabels[status],
                      value: actions.filter((a) => a.status === status).length,
                      color: actionColors[status],
                    }),
                  )}
                />
                {overdue > 0 && (
                  <p className="chart-note">
                    {overdue === 1
                      ? "1 action est en retard."
                      : `${overdue} actions sont en retard.`}
                  </p>
                )}
              </>
            )}
          </Indicator>
        )}
      </div>
    </div>
  );
}
