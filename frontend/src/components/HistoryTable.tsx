import { fmt, fmtDT } from "../format";
import { COLS, fmtPt, labelOfBase, lineCode, lineOf } from "../markets";
import type { HistorySnap } from "../types";

export type HistMarket = "1x2" | "total" | "spread" | "it1" | "it2";
export const HIST_MARKETS: { key: HistMarket; title: string; a?: string; b?: string; sign?: 1 | -1 }[] = [
  { key: "1x2", title: "1X2 и ДШ" },
  { key: "total", title: "Тоталы", a: "over", b: "under", sign: 1 },
  { key: "spread", title: "Форы", a: "ah_home", b: "ah_away", sign: -1 },
  { key: "it1", title: "ИТ1", a: "it1_over", b: "it1_under", sign: 1 },
  { key: "it2", title: "ИТ2", a: "it2_over", b: "it2_under", sign: 1 },
];

export function availableHistMarkets(h: HistorySnap[]) {
  const bases = new Set(h.flatMap((r) => Object.keys(r.p).map((c) => lineOf(c)[0])));
  return HIST_MARKETS.filter((m) => m.key === "1x2" || (m.a && bases.has(m.a)));
}

const DC_PAIRS: Record<string, [string, string]> = { "1X": ["home", "draw"], "12": ["home", "away"], X2: ["draw", "away"] };

// Цена исхода в снимке; двойной шанс без реального значения — расчёт из 1X2 того же снимка
export function priceAt(snap: HistorySnap, code: string): { v: number; derived: boolean } | null {
  const v = snap.p[code];
  if (v) return { v: v[0], derived: false };
  const pair = DC_PAIRS[code];
  if (pair && snap.p[pair[0]] && snap.p[pair[1]]) {
    return { v: 1 / (1 / snap.p[pair[0]]![0] + 1 / snap.p[pair[1]]![0]), derived: true };
  }
  return null;
}

// Значение с подсветкой изменения относительно предыдущего снимка
function Val({ v, prev, derived }: { v?: number | null; prev?: number | null; derived?: boolean }) {
  if (v == null) return <span className="muted">—</span>;
  const dir = prev == null || Math.abs(prev - v) < 1e-9 ? "" : v < prev ? "down" : "up";
  return <span className={`${dir} ${derived ? "derived" : ""}`}>{fmt(v)}</span>;
}

export function HistoryTable({ h, market }: { h: HistorySnap[]; market: HistMarket }) {
  if (market === "1x2") {
    const rows = h.filter((r) => r.p.home || r.p["1X"]);
    const cols = COLS.slice(0, 6).filter(([code]) => rows.some((r) => priceAt(r, code)));
    return (
      <table className="hist">
        <thead><tr><th>Снимок</th>{cols.map(([c, l]) => <th key={c}>{l}</th>)}</tr></thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={r.ts}>
              <td>{fmtDT(r.ts)}</td>
              {cols.map(([code]) => {
                const cur = priceAt(r, code), prev = i ? priceAt(rows[i - 1], code) : null;
                return <td key={code} className="num"><Val v={cur?.v} prev={prev?.v} derived={cur?.derived} /></td>;
              })}
            </tr>
          ))}
        </tbody>
      </table>
    );
  }
  const { a, b, sign } = HIST_MARKETS.find((m) => m.key === market)! as { a: string; b: string; sign: 1 | -1 };
  const pts = new Set<number>();
  h.forEach((r) => Object.keys(r.p).forEach((c) => {
    const [base, pt] = lineOf(c);
    if (pt == null) return;
    if (base === a) pts.add(pt);
    if (base === b) pts.add(sign * pt + 0);
  }));
  const lines = [...pts].sort((x, y) => x - y);
  const rows = h.filter((r) => Object.keys(r.p).some((c) => [a, b].includes(lineOf(c)[0])));
  if (!rows.length) return <span className="muted">Снимков по этому рынку нет.</span>;
  const get = (r: HistorySnap | null, base: string, pt: number) => (r?.p[lineCode(base, pt)]?.[0] ?? null);
  return (
    <table className="hist">
      <thead>
        <tr><th rowSpan={2}>Снимок</th>{lines.map((pt) => (
          <th key={pt} colSpan={2} className="grp">{sign < 0 ? `${fmtPt(a, pt)} / ${fmtPt(b, sign * pt + 0)}` : fmtPt(a, pt)}</th>
        ))}</tr>
        <tr>{lines.map((pt) => [<th key={`a${pt}`} className="grp">{labelOfBase(a)}</th>, <th key={`b${pt}`}>{labelOfBase(b)}</th>])}</tr>
      </thead>
      <tbody>
        {rows.map((r, i) => {
          const prev = i ? rows[i - 1] : null;
          return (
            <tr key={r.ts}>
              <td>{fmtDT(r.ts)}</td>
              {lines.map((pt) => [
                <td key={`a${pt}`} className="num grp"><Val v={get(r, a, pt)} prev={get(prev, a, pt)} /></td>,
                <td key={`b${pt}`} className="num"><Val v={get(r, b, sign * pt)} prev={get(prev, b, sign * pt)} /></td>,
              ])}
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
