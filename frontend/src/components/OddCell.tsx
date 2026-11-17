import { fmt, fmtDT, fmtPct } from "../format";
import { fmtPt } from "../markets";
import { hasPick, togglePick, useCoupon, type MatchMeta } from "../coupon";
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

// Ячейка коэффициента: текущее значение, под ним открытие и изменение; фон — при движении от 3%.
// С ev ячейку можно нажать — исход добавится в купон (повторное нажатие убирает).
export function OddCell({ o, first = false, withLine = true, ev }: { o?: Outcome; first?: boolean; withLine?: boolean; ev?: MatchMeta }) {
  useCoupon();
  if (!o) return <td className={`odd num muted ${first ? "grp" : ""}`}>—</td>;
  const picked = !!ev && hasPick(ev.id, o.code);
  const ch = o.change_pct;
  const dir = ch == null || ch === 0 ? "" : ch < 0 ? "down" : "up";
  const bg = ch != null && Math.abs(ch) >= 3 ? ` ${dir}-bg` : "";
  const title = `${o.label}: открытие ${fmt(o.opening)} (${fmtDT(o.open_ts)}) → сейчас ${fmt(o.current)} (${fmtDT(o.cur_ts)}), снимков ${o.n}` +
    (o.derived ? " · двойной шанс частично рассчитан из 1X2" : "") +
    (ev ? (picked ? "\nВ купоне — нажмите, чтобы убрать" : "\nНажмите, чтобы добавить в купон") : "");
  const onClick = ev ? (e: React.MouseEvent) => { e.stopPropagation(); togglePick(ev, o); } : undefined;
  return (
    <td className={`odd num ${first ? "grp" : ""}${bg} ${o.derived ? "derived" : ""}${ev ? " pickable" : ""}${picked ? " picked" : ""}`}
      title={title} onClick={onClick}>
      {withLine && o.point != null && <LineLabel o={o} />}
      <div className="cur">{fmt(o.current)}</div>
      {ch ? <div className={`delta ${dir}`}>{fmt(o.opening)} · {fmtPct(ch)}</div> : <div className="delta muted">без изменений</div>}
    </td>
  );
}
