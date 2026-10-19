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
    let stream: EventSource | undefined;
    let retry: ReturnType<typeof setTimeout> | undefined;
    let disposed = false;
    function connect() {
      stream?.close();
      clearTimeout(retry);
      if (disposed || document.visibilityState !== "visible" || !navigator.onLine) return;
      stream = new EventSource("/api/notifications/stream");
      const currentStream = stream;
      stream.onmessage = () => {
        if (disposed || stream !== currentStream) return;
        void refresh();
        window.dispatchEvent(new Event("notifications-live"));
      };
      stream.onerror = () => {
        if (disposed || stream !== currentStream) return;
        currentStream.close();
        retry = setTimeout(connect, 3000);
      };
    }
    connect();
    document.addEventListener("visibilitychange", connect);
    window.addEventListener("online", connect);
    window.addEventListener("offline", connect);
    void refresh();
    const timer = setInterval(refresh, 30000);
    window.addEventListener("notifications-changed", refresh);
    window.addEventListener("focus", refresh);
    document.addEventListener("visibilitychange", refresh);
    return () => {
      disposed = true;
      window.removeEventListener("online", connect);
      window.removeEventListener("offline", connect);
      controller?.abort();
      stream?.close();
      clearTimeout(retry);
      document.removeEventListener("visibilitychange", connect);
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
