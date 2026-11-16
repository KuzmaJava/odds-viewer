import type { EventMove, Market, Outcome } from "./types";

// Колонки таблицы и порядок исходов: [базовый код, подпись, рынок]
export const COLS: [string, string, Market][] = [
  ["home", "П1", "1x2"], ["draw", "X", "1x2"], ["away", "П2", "1x2"],
  ["1X", "1X", "dc"], ["12", "12", "dc"], ["X2", "X2", "dc"],
  ["over", "ТБ", "total"], ["under", "ТМ", "total"],
  ["ah_home", "Ф1", "spread"], ["ah_away", "Ф2", "spread"],
  ["it1_over", "ИТ1Б", "team_total"], ["it1_under", "ИТ1М", "team_total"],
  ["it2_over", "ИТ2Б", "team_total"], ["it2_under", "ИТ2М", "team_total"],
];
export const MARKET_NAMES: Record<Market, string> = {
  "1x2": "1X2", dc: "Двойной шанс", total: "Тотал", spread: "Фора", team_total: "Инд. тотал",
};
export const labelOfBase = (base: string) => COLS.find((c) => c[0] === base)?.[1] ?? base;

// Пары исходов одной линии: [A, B, знак линии B относительно A, название]
export const PAIRS: [string, string, 1 | -1, string][] = [
  ["over", "under", 1, "Тотал"],
  ["ah_home", "ah_away", -1, "Фора П1 / П2"],
  ["it1_over", "it1_under", 1, "ИТ1"],
  ["it2_over", "it2_under", 1, "ИТ2"],
];

export const lineOf = (code: string): [string, number | null] => {
  const [b, pt] = code.split("@");
  return [b, pt == null ? null : Number(pt)];
};
export const lineCode = (base: string, pt: number) => `${base}@${pt + 0}`;

// Линия для показа: фора со знаком "+", минус — типографский
export const fmtPt = (base: string, pt: number | null | undefined) =>
  pt == null ? "" : base.startsWith("ah_") && pt > 0 ? "+" + pt : String(pt).replace("-", "−");

// Колонки, по которым есть данные хотя бы у одного матча (инд. тоталы Pinnacle на футбол обычно не даёт)
export function visibleCols(events: EventMove[]) {
  const have = new Set(events.flatMap((e) => e.outcomes.map((o) => o.base)));
  const always = new Set(["home", "draw", "away", "1X", "12", "X2"]);
  return COLS.filter(([code]) => always.has(code) || have.has(code));
}

// В таблице «Матчи» по каждому рынку — основная линия
export const mainByBase = (ev: EventMove): Record<string, Outcome> =>
  Object.fromEntries(ev.outcomes.filter((o) => o.main).map((o) => [o.base, o]));
