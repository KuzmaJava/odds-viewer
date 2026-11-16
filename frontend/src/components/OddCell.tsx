import { fmt, fmtDT, fmtPct } from "../format";
import { fmtPt } from "../markets";
import type { Outcome } from "../types";

// Линия над коэффициентом; если основная линия сдвинулась с открытия — «2.25 → 2.5» (синим)
function LineLabel({ o }: { o: Outcome }) {
  const was = o.open_main_point;
  if (was == null || was === o.point) return <div className="pt">{fmtPt(o.base, o.point)}</div>;
  return (
    <div className="pt moved" title={`Основная линия на открытии была ${fmtPt(o.base, was)}`}>
      {fmtPt(o.base, was)} → {fmtPt(o.base, o.point)}
    </div>
  );
}

// Ячейка коэффициента: текущее значение, под ним открытие и изменение; фон — при движении от 3%
export function OddCell({ o, first = false, withLine = true }: { o?: Outcome; first?: boolean; withLine?: boolean }) {
  if (!o) return <td className={`odd num muted ${first ? "grp" : ""}`}>—</td>;
  const ch = o.change_pct;
  const dir = ch == null || ch === 0 ? "" : ch < 0 ? "down" : "up";
  const bg = ch != null && Math.abs(ch) >= 3 ? ` ${dir}-bg` : "";
  const title = `${o.label}: открытие ${fmt(o.opening)} (${fmtDT(o.open_ts)}) → сейчас ${fmt(o.current)} (${fmtDT(o.cur_ts)}), снимков ${o.n}` +
    (o.derived ? " · двойной шанс частично рассчитан из 1X2" : "");
  return (
    <td className={`odd num ${first ? "grp" : ""}${bg} ${o.derived ? "derived" : ""}`} title={title}>
      {withLine && o.point != null && <LineLabel o={o} />}
      <div className="cur">{fmt(o.current)}</div>
      {ch ? <div className={`delta ${dir}`}>{fmt(o.opening)} · {fmtPct(ch)}</div> : <div className="delta muted">без изменений</div>}
    </td>
  );
}
