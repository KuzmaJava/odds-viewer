import { localDT } from "./format";
import { COLS } from "./markets";
import type { EventMove, Outcome } from "./types";

const cell = (v: unknown) => {
  if (v == null) return "";
  const s = String(v);
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
};

// CSV списка «Матчи»: по колонке на каждый исход каждой линии (открытие, сейчас, изменение %)
export function movesCsv(events: EventMove[]): string {
  const order = Object.fromEntries(COLS.map(([c], i) => [c, i]));
  const codes = new Map<string, Outcome>();
  events.forEach((ev) => ev.outcomes.forEach((o) => codes.set(o.code, o)));
  const cols = [...codes.values()].sort((a, b) => order[a.base] - order[b.base] || (a.point ?? 0) - (b.point ?? 0));
  const header = ["Начало", "Лига", "Хозяева", "Гости", "Снимков", "Первый снимок", "Последний снимок",
    ...cols.flatMap((c) => [`${c.label} открытие`, `${c.label} сейчас`, `${c.label} изменение %`])];
  const rows = events.map((ev) => {
    const by = Object.fromEntries(ev.outcomes.map((o) => [o.code, o]));
    return [localDT(ev.commence_time), ev.sport_title, ev.home, ev.away, ev.snapshots, localDT(ev.first_ts), localDT(ev.last_ts),
      ...cols.flatMap((c) => { const o = by[c.code]; return [o?.opening, o?.current, o?.change_pct]; })];
  });
  return "﻿" + [header, ...rows].map((r) => r.map(cell).join(",")).join("\r\n"); // BOM — для кириллицы в Excel
}

export function downloadCsv(text: string, name: string) {
  const url = URL.createObjectURL(new Blob([text], { type: "text/csv;charset=utf-8" }));
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
