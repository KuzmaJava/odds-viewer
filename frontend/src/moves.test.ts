import { describe, expect, it } from "vitest";
import { movesCsv } from "./csv";
import { moveRows } from "./moves";
import type { EventMove, Outcome } from "./types";

const o = (code: string, change: number | null, extra: Partial<Outcome> = {}): Outcome => ({
  code, base: code.split("@")[0], market: "1x2", label: code, point: null, main: true,
  opening: 2, current: 2, change_pct: change, open_ts: "2026-11-14T08:00:00Z", cur_ts: "2026-11-14T11:00:00Z", n: 2, derived: false, ...extra,
});
const ev = (outcomes: Outcome[]): EventMove => ({
  id: "a", sport_key: "s", sport_title: "S", home: "Kosovo", away: "Israel", commence_time: "2099-01-01T00:00:00Z",
  snapshots: 2, first_ts: "2026-11-14T08:00:00Z", last_ts: "2026-11-14T11:00:00Z", outcomes,
});

describe("moveRows (как app/movement.py)", () => {
  const e = ev([o("home", -10), o("draw", 2.86), o("away", 15), o("1X", 0)]);
  it("просадки: только падение от порога, сильнейшее первым", () => {
    expect(moveRows([e], 3, false).map((r) => r.code)).toEqual(["home"]);
  });
  it("повышения: сортировка по убыванию, без «без изменений» при пороге 0", () => {
    expect(moveRows([e], 0, true).map((r) => r.code)).toEqual(["away", "draw"]);
  });
});

describe("movesCsv", () => {
  it("BOM, колонка на каждую линию, порядок рынков", () => {
    const csv = movesCsv([ev([o("over@2.5", 1, { base: "over", market: "total", label: "ТБ 2.5", point: 2.5 }), o("home", -5)])]);
    expect(csv.startsWith("﻿")).toBe(true);
    const header = csv.slice(1).split("\r\n")[0];
    expect(header.indexOf("home открытие")).toBeLessThan(header.indexOf("ТБ 2.5 открытие"));
  });
});
