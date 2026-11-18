import { useEffect, useState } from "react";
import type { DataSource } from "../data";
import { fmt, fmtDT, fmtPct } from "../format";
import { Flag } from "../flags";
import { MARKET_NAMES } from "../markets";
import type { Market, MoveRow } from "../types";
import { PickBtn } from "./PickBtn";

// «Просадки» и «Повышения»: исходы, сдвинувшиеся от открытия минимум на N %
export function MovesTab({ kind, source, version, league, onError }: {
  kind: "drops" | "rises"; source: DataSource; version: number; league: string; onError: (m: string) => void;
}) {
  const rising = kind === "rises";
  const [min, setMin] = useState(5);
  const [started, setStarted] = useState(false);
  const [market, setMarket] = useState<Market | "">("");
  const [rows, setRows] = useState<MoveRow[] | null>(null);
  const cls = rising ? "up" : "down";

  useEffect(() => {
    source.moves(kind, min, started).then(setRows, (e: Error) => onError(e.message));
  }, [source, version, kind, min, started, onError]);

  const shown = (rows ?? []).filter((r) => (!market || r.market === market) && (!league || r.sport_title === league));
  return (
    <section>
      <div className="toolbar">
        <label>{rising ? "Рост" : "Просадка"} от{" "}
          <input type="number" value={min} min={0} step={0.5} style={{ width: 70 }} onChange={(e) => setMin(parseFloat(e.target.value) || 0)} /> %
        </label>
        <label><input type="checkbox" checked={started} onChange={(e) => setStarted(e.target.checked)} /> включая начавшиеся</label>
        <label>Рынок{" "}
          <select value={market} onChange={(e) => setMarket(e.target.value as Market | "")}>
            <option value="">все</option>
            {(Object.entries(MARKET_NAMES) as [Market, string][]).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </label>
      </div>
      <div className="scroll">
        <table>
          <thead><tr>
            <th>Δ</th><th>Исход</th><th className="left">Матч</th><th>Начало</th>
            <th className="grp">Открытие</th><th>Сейчас</th><th className="grp">Отслеживается</th><th title="Купон" />
          </tr></thead>
          <tbody>
            {!rows && <tr><td colSpan={8} className="empty">Загрузка…</td></tr>}
            {rows && !shown.length && <tr><td colSpan={8} className="empty">{rising ? "Повышений" : "Просадок"} от {min}% нет</td></tr>}
            {shown.map((r) => (
              <tr key={`${r.event_id}:${r.code}`}>
                <td className={`pct ${cls} num`}>{fmtPct(r.change_pct)}</td>
                <td className={r.derived ? "derived" : ""} title={`${MARKET_NAMES[r.market] ?? ""}${r.derived ? " · частично рассчитан из 1X2" : ""}`}>
                  <b>{r.label}</b>{r.point != null && !r.main && <div className="muted">не основная линия</div>}
                </td>
                <td className="left"><b>{r.home} — {r.away}</b><div className="muted"><Flag sportKey={r.sport_key} />{r.sport_title}</div></td>
                <td className="muted">{fmtDT(r.commence_time)}</td>
                <td className="num grp">{fmt(r.opening)}</td>
                <td className={`num ${cls}`}><b>{fmt(r.current)}</b></td>
                <td className="muted grp">с {fmtDT(r.open_ts)} · {r.snapshots} сн.</td>
                <td><PickBtn ev={{ ...r, id: r.event_id }} o={r} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
