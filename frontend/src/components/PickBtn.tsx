import { hasPick, togglePick, useCoupon, type MatchMeta } from "../coupon";
import type { Outcome } from "../types";

// Кнопка «в купон»: ＋ — добавить, ✓ — уже в купоне (нажатие убирает)
export function PickBtn({ ev, o }: { ev: MatchMeta; o: Outcome }) {
  useCoupon();
  const on = hasPick(ev.id, o.code);
  return (
    <button className={`pickBtn ${on ? "on" : ""}`} title={on ? "В купоне — убрать" : "Добавить в купон"}
      onClick={(e) => { e.stopPropagation(); togglePick(ev, o); }}>{on ? "✓" : "+"}</button>
  );
}
