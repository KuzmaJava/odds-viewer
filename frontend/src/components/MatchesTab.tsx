import { Fragment, useEffect, useState } from "react";
import { downloadCsv, movesCsv } from "../csv";
import type { DataSource } from "../data";
import { fmtDT, localDT } from "../format";
import { MARKET_NAMES, mainByBase, visibleCols } from "../markets";
import type { EventMove, Policy } from "../types";
import { Flag } from "../flags";
import { MatchDetails } from "./MatchDetails";
import { OddCell } from "./OddCell";

function scheduleText(p: Policy) {
  if (p.summary) return `Снимки: ${p.summary}.`;
  const what = p.league_mode
    ? `1X2${p.league_snapshot_markets === "h2h" ? "" : " и основной тотал"} всех матчей лиги одним запросом`
    : "все исходы матча одним запросом (1X2, все линии тоталов и фор)";
  return `Снимок — ${what}: при появлении матча, раз в ${p.lines_far_hours ?? 12} ч, за ${p.lines_near_window_hours ?? 13} ч до старта — ` +
    `раз в ${p.lines_near_hours ?? 3} ч, закрытие за ${Math.round(p.close_window_min ?? 60)} мин.`;
}

export function MatchesTab({ source, version, policy, league, onError }: {
  source: DataSource; version: number; policy: Policy; league: string; onError: (m: string) => void;
}) {
  const [started, setStarted] = useState(false);
  const [events, setEvents] = useState<EventMove[] | null>(null);
  const [open, setOpen] = useState<Set<string>>(new Set());

  useEffect(() => {
    source.events(started).then(setEvents, (e: Error) => onError(e.message));
  }, [source, version, started, onError]);

  const toggle = (id: string) => setOpen((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  const list = (events ?? []).filter((e) => !league || e.sport_title === league);
  const cols = visibleCols(list);
  const span = 3 + cols.length;

  return (
    <section>
      <div className="toolbar">
        <label><input type="checkbox" checked={started} onChange={(e) => setStarted(e.target.checked)} /> показывать начавшиеся</label>
        <button title="Скачать текущий список матчей в CSV" onClick={() => list.length
          ? downloadCsv(movesCsv(list), `pinnacle-matches-${localDT(new Date().toISOString()).replace(/[: ]/g, "-")}.csv`)
          : onError("Список матчей пуст — нечего сохранять.")}>Скачать CSV</button>
        <span className="muted">
          {scheduleText(policy)} Значение — текущий коэффициент; под ним — открытие и изменение. Тотал и фора — основная линия
          (синим — если она сдвинулась с открытия); остальное — по клику на матч. Нажмите на коэффициент — исход попадёт в купон. Курсив — двойной шанс, рассчитанный из 1X2.
        </span>
      </div>
      <div className="scroll">
        <table>
          <thead>
            <tr>
              <th>Начало</th><th className="left">Матч</th><th>Снимков</th>
              {cols.map(([code, l, m], i) => (
                <th key={code} className={i === 0 || cols[i - 1][2] !== m ? "grp" : ""} title={MARKET_NAMES[m]}>{l}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {events && !list.length && (
              <tr><td colSpan={span} className="empty">Пока нет данных. Выберите лиги на вкладке «Лиги» и дождитесь снимка.</td></tr>
            )}
            {!events && <tr><td colSpan={span} className="empty">Загрузка…</td></tr>}
            {list.map((ev) => {
              const by = mainByBase(ev);
              return (
                <Fragment key={ev.id}>
                  <tr className="ev" onClick={() => toggle(ev.id)}>
                    <td className="muted">{fmtDT(ev.commence_time)}</td>
                    <td className="left"><b>{ev.home} — {ev.away}</b><div className="muted"><Flag sportKey={ev.sport_key} />{ev.sport_title}</div></td>
                    <td className="muted">{ev.snapshots}<div>с {fmtDT(ev.first_ts)}</div></td>
                    {cols.map(([code, , m], i) => <OddCell key={code} o={by[code]} ev={ev} first={i === 0 || cols[i - 1][2] !== m} />)}
                  </tr>
                  {open.has(ev.id) && (
                    <tr className="details"><td colSpan={span}><MatchDetails ev={ev} source={source} version={version} /></td></tr>
                  )}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}
