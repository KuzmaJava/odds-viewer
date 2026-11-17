import { useEffect, useState } from "react";
import type { DataSource } from "../data";
import { Flag } from "../flags";
import { dispatchWorkflow, getToken } from "../github";
import { fmtDT } from "../format";
import type { LeagueSchedule as Row } from "../types";

// «через 3 ч 20 мин» — от текущего момента
export function fromNow(iso: string | null, now = Date.now()) {
  if (!iso) return "";
  const min = Math.round((new Date(iso).getTime() - now) / 60_000);
  if (min <= 15) return "в ближайший прогон";
  const d = Math.floor(min / 1440), h = Math.floor((min % 1440) / 60), m = min % 60;
  return "через " + (d ? `${d} д ${h} ч` : h ? `${h} ч${m ? ` ${m} мин` : ""}` : `${m} мин`);
}

function When({ at, sub, label }: { at: string | null; sub?: string | null; label: string }) {
  if (!at) return <td className="muted" data-label={label}>—</td>;
  return (
    <td data-label={label}>
      <b>{fmtDT(at)}</b> <span className="muted rel">{fromNow(at)}</span>
      {sub && <div className="muted">{sub}</div>}
    </td>
  );
}

// Ближайшие обновления коэффициентов по каждой лиге. Без выбранной лиги — все, с выбранной — только она.
export function LeagueSchedule({ rows, titles, league, source }: {
  rows: Row[]; titles: Record<string, string>; league: string; source: DataSource;
}) {
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  // Кнопка «снять линии сейчас» — только на GitHub Pages и с сохранённым токеном (как «Опросить сейчас»)
  const canPoll = source.isStatic && !!source.site?.repo && !!getToken();
  const linesNow = async (r: Row) => {
    const title = titles[r.sport] ?? r.sport;
    if (!confirm(`Снять все тоталы и форы ${title} сейчас, вне расписания? Это ≈ ${2 * r.matches} запросов квоты лиги ` +
      `(${r.matches} матч., по 2 на матч). Расписание после этого не сбивается.`)) return;
    setBusy(r.sport);
    try {
      await dispatchWorkflow(source.site!.repo, source.site!.ref ?? null, { lines_now: r.sport });
      setMsg(`${title}: снимок линий запущен, сайт обновится примерно через 1–2 минуты.`);
    } catch (e) { setMsg("Не удалось запустить: " + (e as Error).message); }
    finally { setBusy(null); }
  };
  const [open, setOpen] = useState(() => { try { return localStorage.getItem("schedule_open") !== "0"; } catch { return true; } });
  const [, tick] = useState(0);
  useEffect(() => { const id = setInterval(() => tick((x) => x + 1), 60_000); return () => clearInterval(id); }, []);
  const list = rows.filter((r) => !league || (titles[r.sport] ?? r.sport) === league)
    .sort((a, b) => (titles[a.sport] ?? a.sport).localeCompare(titles[b.sport] ?? b.sport));
  if (!list.length) return null;
  const next = list.flatMap((r) => [r.h2h_at, r.lines_at, r.close_at]).filter(Boolean).sort()[0] ?? null;
  return (
    <details className="schedule" open={open}
      onToggle={(e) => { const o = (e.target as HTMLDetailsElement).open; setOpen(o); try { localStorage.setItem("schedule_open", o ? "1" : "0"); } catch { /* ignore */ } }}>
      <summary>Когда обновятся коэффициенты{next && <span className="muted"> · ближайшее {fmtDT(next)}, {fromNow(next)}</span>}</summary>
      <div className="scroll">
        <table className="hist sched">
          <thead><tr>
            <th className="left">Лига</th><th>1X2 (вся лига)</th><th>Все тоталы и форы</th><th>Закрытие</th>
          </tr></thead>
          <tbody>
            {list.map((r) => (
              <tr key={r.sport}>
                <td className="left league"><Flag sportKey={r.sport} />{titles[r.sport] ?? r.sport}
                  <div className="muted">{r.matches ? `матчей впереди: ${r.matches}` : "матчей пока нет"}</div>
                  {canPoll && r.matches > 0 && (
                    <button className="histBtn linesNow" disabled={busy !== null} onClick={() => linesNow(r)}
                      title="Внеплановый снимок всех тоталов и фор матчей этой лиги">
                      {busy === r.sport ? "Запускаю…" : "Снять линии сейчас"}
                    </button>
                  )}</td>
                <When label="1X2" at={r.h2h_at} />
                <When label="Линии" at={r.lines_at} sub={r.lines_match && `${r.lines_match}${r.lines_point ? ` · ${r.lines_point}` : ""}`} />
                <When label="Закрытие" at={r.close_at} sub={r.close_match} />
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {msg && <div className="banner" style={{ margin: "6px 0" }}>{msg}</div>}
      <div className="muted">
        Время — по расписанию; снимок делается в ближайший прогон после него (прогоны каждые 15 мин). Новые матчи
        Pinnacle проверяются каждые 15 мин и снимаются сразу при появлении.
      </div>
    </details>
  );
}
