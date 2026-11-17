import { beforeEach, describe, expect, it } from "vitest";
import { clearPicks, couponRows, expressTotals, getPicks, hasPick, togglePick } from "./coupon";
import type { EventMove, Outcome } from "./types";

const o = (code: string, opening: number, current: number): Outcome => ({
  code, base: code.split("@")[0], market: "1x2", label: code, point: null, main: true,
  opening, current, change_pct: null, open_ts: "2026-11-14T08:00:00Z", cur_ts: "2026-11-14T11:00:00Z", n: 2, derived: false,
});
const ev = (id: string, outcomes: Outcome[]): EventMove => ({
  id, sport_key: "soccer_epl", sport_title: "EPL", home: "A" + id, away: "B" + id, commence_time: "2099-01-01T00:00:00Z",
  snapshots: 2, first_ts: "2026-11-14T08:00:00Z", last_ts: "2026-11-14T11:00:00Z", outcomes,
});

describe("купон", () => {
  beforeEach(() => clearPicks());

  it("добавляет и убирает исход повторным нажатием, запоминает коэффициент при добавлении", () => {
    const e = ev("1", [o("home", 2, 1.9)]);
    expect(togglePick(e, e.outcomes[0], "2026-11-15T00:00:00Z")).toBe(true);
    expect(hasPick("1", "home")).toBe(true);
    expect(getPicks()[0]).toMatchObject({ added_price: 1.9, home: "A1", added_at: "2026-11-15T00:00:00Z" });
    expect(togglePick(e, e.outcomes[0])).toBe(false);
    expect(getPicks()).toEqual([]);
  });

  it("движение от добавления и экспресс; пропавший матч остаётся в купоне без цен", () => {
    const e1 = ev("1", [o("home", 2, 2)]), e2 = ev("2", [o("over@2.5", 1.8, 1.8)]);
    togglePick(e1, e1.outcomes[0]);
    togglePick(e2, e2.outcomes[0]);
    const later = [ev("1", [o("home", 2, 1.5)]), ev("2", [o("over@2.5", 1.8, 2.0)])];
    const rows = couponRows(getPicks(), later);
    expect(rows.map((r) => r.sinceAdded)).toEqual([-25, 11.11]);
    const t = expressTotals(rows);
    expect(t.opening).toBeCloseTo(3.6);
    expect(t.current).toBeCloseTo(3.0);
    expect(t.change).toBe(-16.67);

    const gone = couponRows(getPicks(), [later[0]]);
    expect(gone[1].outcome).toBeNull();
    expect(expressTotals(gone).current).toBeNull();
  });
});
