import { describe, expect, it } from "vitest";
import { closePct, filterArchive, norm } from "./archive";
import type { ArchiveEntry } from "./types";

const e = (id: string, home: string, away: string, t: string, league = "Russia Premier League"): ArchiveEntry => ({
  id, home, away, sport_key: "soccer_russia_premier_league", sport_title: league, commence_time: t, month: t.slice(0, 7),
});
const list = [
  e("1", "FC Rostov", "Kryliya Sovetov", "2026-11-20T16:00:00Z"),
  e("2", "Zenit St Petersburg", "FC Rostov", "2026-12-02T16:00:00Z"),
  e("3", "Atlético Madrid", "Sevilla", "2026-11-22T20:00:00Z", "La Liga - Spain"),
];

describe("архив", () => {
  it("ищет команду кириллицей и латиницей, новые матчи сверху", () => {
    expect(filterArchive(list, { q: "Ростов", league: "", month: "" }).map((x) => x.id)).toEqual(["2", "1"]);
    expect(filterArchive(list, { q: "крылья", league: "", month: "" }).map((x) => x.id)).toEqual(["1"]);
    expect(filterArchive(list, { q: "atletico", league: "", month: "" }).map((x) => x.id)).toEqual(["3"]);
    expect(filterArchive(list, { q: "ростов зенит", league: "", month: "2026-12" }).map((x) => x.id)).toEqual(["2"]);
    expect(filterArchive(list, { q: "", league: "La Liga - Spain", month: "" }).map((x) => x.id)).toEqual(["3"]);
    expect(norm("Ёлки-Палки")).toBe("elki palki");
  });

  it("считает изменение от открытия до закрытия", () => {
    expect(closePct([1.93, 2.32])).toBe(20.21);
    expect(closePct(undefined)).toBeNull();
  });
});
