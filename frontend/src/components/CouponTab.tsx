import { Fragment, useEffect, useState } from "react";
import { clearPicks, couponRows, expressTotals, removePick, useCoupon } from "../coupon";
import type { DataSource } from "../data";
import { Flag } from "../flags";
import { fmt, fmtDT, fmtPct } from "../format";
import { MARKET_NAMES } from "../markets";
import type { EventMove } from "../types";
import { MatchDetails } from "./MatchDetails";

const dirCls = (v: number | null | undefined) => (v == null || v === 0 ? "" : v < 0 ? "down" : "up");

function Pct({ v }: { v: number | null | undefined }) {
  return v == null ? <span className="muted">—</span> : v === 0 ? <span className="muted">0%</span> : <span className={`pct ${dirCls(v)}`}>{fmtPct(v)}</span>;
}

// Купон: только выбранные исходы — открытие, коэффициент при добавлении, сейчас и движение
export function CouponTab({ source, version, onError }: { source: DataSource; version: number; onError: (m: string) => void }) {
  const picks = useCoupon();
  const [events, setEvents] = useState<EventMove[] | null>(null);
  const [open, setOpen] = useState<string | null>(null);

  useEffect(() => {
    source.events(true).then(setEvents, (e: Error) => onError(e.message));
  }, [source, version, onError]);

  const rows = couponRows([...picks].sort((a, b) => a.commence_time.localeCompare(b.commence_time)), events ?? []);
  const tot = expressTotals(rows);
  const now = new Date().toISOString();

  return (
    <section>
      <div className="toolbar">
        <span className="muted">
          Добавляйте исходы нажатием на коэффициент во вкладке «Матчи» (и в раскрытых линиях) или кнопкой «+» в «Просадках» / «Повышениях».
          Купон хранится в этом браузере. «С добавления» — изменение от коэффициента в момент, когда вы добавили исход.
        </span>
        {picks.length > 0 && <button onClick={() => { if (confirm("Очистить купон?")) clearPicks(); }}>Очистить купон</button>}
      </div>
      <div className="scroll">
        <table className="coupon">
          <thead><tr>
            <th className="left">Исход</th><th className="left wideOnly">Матч</th><th className="wideOnly">Начало</th>
            <th className="grp wideOnly">Открытие</th><th className="wideOnly">При добавлении</th><th>Сейчас</th>
            <th className="grp"><span className="wideOnly">От открытия</span><span className="narrowOnly">Δ откр.</span></th><th><span className="wideOnly">С добавления</span><span className="narrowOnly">Δ доб.</span></th><th />
          </tr></thead>
          <tbody>
            {!picks.length && <tr><td colSpan={9} className="empty">Купон пуст. Нажмите на коэффициент во вкладке «Матчи», чтобы добавить исход.</td></tr>}
            {picks.length > 0 && !events && <tr><td colSpan={9} className="empty">Загрузка…</td></tr>}
            {events && rows.map(({ pick: p, outcome: o, ev, sinceAdded }) => {
              const key = `${p.event_id}|${p.code}`;
              const started = p.commence_time <= now;
              return (
                <Fragment key={key}>
                  <tr className="ev" onClick={() => ev && setOpen(open === key ? null : key)}>
                    <td className={`left ${o?.derived ? "derived" : ""}`} title={o ? MARKET_NAMES[o.market] : ""}>
                      <b>{o?.label ?? p.label}</b>{o && o.point != null && !o.main && <div className="muted">не основная линия</div>}
                      <div className="muted narrowOnly">{p.home} — {p.away}<br />{fmtDT(ev?.commence_time ?? p.commence_time)}
                        {ev?.closed_at ? " · закрытие снято" : started ? " · начался" : ""}</div>
                    </td>
                    <td className="left wideOnly"><b>{p.home} — {p.away}</b><div className="muted"><Flag sportKey={p.sport_key} />{p.sport_title}</div></td>
                    <td className="muted wideOnly">{fmtDT(ev?.commence_time ?? p.commence_time)}
                      {ev?.closed_at ? <div>закрытие снято</div> : started ? <div>начался</div> : null}
                    </td>
                    <td className="num grp wideOnly">{fmt(o?.opening)}</td>
                    <td className="num wideOnly">{fmt(p.added_price)}<div className="muted">{fmtDT(p.added_at)}</div></td>
                    <td className={`num ${dirCls(o?.change_pct)}`}><b>{fmt(o?.current)}</b>
                      {!o && <div className="muted">нет в данных</div>}
                      {o && <div className="muted narrowOnly">откр. {fmt(o.opening)}</div>}
                    </td>
                    <td className="num grp"><Pct v={o?.change_pct} /></td>
                    <td className="num"><Pct v={sinceAdded} /></td>
                    <td><button className="pickBtn" title="Убрать из купона" onClick={(e) => { e.stopPropagation(); removePick(p.event_id, p.code); }}>✕</button></td>
                  </tr>
                  {open === key && ev && (
                    <tr className="details"><td colSpan={9}><MatchDetails ev={ev} source={source} version={version} /></td></tr>
                  )}
                </Fragment>
              );
            })}
            {events && rows.length > 1 && (
              <tr className="total">
                <td className="left"><b>Экспресс</b><div className="muted narrowOnly">{rows.length} исх.</div></td>
                <td className="left muted wideOnly">{rows.length} исх. — произведение коэффициентов</td>
                <td className="wideOnly" />
                <td className="num grp wideOnly">{fmt(tot.opening)}</td>
                <td className="num wideOnly">{fmt(tot.added)}</td>
                <td className={`num ${dirCls(tot.change)}`}><b>{fmt(tot.current)}</b><div className="muted narrowOnly">откр. {fmt(tot.opening)}</div></td>
                <td className="num grp"><Pct v={tot.change} /></td>
                <td className="num"><Pct v={tot.sinceAdded} /></td>
                <td />
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}
