import { Flag } from "../flags";

// Переключатель лиг над вкладками «Матчи», «Просадки», «Повышения»: все или одна лига
export function LeagueFilter({ leagues, value, onChange }: {
  leagues: { title: string; key: string; count: number }[]; value: string; onChange: (title: string) => void;
}) {
  if (leagues.length < 2) return null;
  const total = leagues.reduce((s, l) => s + l.count, 0);
  return (
    <div className="toolbar leagueFilter">
      <span className="muted">Лига:</span>
      <button className={`histBtn ${value === "" ? "on" : ""}`} onClick={() => onChange("")}>Все · {total}</button>
      {leagues.map((l) => (
        <button key={l.title} className={`histBtn ${value === l.title ? "on" : ""}`} onClick={() => onChange(l.title)}>
          <Flag sportKey={l.key} />{l.title} · {l.count}
        </button>
      ))}
    </div>
  );
}
