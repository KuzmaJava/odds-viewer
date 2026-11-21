import { Fragment, useEffect, useMemo, useState } from "react";
import { closePct, filterArchive } from "../archive";
import type { DataSource } from "../data";
import { Flag } from "../flags";
import { fmt, fmtDT, fmtPct } from "../format";
import type { ArchiveEntry, ArchiveIndex, EventMove } from "../types";
import { MatchDetails } from "./MatchDetails";

const LIMIT = 200;
const MONTHS = ["январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь"];
const monthTitle = (m: string) => `${MONTHS[Number(m.slice(5, 7)) - 1] ?? m.slice(5, 7)} ${m.slice(0, 4)}`;

function saved(key: string): string {
  try { return localStorage.getItem(key) || ""; } catch { return ""; }
}

// П1 / X / П2 одного матча: закрытие, под ним открытие и изменение
function X({ v }: { v?: [number | null, number | null] }) {
  const p = closePct(v);
  const cls = p == null || p === 0 ? "" : p < 0 ? "down" : "up";
  return (
    <td className={`num ${cls}`}>
      <b>{fmt(v?.[1])}</b>
      <div className="muted">{fmt(v?.[0])}{p != null && p !== 0 && <> · <span className={`pct ${cls}`}>{fmtPct(p)}</span></>}</div>
    </td>
  );
}

// Архив: прошедшие матчи навсегда — поиск по команде, лиге и месяцу; матч раскрывается в полную историю
export function ArchiveTab({ source, version, onError }: { source: DataSource; version: number; onError: (m: string) => void }) {
  const [index, setIndex] = useState<ArchiveIndex | null | undefined>(undefined);
  const [q, setQ] = useState(() => saved("archive_q"));
  const [league, setLeague] = useState(() => saved("archive_league"));
  const [month, setMonth] = useState("");
  const [open, setOpen] = useState<string | null>(null);
  const [ev, setEv] = useState<EventMove | null>(null);

  useEffect(() => {
    source.archiveIndex().then(setIndex, (e: Error) => { setIndex(null); onError(e.message); });
  }, [source, onError]);
  useEffect(() => { try { localStorage.setItem("archive_q", q); localStorage.setItem("archive_league", league); } catch { /* ignore */ } }, [q, league]);

  const leagues = useMemo(() => {
    const m = new Map<string, string>();
    index?.events.forEach((e) => m.set(e.sport_title, e.sport_key));
    return [...m].sort((a, b) => a[0].localeCompare(b[0]));
  }, [index]);
  const found = useMemo(() => (index ? filterArchive(index.events, { q, league, month }) : []), [index, q, league, month]);

  const toggle = (e: ArchiveEntry) => {
    if (open === e.id) { setOpen(null); return; }
    setOpen(e.id);
    setEv(null);
    source.archiveMonth(e.month).then(
      (m) => setEv(m.events.find((x) => x.id === e.id) ?? null),
      (err: Error) => onError(err.message),
    );
  };

  if (index === undefined) return <section className="muted">Загрузка архива…</section>;
  if (!index || !index.events.length) {
    return (
      <section className="muted">
        Архив пока пуст. Матч попадает сюда через 3 часа после начала со всей историей коэффициентов и хранится всегда.
        {!source.isStatic && " Архив ведёт только версия на GitHub Pages."}
      </section>
    );
  }

  return (
    <section>
      <div className="toolbar">
        <input className="search" type="search" placeholder="Команда: Ростов, Real Madrid…" value={q} onChange={(e) => setQ(e.target.value)} />
        <label>Лига{" "}
          <select value={league} onChange={(e) => setLeague(e.target.value)}>
            <option value="">все</option>
            {leagues.map(([t]) => <option key={t} value={t}>{t}</option>)}
          </select>
        </label>
        <label>Месяц{" "}
          <select value={month} onChange={(e) => setMonth(e.target.value)}>
            <option value="">все</option>
            {[...index.months].reverse().map((m) => <option key={m.month} value={m.month}>{monthTitle(m.month)} · {m.count}</option>)}
          </select>
        </label>
        <span className="muted">Найдено: {found.length} из {index.events.length}. Нажмите на матч — откроется вся история.</span>
      </div>
      <div className="scroll">
        <table className="archive">
          <thead><tr>
            <th className="left">Матч</th><th className="wideOnly">Начало</th>
            <th className="grp">П1</th><th>X</th><th>П2</th>
          </tr></thead>
          <tbody>
            {!found.length && <tr><td colSpan={5} className="empty">Ничего не найдено{q && " — попробуйте часть названия или латиницей, как в API (Rostov, Krylia)"}</td></tr>}
            {found.slice(0, LIMIT).map((e) => (
              <Fragment key={e.id}>
                <tr className="ev" onClick={() => toggle(e)}>
                  <td className="left"><b>{e.home} — {e.away}</b>
                    <div className="muted"><Flag sportKey={e.sport_key} />{e.sport_title}<span className="narrowOnly"> · {fmtDT(e.commence_time)}</span></div>
                  </td>
                  <td className="muted wideOnly">{fmtDT(e.commence_time)}</td>
                  <X v={e.x?.home} /><X v={e.x?.draw} /><X v={e.x?.away} />
                </tr>
                {open === e.id && (
                  <tr className="details"><td colSpan={5}>
                    {ev ? <MatchDetails ev={ev} source={source} version={version} /> : <span className="muted">Загрузка…</span>}
                  </td></tr>
                )}
              </Fragment>
            ))}
            {found.length > LIMIT && <tr><td colSpan={5} className="empty">Показаны первые {LIMIT} — уточните поиск</td></tr>}
          </tbody>
        </table>
      </div>
      <div className="muted" style={{ marginTop: 8 }}>Крупная цифра — закрытие (последний снимок перед матчем), под ней — открытие и изменение.</div>
    </section>
  );
}
