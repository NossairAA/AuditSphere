"use client";
import { useEffect, useState } from "react";

export default function UnreadCount() {
  const [count, setCount] = useState<number | null>(null);
  useEffect(() => {
    let controller: AbortController | undefined;
    async function refresh() {
      if (document.visibilityState !== "visible") return;
      controller?.abort();
      const current = new AbortController();
      controller = current;
      try {
        const r = await fetch("/api/notifications/unread-count", {
          cache: "no-store",
          signal: AbortSignal.any([current.signal, AbortSignal.timeout(10000)]),
        });
        if (!r.ok) throw new Error("Unavailable count");
        const body = await r.json();
        if (!current.signal.aborted) setCount(body.count);
      } catch {
        if (!current.signal.aborted) setCount(null);
      }
    }
    void refresh();
    const timer = setInterval(refresh, 30000);
    window.addEventListener("notifications-changed", refresh);
    window.addEventListener("focus", refresh);
    document.addEventListener("visibilitychange", refresh);
    return () => {
      controller?.abort();
      clearInterval(timer);
      window.removeEventListener("notifications-changed", refresh);
      window.removeEventListener("focus", refresh);
      document.removeEventListener("visibilitychange", refresh);
    };
  }, []);
  if (count === null || count === 0) return null;
  return (
    <span
      className="notification-badge"
      role="status"
      aria-label={`${count} notifications non lues`}
    >
      {count > 99 ? "99+" : count}
    </span>
  );
}
