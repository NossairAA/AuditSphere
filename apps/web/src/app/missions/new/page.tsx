"use client";
import { canManage } from "@/lib/types";
import Link from "next/link";
import { AuditorPicker } from "@/components/assignments";
import { ValidatorPicker } from "@/components/validator-picker";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { ArrowLeft, ArrowRight, ShieldCheck, Check } from "lucide-react";
import { useUser, ErrorNotice } from "@/components/workspace";
export default function NewMission() {
  const user = useUser(),
    router = useRouter(),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [selected, setSelected] = useState<string[]>([]),
    [validator, setValidator] = useState("");
  if (!canManage(user))
    return (
      <div className="panel empty">
        <ShieldCheck size={32} />
        <h1>Création non autorisée</h1>
        <p>La création d’une mission est réservée aux responsables d’audit.</p>
        <Link href="/missions" className="button secondary">
          Retour aux missions
        </Link>
      </div>
    );
  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError("");
    const form = new FormData(event.currentTarget);
    try {
      const response = await fetch("/api/missions", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "x-csrf-token": user!.csrf,
        },
        body: JSON.stringify({
          assigneeSubjects: selected,
          validatorSubject: validator || null,
          title: form.get("title"),
          perimeter: form.get("perimeter"),
          description: form.get("description"),
          plannedStartDate: form.get("plannedStartDate") || null,
          plannedEndDate: form.get("plannedEndDate") || null,
        }),
      });
      const body = await response.json();
      if (!response.ok) throw new Error(body.error);
      router.push("/missions/" + body.id + "?created=1");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Une erreur est survenue");
      setBusy(false);
    }
  }
  return (
    <>
      <Link href="/missions" className="back-link">
        <ArrowLeft size={16} /> Toutes les missions
      </Link>
      <div className="page-heading">
        <div>
          <span className="eyebrow">PRÉPARER UN AUDIT</span>
          <h1>
            Posons le cadre<span className="green">.</span>
          </h1>
          <p>
            Définissez l’essentiel pour donner une direction claire à votre
            mission.
          </p>
        </div>
      </div>
      <div className="form-layout">
        <form className="panel mission-form" onSubmit={submit}>
          <h2>Nouvelle mission</h2>
          <p className="form-intro">
            Titre, périmètre et description sont obligatoires.
          </p>
          {error && <ErrorNotice message={error} />}
          <label htmlFor="title">Titre de la mission</label>
          <input
            id="title"
            name="title"
            required
            maxLength={200}
            placeholder="Ex. Audit du processus achats"
          />
          <label htmlFor="perimeter">Périmètre</label>
          <textarea
            id="perimeter"
            name="perimeter"
            required
            maxLength={2000}
            rows={2}
            placeholder="Équipe, activité ou processus concerné"
          />
          <label htmlFor="description">Objectifs et description</label>
          <textarea
            id="description"
            name="description"
            required
            maxLength={4000}
            rows={6}
            placeholder="Que souhaitez-vous examiner et quels sont les objectifs de cet audit ?"
          />
          <label htmlFor="plannedStartDate">Date de début prévue</label>
          <input
            id="plannedStartDate"
            name="plannedStartDate"
            type="date"
            min="0001-01-01"
            max="9999-12-31"
          />
          <label htmlFor="plannedEndDate">Date de fin prévue</label>
          <input
            id="plannedEndDate"
            name="plannedEndDate"
            type="date"
            min="0001-01-01"
            max="9999-12-31"
          />
          <p>
            Les dates sont facultatives en brouillon. Renseignez les deux ou
            laissez les deux vides.
          </p>
          <AuditorPicker
            selected={selected}
            onChange={setSelected}
            disabled={busy}
          />
          <ValidatorPicker
            selected={validator}
            onChange={setValidator}
            disabled={busy}
          />
          <div className="form-actions">
            <Link href="/missions" className="button secondary">
              Annuler
            </Link>
            <button className="button primary" disabled={busy}>
              {busy ? "Création en cours…" : "Créer la mission"}
              <ArrowRight size={17} />
            </button>
          </div>
        </form>
        <aside className="form-guide">
          <span className="icon-tile">
            <ClipboardIcon />
          </span>
          <h3>Un bon point de départ</h3>
          <p>Une mission précise facilite la préparation et les échanges.</p>
          <ul>
            <li>
              <Check size={17} /> Un titre reconnaissable
            </li>
            <li>
              <Check size={17} /> Un périmètre délimité
            </li>
            <li>
              <Check size={17} /> Des objectifs explicites
            </li>
          </ul>
          <div className="guide-foot">
            La mission sera créée en brouillon.
            <br />
            Vous en serez responsable.
          </div>
        </aside>
      </div>
    </>
  );
}
function ClipboardIcon() {
  return <ShieldCheck size={24} />;
}
