import { useEffect, useState } from "react";
import type { DataSource } from "../data";
import { PAIRS } from "../markets";
import type { EventMove, HistorySnap } from "../types";
import { availableHistMarkets, HistoryTable, type HistMarket } from "./HistoryTable";
import { LinesTable } from "./LinesTable";
import { OddsChart } from "./OddsChart";

// Раскрытый матч: все линии тоталов и фор, график и история снимков
export function MatchDetails({ ev, source, version }: { ev: EventMove; source: DataSource; version: number }) {
  const [h, setH] = useState<HistorySnap[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [market, setMarket] = useState<HistMarket>("1x2");

  useEffect(() => {
    let alive = true;
    source.history(ev.id).then((x) => alive && setH(x), (e: Error) => alive && setErr(e.message));
    return () => { alive = false; };
  }, [ev.id, source, version]);

  const tables = PAIRS.map(([a, b, sign, title]) => <LinesTable key={a} ev={ev} a={a} b={b} sign={sign} title={title} />);
  const hasLines = ev.outcomes.some((o) => o.point != null);
  const avail = h ? availableHistMarkets(h) : [];
  const m = avail.some((x) => x.key === market) ? market : "1x2";

  return (
    <>
      <div className="detailsGrid">
        {hasLines ? tables : <span className="muted">Линий тоталов и фор у этого матча пока нет.</span>}
      </div>
      {err && <div className="err">Ошибка: {err}</div>}
      {!h && !err && <div className="muted">Загрузка…</div>}
      {h && <OddsChart ev={ev} h={h} />}
      {h && (
        <>
          <div className="toolbar" style={{ margin: "12px 0 6px" }}>
            <span className="muted">История снимков:</span>
            {avail.map((x) => (
              <button key={x.key} className={`histBtn ${x.key === m ? "on" : ""}`} onClick={() => setMarket(x.key)}>{x.title}</button>
            ))}
            <span className="muted">цвет — изменение к предыдущему снимку</span>
          </div>
          <div className="scroll"><HistoryTable h={h} market={m} /></div>
        </>
      )}
    </>
  );
}
