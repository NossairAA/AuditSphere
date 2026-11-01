"use client";
import { useRef } from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";

// Pages shown: first, last, current with its neighbours, and a gap for each hidden run of two pages or more.
export function pageItems(page: number, count: number): (number | null)[] {
  const items: (number | null)[] = [];
  for (let i = 0; i < count; i++) {
    const near = Math.abs(i - page) <= 1 || (Math.abs(i - page) === 2 && (i === 1 || i === count - 2));
    if (i === 0 || i === count - 1 || near) items.push(i);
    else if (items[items.length - 1] !== null) items.push(null);
  }
  return items;
}

// Table footer with the visible range and a numbered navigation. Pages start at 0, like the API.
export function Pagination({
  page,
  size,
  total,
  unit,
  onPage,
}: {
  page: number;
  size: number;
  total: number | undefined;
  unit: [string, string];
  onPage: (page: number) => void;
}) {
  // Keeps the last known total while the next page loads, so the navigation does not jump.
  const known = useRef(total);
  if (total !== undefined) known.current = total;
  const loading = total === undefined;
  const count = Math.max(1, Math.ceil((known.current ?? 0) / size));
  const shown = known.current ?? 0;
  const first = Math.min(shown, page * size + 1);
  const last = Math.min(shown, (page + 1) * size);
  const label = shown === 1 ? unit[0] : unit[1];
  return (
    <div className="table-footer pagination">
      <span>
        {known.current === undefined ? "Chargement…" : shown ? `${first} à ${last} sur ${shown} ${label}` : `0 ${label}`}
      </span>
      {count > 1 && (
        <nav aria-label="Pagination">
          <button
            className="button secondary compact"
            aria-label="Page précédente"
            disabled={loading || page === 0}
            onClick={() => onPage(page - 1)}
          >
            <ChevronLeft size={16} aria-hidden="true" />
          </button>
          {pageItems(page, count).map((item, index) =>
            item === null ? (
              <span key={"gap" + index} className="pagination-gap" aria-hidden="true">
                …
              </span>
            ) : (
              <button
                key={item}
                className="button secondary compact pagination-page"
                aria-label={`Page ${item + 1}`}
                aria-current={item === page ? "page" : undefined}
                disabled={loading && item !== page}
                onClick={() => item !== page && onPage(item)}
              >
                {item + 1}
              </button>
            ),
          )}
          <span className="pagination-status">
            Page {page + 1} sur {count}
          </span>
          <button
            className="button secondary compact"
            aria-label="Page suivante"
            disabled={loading || page >= count - 1}
            onClick={() => onPage(page + 1)}
          >
            <ChevronRight size={16} aria-hidden="true" />
          </button>
        </nav>
      )}
    </div>
  );
}
