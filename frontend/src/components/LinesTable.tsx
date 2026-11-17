import { fmtPt, labelOfBase, lineCode } from "../markets";
import type { EventMove } from "../types";
import { OddCell } from "./OddCell";

// Все линии рынка: строка на линию, пара исходов (ТБ/ТМ или Ф1/Ф2 с противоположной форой)
export function LinesTable({ ev, a, b, sign, title }: { ev: EventMove; a: string; b: string; sign: 1 | -1; title: string }) {
  const by = Object.fromEntries(ev.outcomes.map((o) => [o.code, o]));
  const pts = new Set<number>();
  for (const o of ev.outcomes) {
    if (o.point == null) continue;
    if (o.base === a) pts.add(o.point);
    if (o.base === b) pts.add(sign * o.point + 0);
  }
  if (!pts.size) return null;
  return (
    <table className="hist lines">
      <thead><tr><th>{title}</th><th>{labelOfBase(a)}</th><th>{labelOfBase(b)}</th></tr></thead>
      <tbody>
        {[...pts].sort((x, y) => x - y).map((pt) => {
          const oa = by[lineCode(a, pt)], ob = by[lineCode(b, sign * pt)];
          return (
            <tr key={pt} className={oa?.main || ob?.main ? "mainline" : ""}>
              <td className="num"><b>{fmtPt(a, pt)}</b>{sign < 0 ? ` / ${fmtPt(b, sign * pt + 0)}` : ""}</td>
              <OddCell o={oa} withLine={false} ev={ev} />
              <OddCell o={ob} withLine={false} ev={ev} />
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
