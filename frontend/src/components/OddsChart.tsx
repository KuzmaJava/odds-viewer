import { useMemo, useRef, useState } from "react";
import { fmt, fmtDT, fmtPct } from "../format";
import type { EventMove, HistorySnap, Market } from "../types";
import { priceAt } from "./HistoryTable";

// График движения: серии выбираются чипами; цвет закрепляется за исходом при выборе
// (у остальных не меняется), максимум 8 серий — по числу цветов палитры.
const MAX_SERIES = 8;
const W = 900, H = 280, L = 48, R = 70, T = 12, B = 26;
const GROUPS: [string, Market][] = [["1X2", "1x2"], ["ДШ", "dc"], ["Тотал", "total"], ["Фора", "spread"], ["ИТ", "team_total"]];

interface Series { code: string; slot: number; label: string; pts: [number, number, boolean][] }

function niceStep(raw: number) {
  const p = Math.pow(10, Math.floor(Math.log10(raw))), f = raw / p;
  return (f < 1.5 ? 1 : f < 3 ? 2 : f < 7 ? 5 : 10) * p;
}

export function OddsChart({ ev, h }: { ev: EventMove; h: HistorySnap[] }) {
  const [sel, setSel] = useState<Map<string, number>>(() => new Map([["home", 1], ["draw", 2], ["away", 3]]));
  const [pct, setPct] = useState(false);
  const [hover, setHover] = useState<{ t: number; x: number; y: number } | null>(null);
  const boxRef = useRef<HTMLDivElement>(null);

  const toggle = (code: string) => {
    const next = new Map(sel);
    if (next.has(code)) next.delete(code);
    else if (next.size < MAX_SERIES) {
      const used = new Set(next.values());
      next.set(code, [1, 2, 3, 4, 5, 6, 7, 8].find((n) => !used.has(n))!);
    }
    setSel(next);
  };

  const labelOf = useMemo(() => Object.fromEntries(ev.outcomes.map((o) => [o.code, o.label])), [ev]);
  const series: Series[] = useMemo(() => [...sel].map(([code, slot]) => {
    let pts = h.map((r) => { const p = priceAt(r, code); return p ? [Date.parse(r.ts), p.v, p.derived] as [number, number, boolean] : null; })
      .filter((x): x is [number, number, boolean] => !!x);
    if (pct && pts.length) { const o = pts[0][1]; pts = pts.map(([t, v, d]) => [t, (v - o) / o * 100, d]); }
    return { code, slot, label: labelOf[code] || code, pts };
  }).filter((s) => s.pts.length), [sel, h, pct, labelOf]);

  const geo = useMemo(() => {
    if (!series.length) return null;
    const xs = [...new Set(series.flatMap((s) => s.pts.map((p) => p[0])))].sort((a, b) => a - b);
    const vals = series.flatMap((s) => s.pts.map((p) => p[1]));
    let lo = Math.min(...vals, pct ? 0 : Infinity), hi = Math.max(...vals, pct ? 0 : -Infinity);
    if (hi - lo < (pct ? 2 : 0.1)) { const m = (hi + lo) / 2, d = pct ? 1 : 0.05; lo = m - d; hi = m + d; }
    const pad = (hi - lo) * 0.08; lo -= pad; hi += pad;
    const x0 = xs[0], x1 = xs[xs.length - 1] === x0 ? x0 + 3600e3 : xs[xs.length - 1];
    const sx = (t: number) => L + (t - x0) / (x1 - x0) * (W - L - R);
    const sy = (v: number) => T + (hi - v) / (hi - lo) * (H - T - B);
    const step = niceStep((hi - lo) / 5);
    const yt: number[] = [];
    for (let v = Math.ceil(lo / step) * step; v <= hi; v += step) yt.push(v);
    const xt = xs.length <= 6 ? xs : [0, 0.25, 0.5, 0.75, 1].map((f) => xs[Math.round(f * (xs.length - 1))]);
    const fy = (v: number) => pct ? (v > 0 ? "+" : "") + v.toFixed(step < 1 ? 1 : 0) + "%" : v.toFixed(step < 0.1 ? 2 : step < 1 ? 1 : 0);
    return { xs, sx, sy, yt, xt, fy };
  }, [series, pct]);

  const onMove = (e: React.MouseEvent<SVGSVGElement>) => {
    if (!geo || !boxRef.current) return;
    const r = e.currentTarget.getBoundingClientRect(), x = (e.clientX - r.left) * (W / r.width);
    const t = geo.xs.reduce((best, v) => (Math.abs(geo.sx(v) - x) < Math.abs(geo.sx(best) - x) ? v : best), geo.xs[0]);
    const bx = boxRef.current.getBoundingClientRect();
    setHover({ t, x: e.clientX - bx.left, y: e.clientY - bx.top });
  };

  // подписи у конца линий — только когда серий немного, с разводкой по вертикали
  const ends = geo && series.length <= 4
    ? series.map((s) => ({ s, y: geo.sy(s.pts[s.pts.length - 1][1]) })).sort((a, b) => a.y - b.y)
      .map((it, i, arr) => { if (i) it.y = Math.max(it.y, arr[i - 1].y + 13); return it; })
    : [];

  return (
    <div className="chartBox" ref={boxRef}>
      <div className="chipRow">
        <b style={{ fontSize: 13 }}>График</b>
        <label style={{ marginLeft: 10 }}><input type="checkbox" checked={pct} onChange={(e) => setPct(e.target.checked)} /> в % от открытия</label>
        {sel.size > 0 && <button className="chip" onClick={() => setSel(new Map())}>очистить</button>}
      </div>
      <div className="chipRow">
        {GROUPS.map(([name, m]) => {
          const items = ev.outcomes.filter((o) => o.market === m);
          if (!items.length) return null;
          return [
            <span key={name} className="grpName">{name}</span>,
            ...items.map((o) => {
              const slot = sel.get(o.code);
              return (
                <button key={o.code} className={`chip ${slot ? "on" : ""}`} onClick={() => toggle(o.code)}
                  disabled={!slot && sel.size >= MAX_SERIES} title={!slot && sel.size >= MAX_SERIES ? "Не больше 8 линий на графике" : undefined}>
                  <span className="sw" style={slot ? { background: `var(--s${slot})` } : undefined} />{o.label}
                </button>
              );
            }),
          ];
        })}
      </div>
      {!geo ? <div className="muted" style={{ padding: "20px 0" }}>Выберите исходы выше — П1, X, ТМ 2.5 и т.д.</div> : (
        <svg className="chartSvg" viewBox={`0 0 ${W} ${H}`} role="img" aria-label="График коэффициентов"
          onMouseMove={onMove} onMouseLeave={() => setHover(null)}>
          {geo.yt.map((v) => (
            <g key={v}>
              <line className="grid" x1={L} x2={W - R} y1={geo.sy(v)} y2={geo.sy(v)} />
              <text x={L - 6} y={geo.sy(v) + 4} textAnchor="end">{geo.fy(v)}</text>
            </g>
          ))}
          {geo.xt.map((t) => <text key={t} x={geo.sx(t)} y={H - 6} textAnchor="middle">{fmtDT(new Date(t).toISOString())}</text>)}
          {series.map((s) => (
            <g key={s.code}>
              <path d={s.pts.map((p, i) => `${i ? "L" : "M"}${geo.sx(p[0]).toFixed(1)},${geo.sy(p[1]).toFixed(1)}`).join("")}
                fill="none" stroke={`var(--s${s.slot})`} strokeWidth={2} strokeLinejoin="round" />
              {s.pts.map((p) => (
                <circle key={p[0]} cx={geo.sx(p[0])} cy={geo.sy(p[1])} r={p[2] ? 3 : 4}
                  fill={p[2] ? "var(--panel)" : `var(--s${s.slot})`} stroke={`var(--s${s.slot})`} strokeWidth={2} />
              ))}
            </g>
          ))}
          {ends.map(({ s, y }) => <text key={s.code} className="lbl" x={W - R + 8} y={y + 4}>{s.label}</text>)}
          {hover && <line x1={geo.sx(hover.t)} x2={geo.sx(hover.t)} y1={T} y2={H - B} stroke="var(--muted)" strokeDasharray="3 3" />}
          <rect x={L} y={T} width={W - L - R} height={H - T - B} fill="transparent" />
        </svg>
      )}
      {geo && hover && (
        <div className="chartTip" style={{ top: hover.y + 12, left: Math.min(hover.x + 14, (boxRef.current?.clientWidth ?? 900) - 200) }}>
          <div className="muted">{fmtDT(new Date(hover.t).toISOString())}</div>
          {series.map((s) => {
            const p = s.pts.find((q) => q[0] === hover.t);
            return (
              <div key={s.code}>
                <span className="sw" style={{ background: `var(--s${s.slot})` }} />{s.label}: <b>{p ? (pct ? fmtPct(p[1]) : fmt(p[1])) + (p[2] ? " (расчёт)" : "") : "—"}</b>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
