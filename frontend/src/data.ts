import { moveRows } from "./moves";
import type { EventMove, HistorySnap, MoveRow, SiteData, Sport, Status } from "./types";

// Источник данных:
//  - статический режим (GitHub Pages): рядом со страницей лежит data.json, собранный GitHub Actions;
//  - серверный режим (локально, uvicorn): данные отдаёт FastAPI по /api/...
export interface DataSource {
  isStatic: boolean;
  site: SiteData | null;
  reload(): Promise<void>;
  status(): Promise<Status>;
  events(includeStarted: boolean): Promise<EventMove[]>;
  moves(kind: "drops" | "rises", minPct: number, includeStarted: boolean): Promise<MoveRow[]>;
  history(id: string): Promise<HistorySnap[]>;
  sports(): Promise<Sport[]>;
  setTracked(sports: string[]): Promise<void>;
  pollNow(): Promise<void>;
}

async function fetchSite(): Promise<SiteData | null> {
  try {
    const r = await fetch("data.json", { cache: "no-cache" });
    if (!r.ok || !(r.headers.get("content-type") || "").includes("json")) return null;
    return (await r.json()) as SiteData;
  } catch {
    return null;
  }
}

async function apiGet<T>(path: string, init?: RequestInit): Promise<T> {
  const r = await fetch(path, init);
  const body = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error((body as { detail?: string }).detail || `HTTP ${r.status}`);
  return body as T;
}

const upcoming = (events: EventMove[], includeStarted: boolean) => {
  const now = new Date().toISOString();
  return events.filter((e) => includeStarted || e.commence_time > now);
};

export async function createDataSource(): Promise<DataSource> {
  const site = await fetchSite();
  if (site) {
    const src: DataSource = {
      isStatic: true,
      site,
      async reload() { src.site = (await fetchSite()) ?? src.site; },
      async status() { return src.site!.status; },
      async events(inc) { return upcoming(src.site!.events, inc); },
      async moves(kind, min, inc) { return moveRows(upcoming(src.site!.events, inc), min, kind === "rises"); },
      async history(id) { return src.site!.history[id] ?? []; },
      async sports() { return src.site!.sports ?? []; },
      async setTracked() { throw new Error("в статической версии лиги сохраняются через GitHub"); },
      async pollNow() { throw new Error("в статической версии опрос запускается через GitHub"); },
    };
    return src;
  }
  return {
    isStatic: false,
    site: null,
    async reload() {},
    status: () => apiGet<Status>("/api/status"),
    events: (inc) => apiGet<EventMove[]>(`/api/events?include_started=${inc}`),
    moves: (kind, min, inc) => apiGet<MoveRow[]>(`/api/${kind}?min_pct=${min}&include_started=${inc}`),
    history: async (id) => (await apiGet<{ history: HistorySnap[] }>(`/api/events/${encodeURIComponent(id)}/history`)).history,
    sports: () => apiGet<Sport[]>("/api/sports"),
    async setTracked(sports) {
      await apiGet("/api/tracked", { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ sports }) });
    },
    async pollNow() { await apiGet("/api/poll", { method: "POST" }); },
  };
}
