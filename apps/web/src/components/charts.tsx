"use client";
import Link from "next/link";
import { useState } from "react";

// Rampes ordinales validées sur fond blanc : étapes ordonnées, du plus clair au plus foncé.
export const progressRamp = ["#86b89f", "#5e9f82", "#3a7f62", "#27644f", "#163f31"];
export const severityRamp = ["#eba772", "#d9803f", "#b5551f", "#7f3414"];
export const chartColors = {
  good: "#0ca30c",
  critical: "#d03b3b",
  neutral: "#898781",
  empty: "#c3c2b7",
};

export type Segment = {
  key: string;
  label: string;
  value: number;
  color: string;
  href?: string;
};

const share = (value: number, total: number) =>
  Math.round((value / total) * 100) + " %";

// Barre empilée : la légende porte chaque valeur, l'infobulle ne fait que la compléter.
export function StackedBar({
  label,
  segments,
}: {
  label: string;
  segments: Segment[];
}) {
  const [hovered, setHovered] = useState<string | null>(null);
  const total = segments.reduce((sum, s) => sum + s.value, 0);
  const visible = segments.filter((s) => s.value > 0);
  const active = visible.find((s) => s.key === hovered);
  const before = active
    ? visible
        .slice(0, visible.indexOf(active))
        .reduce((sum, s) => sum + s.value, 0)
    : 0;
  return (
    <div className="chart-stack">
      {total > 0 && (
        <div
          className="chart-bar"
          role="img"
          aria-label={`${label} : ${visible.map((s) => `${s.label} ${s.value}`).join(", ")}`}
          onPointerLeave={() => setHovered(null)}
        >
          {visible.map((s) => (
            <span
              key={s.key}
              className="chart-segment"
              style={{ flexGrow: s.value, background: s.color }}
              onPointerEnter={() => setHovered(s.key)}
            />
          ))}
          {active && (
            <div
              className="chart-tooltip"
              style={{
                left: `${Math.min(75, Math.max(25, ((before + active.value / 2) / total) * 100))}%`,
              }}
            >
              <strong>{active.value}</strong> {active.label} ·{" "}
              {share(active.value, total)}
            </div>
          )}
        </div>
      )}
      <ul className="chart-legend">
        {segments.map((s) => {
          const content = (
            <>
              <span
                className="chart-swatch"
                style={{ background: s.color }}
                aria-hidden="true"
              />
              <span className="chart-legend-label">{s.label}</span>
              <strong>{s.value}</strong>
              <span className="chart-legend-share">
                {total > 0 ? share(s.value, total) : ""}
              </span>
            </>
          );
          return (
            <li key={s.key}>
              {s.href ? <Link href={s.href}>{content}</Link> : <div>{content}</div>}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

// La couleur suit l'effectif de la case, jamais un score de criticité.
const cellColor = (count: number) =>
  count >= 6 ? progressRamp[4] : count >= 4 ? progressRamp[3] : progressRamp[count - 1];

export function RiskMatrix({
  risks,
  probabilities,
  impacts,
}: {
  risks: { title: string; probability: number; impact: number }[];
  probabilities: string[];
  impacts: string[];
}) {
  const columns = probabilities.map((_, index) => index + 1);
  const rows = impacts.map((_, index) => impacts.length - index);
  return (
    <div className="chart-matrix-scroll">
      <table className="chart-matrix">
        <caption>Nombre de risques par impact et par probabilité</caption>
        <thead>
          <tr>
            <td className="chart-matrix-axis">Impact / Probabilité</td>
            {columns.map((p) => (
              <th key={p} scope="col">
                {probabilities[p - 1]}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((i) => (
            <tr key={i}>
              <th scope="row">{impacts[i - 1]}</th>
              {columns.map((p) => {
                const cell = risks.filter(
                  (r) => r.probability === p && r.impact === i,
                );
                return (
                  <td
                    key={p}
                    className={
                      cell.length >= 3
                        ? "chart-cell chart-cell-dark"
                        : "chart-cell"
                    }
                    style={
                      cell.length
                        ? { background: cellColor(cell.length) }
                        : undefined
                    }
                    title={cell.map((r) => r.title).join("\n") || undefined}
                  >
                    {cell.length || ""}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
