// Формат data.json и ответов /api/* (контракт с бэкендом, см. app/export.py и app/movement.py)

export type Market = "1x2" | "dc" | "total" | "spread" | "team_total";

export interface Outcome {
  code: string;          // "home", "1X", "over@2.5", "ah_home@-1"
  base: string;          // "home", "over", "ah_home"
  market: Market;
  label: string;         // "П1", "ТБ 2.5", "Ф1 +0.5"
  point: number | null;
  main: boolean;         // основная линия рынка (для 1X2/ДШ всегда true)
  open_main_point?: number | null;
  opening: number | null;
  current: number | null;
  change_pct: number | null;
  open_ts: string;
  cur_ts: string;
  n: number;
  derived: boolean;      // двойной шанс частично рассчитан из 1X2
}

export interface EventMove {
  id: string;
  sport_key: string;
  sport_title: string;
  home: string;
  away: string;
  commence_time: string;
  snapshots: number;
  first_ts: string;
  last_ts: string;
  opened_at?: string | null;
  closed_at?: string | null;
  outcomes: Outcome[];
}

export interface MoveRow extends Outcome {
  event_id: string;
  sport_key?: string;
  sport_title: string;
  home: string;
  away: string;
  commence_time: string;
  snapshots: number;
  first_ts: string;
  last_ts: string;
}

// Снимок истории: код исхода -> [коэффициент, линия]
export interface HistorySnap { ts: string; p: Record<string, [number, number | null] | [number]> }

export interface Policy {
  close_window_min?: number;
  league_mode?: boolean;
  league_snapshot_markets?: string;
  lines_far_hours?: number;
  lines_near_hours?: number;
  lines_near_window_hours?: number;
  summary?: string;   // расписание по лигам одной строкой (Java-трекер)
}

export interface Status {
  demo: boolean;
  tracked_sports: string[];
  policy: Policy;
  last_poll_at: string | null;
  next_poll_at: string | null;
  last_error: string | null;
  quota: Quota;
  quotas?: Record<string, Quota>;
  schedule?: LeagueSchedule[];      // ближайшие обновления по лигам (Java-трекер)   // по ключам: "default" или ключ лиги (если у лиг свои ключи API)
}

// Когда у лиги по расписанию обновятся коэффициенты
export interface LeagueSchedule {
  sport: string;
  matches: number;              // предстоящих матчей без закрытия
  last_at?: string | null;      // последний снимок коэффициентов лиги (любой)
  last_lines_at?: string | null; // последний снимок всех тоталов и фор
  h2h_at: string | null;        // 1X2 запросом по лиге
  lines_at: string | null;      // все тоталы и форы по матчу
  lines_point: string | null;   // "открытие", "за 24 ч", …
  lines_match: string | null;
  close_at: string | null;      // закрытие (за час до старта)
  close_match: string | null;
}

export interface Quota { remaining?: string | null; used?: string | null; last_cost?: string | null }

export interface Sport { key: string; title: string; group?: string; active?: boolean }

export interface SiteData {
  generated_at: string;
  repo: string | null;
  ref: string | null;
  status: Status;
  events: EventMove[];
  history: Record<string, HistorySnap[]>;
  sports: Sport[];
}

// Архив прошедших матчей (archive/index.json и archive/ГГГГ-ММ.json, Java-трекер)
export interface ArchiveEntry {
  id: string;
  sport_key: string;
  sport_title: string;
  home: string;
  away: string;
  commence_time: string;
  month: string;                                       // "2026-11"
  x?: Record<string, [number | null, number | null]>;  // 1X2: код -> [открытие, закрытие]
}
export interface ArchiveIndex { generated_at: string; months: { month: string; count: number }[]; events: ArchiveEntry[] }
export interface ArchiveMonth { events: EventMove[]; history: Record<string, HistorySnap[]> }
