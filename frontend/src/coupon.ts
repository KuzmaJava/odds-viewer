import { useSyncExternalStore } from "react";
import type { EventMove, Outcome } from "./types";

// Купон: выбранные исходы матчей. Хранится в браузере (localStorage) — у каждого посетителя свой.
// Вместе с ключом запоминаем матч и коэффициент в момент добавления: так видно движение «с моего выбора»,
// и выбор не теряется, если матч уже выпал из данных.
export interface Pick {
  event_id: string;
  code: string;
  label: string;
  home: string;
  away: string;
  sport_key: string;
  sport_title: string;
  commence_time: string;
  added_at: string;
  added_price: number | null;
}

const KEY = "coupon";
const listeners = new Set<() => void>();
let picks: Pick[] = read();

function read(): Pick[] {
  try {
    const v = JSON.parse(localStorage.getItem(KEY) || "[]");
    return Array.isArray(v) ? v : [];
  } catch { return []; }
}

function write(next: Pick[]) {
  picks = next;
  try { localStorage.setItem(KEY, JSON.stringify(next)); } catch { /* приватное окно — купон живёт до перезагрузки */ }
  listeners.forEach((l) => l());
}

// Купон, открытый в другой вкладке, тоже обновится
if (typeof window !== "undefined") {
  window.addEventListener("storage", (e) => { if (e.key === KEY) { picks = read(); listeners.forEach((l) => l()); } });
}

export const pickKey = (eventId: string, code: string) => `${eventId}|${code}`;

export function getPicks(): Pick[] { return picks; }

export function hasPick(eventId: string, code: string) {
  return picks.some((p) => p.event_id === eventId && p.code === code);
}

export interface MatchMeta {
  id: string; home: string; away: string; sport_key?: string; sport_title: string; commence_time: string;
}

// Добавить исход в купон или убрать, если он уже там. true — добавлен.
export function togglePick(ev: MatchMeta, o: Outcome, now = new Date().toISOString()) {
  const eventId = ev.id;
  if (hasPick(eventId, o.code)) { removePick(eventId, o.code); return false; }
  write([...picks, {
    event_id: eventId, code: o.code, label: o.label, home: ev.home, away: ev.away,
    sport_key: ev.sport_key ?? "", sport_title: ev.sport_title, commence_time: ev.commence_time,
    added_at: now, added_price: o.current,
  }]);
  return true;
}

export function removePick(eventId: string, code: string) {
  write(picks.filter((p) => !(p.event_id === eventId && p.code === code)));
}

export function clearPicks() { write([]); }

export function useCoupon(): Pick[] {
  return useSyncExternalStore((l) => { listeners.add(l); return () => listeners.delete(l); }, getPicks);
}

const pct = (from: number | null | undefined, to: number | null | undefined) =>
  from && to ? Math.round((to - from) / from * 10000) / 100 : null;

export interface CouponRow {
  pick: Pick;
  outcome: Outcome | null;     // null — матча или исхода больше нет в данных
  ev: EventMove | null;
  sinceAdded: number | null;   // % от коэффициента при добавлении
}

export function couponRows(list: Pick[], events: EventMove[]): CouponRow[] {
  const byId = new Map(events.map((e) => [e.id, e]));
  return list.map((pick) => {
    const ev = byId.get(pick.event_id) ?? null;
    const outcome = ev?.outcomes.find((o) => o.code === pick.code) ?? null;
    return { pick, outcome, ev, sinceAdded: pct(pick.added_price, outcome?.current) };
  });
}

// Экспресс из всех исходов: произведение коэффициентов на открытии, при добавлении и сейчас
export function expressTotals(rows: CouponRow[]) {
  const prod = (f: (r: CouponRow) => number | null | undefined) => {
    let x = 1;
    for (const r of rows) { const v = f(r); if (!v) return null; x *= v; }
    return rows.length ? x : null;
  };
  const opening = prod((r) => r.outcome?.opening), added = prod((r) => r.pick.added_price), current = prod((r) => r.outcome?.current);
  return { opening, added, current, change: pct(opening, current), sinceAdded: pct(added, current) };
}
