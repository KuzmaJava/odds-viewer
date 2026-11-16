import type { EventMove, MoveRow } from "./types";

// То же, что app/movement.py: исходы, сдвинувшиеся от открытия минимум на minPct % вниз или вверх,
// самые сильные — первыми
export function moveRows(events: EventMove[], minPct: number, rising: boolean): MoveRow[] {
  const t = Math.abs(minPct);
  const res: MoveRow[] = [];
  for (const ev of events) for (const o of ev.outcomes) {
    const ch = o.change_pct;
    if (ch == null || ch === 0) continue;
    if (rising ? ch >= t : ch <= -t) {
      res.push({
        ...o, event_id: ev.id, sport_title: ev.sport_title, home: ev.home, away: ev.away,
        commence_time: ev.commence_time, snapshots: ev.snapshots, first_ts: ev.first_ts, last_ts: ev.last_ts,
      });
    }
  }
  return res.sort((a, b) => (rising ? b.change_pct! - a.change_pct! : a.change_pct! - b.change_pct!));
}
