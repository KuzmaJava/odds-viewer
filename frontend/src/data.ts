import { moveRows } from "./moves";
import type { ArchiveIndex, ArchiveMonth, EventMove, HistorySnap, MoveRow, SiteData, Sport, Status } from "./types";

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
  archiveIndex(): Promise<ArchiveIndex | null>;   // null — архива нет (серверный режим или ещё пуст)
  archiveMonth(month: string): Promise<ArchiveMonth>;
}

// Архив: индекс и файлы месяцев грузятся по требованию и кэшируются до перезагрузки страницы (меняются раз в несколько часов)
function archiveLoader() {
  let index: Promise<ArchiveIndex | null> | null = null;
  const months = new Map<string, Promise<ArchiveMonth>>();
  const get = async <T,>(path: string): Promise<T | null> => {
    const r = await fetch(path, { cache: "no-cache" });
    if (!r.ok || !(r.headers.get("content-type") || "").includes("json")) return null;
    return (await r.json()) as T;
  };
  const self = {
    index(): Promise<ArchiveIndex | null> {
      if (!index) index = get<ArchiveIndex>("archive/index.json").catch(() => null);
      return index;
    },
    month(m: string): Promise<ArchiveMonth> {
      if (!/^\d{4}-\d{2}$/.test(m)) return Promise.reject(new Error("некорректный месяц " + m));
      let p = months.get(m);
      if (!p) {
        p = get<ArchiveMonth>(`archive/${m}.json`).then((x) => {
          if (!x) throw new Error(`архив за ${m} не найден`);
          return x;
        });
        p.catch(() => months.delete(m));
        months.set(m, p);
      }
      return p;
    },
    async history(id: string): Promise<HistorySnap[] | null> {
      const e = (await self.index())?.events.find((x) => x.id === id);
      return e ? (await self.month(e.month)).history[id] ?? null : null;
    },
  };
  return self;
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
    const arch = archiveLoader();
    const src: DataSource = {
      isStatic: true,
      site,
      async reload() { src.site = (await fetchSite()) ?? src.site; },
      async status() { return src.site!.status; },
      async events(inc) { return upcoming(src.site!.events, inc); },
      async moves(kind, min, inc) { return moveRows(upcoming(src.site!.events, inc), min, kind === "rises"); },
      async history(id) { return src.site!.history[id] ?? (await arch.history(id)) ?? []; },
      async sports() { return src.site!.sports ?? []; },
      async setTracked() { throw new Error("в статической версии лиги сохраняются через GitHub"); },
      async pollNow() { throw new Error("в статической версии опрос запускается через GitHub"); },
      archiveIndex: () => arch.index(),
      archiveMonth: (m) => arch.month(m),
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
    async archiveIndex() { return null; },
    async archiveMonth() { throw new Error("архив есть только в версии на GitHub Pages"); },
  };
}
