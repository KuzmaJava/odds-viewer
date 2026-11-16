import { useEffect, useState } from "react";
import type { DataSource } from "../data";
import { Flag } from "../flags";
import { dispatchWorkflow, forgetToken, getToken, saveToken } from "../github";
import type { Policy, Sport } from "../types";

// Сохранённый, но ещё не применённый выбор лиг: на GitHub Pages новый список попадает в data.json
// только после запуска workflow (1–2 мин), поэтому до тех пор показываем выбор из браузера
const PENDING_KEY = "pending_sports";
const PENDING_TTL_MS = 10 * 60_000;
const sameSet = (a: string[], b: string[]) => a.length === b.length && a.every((x) => b.includes(x));

function readPending(tracked: string[]): string[] | null {
  try {
    const p = JSON.parse(localStorage.getItem(PENDING_KEY) || "null") as { list: string[]; at: number } | null;
    if (!p || Date.now() - p.at > PENDING_TTL_MS || sameSet(p.list, tracked)) {
      localStorage.removeItem(PENDING_KEY);
      return null;
    }
    return p.list;
  } catch { return null; }
}

function estimate(n: number, p: Policy) {
  if (p.league_mode) {
    const per = (p.league_snapshot_markets ?? "h2h,totals").split(",").length;
    const far = 24 / (p.lines_far_hours ?? 8) * 30;   // фоновые снимки лиги в месяц
    const perLeague = Math.round((far + 65) * per);  // + игровые дни (каждые 3 ч) и закрытия
    return `Выбрано лиг: ${n}. Режим лиги: снимок = ${per} запроса на всю лигу; клубная лига ≈ ${perLeague}/мес, ` +
      `всего ≈ ${n * perLeague}/мес. Бесплатный план — 500/мес.`;
  }
  const perMatch = 5 + 8 * 3 + 4 * 3 + 5;
  return `Выбрано лиг: ${n}. Полный режим: ≈ ${perMatch} запросов на матч; лига с ~40 матчами ≈ ${40 * perMatch}/мес.`;
}

export function LeaguesTab({ source, tracked, policy, onSaved }: {
  source: DataSource; tracked: string[]; policy: Policy; onSaved: () => void;
}) {
  const [sports, setSports] = useState<Sport[] | null>(null);
  const pending = source.isStatic ? readPending(tracked) : null;
  const [checked, setChecked] = useState<Set<string>>(new Set(pending ?? tracked));
  const [hint, setHint] = useState<string | null>(pending ? "Сохранено, применяется — сайт обновится через 1–2 минуты." : null);
  const [busy, setBusy] = useState(false);
  const [tokenInput, setTokenInput] = useState("");
  const [hasToken, setHasToken] = useState(!!getToken());

  useEffect(() => { source.sports().then(setSports, (e: Error) => setHint("Ошибка: " + e.message)); }, [source]);
  useEffect(() => {
    const p = source.isStatic ? readPending(tracked) : null;
    setChecked(new Set(p ?? tracked));
    if (!p) setHint((h) => (h?.startsWith("Сохранено, применяется") ? "Изменения применены ✓" : h));
  }, [tracked, source]);

  const repo = source.site?.repo ?? null;
  const save = async () => {
    const list = [...checked];
    if (!list.length) { setHint("Выберите хотя бы одну лигу."); return; }
    setBusy(true);
    try {
      if (source.isStatic) {
        await dispatchWorkflow(repo, source.site?.ref ?? null, { sports: list.join(",") });
        try { localStorage.setItem(PENDING_KEY, JSON.stringify({ list, at: Date.now() })); } catch { /* ignore */ }
        setHint("Сохранено, применяется — сайт обновится через 1–2 минуты.");
      } else {
        await source.setTracked(list);
        setHint("Сохранено ✓");
        onSaved();
      }
    } catch (e) {
      setHint("Не удалось сохранить: " + (e as Error).message);
    } finally { setBusy(false); }
  };

  return (
    <section>
      <div className="toolbar">
        <button onClick={save} disabled={busy}>Сохранить</button>
        <span className="muted">{estimate(checked.size, policy)}</span>
      </div>
      {source.isStatic && repo && (
        <>
          <div className="toolbar">
            <label>Токен GitHub
              <input type="password" placeholder="github_pat_…" autoComplete="off" style={{ width: 260 }}
                value={tokenInput} onChange={(e) => setTokenInput(e.target.value)} />
            </label>
            <button onClick={() => { if (tokenInput.trim() && saveToken(tokenInput.trim())) { setTokenInput(""); setHasToken(true); } }}>Запомнить</button>
            <button onClick={() => { forgetToken(); setHasToken(false); }}>Забыть</button>
            <span className="muted">{hasToken ? "токен сохранён в этом браузере" : "токен не задан"}</span>
          </div>
          <p className="muted">
            Нужен fine-grained токен: <i>Repository access</i> — только этот репозиторий, <i>Permissions</i> — <b>Actions: Read and write</b>.
            Он хранится только в этом браузере и отправляется только на api.github.com. С таким токеном можно лишь запускать опрос — ни код, ни ключ API он не открывает.{" "}
            <a href="https://github.com/settings/personal-access-tokens/new" target="_blank" rel="noopener">Создать токен</a>
            {" · "}
            <a href={`https://github.com/${repo}/actions/workflows/poll.yml`} target="_blank" rel="noopener">Ход опросов</a>
          </p>
        </>
      )}
      {hint && <p className="muted">{hint}</p>}
      <div className="leagues">
        {!sports ? "Загрузка…" : [...sports].sort((a, b) => a.title.localeCompare(b.title)).map((s) => (
          <label key={s.key}>
            <input type="checkbox" checked={checked.has(s.key)} onChange={(e) => {
              const n = new Set(checked);
              if (e.target.checked) n.add(s.key); else n.delete(s.key);
              setChecked(n);
            }} /> <Flag sportKey={s.key} />{s.title}
          </label>
        ))}
      </div>
    </section>
  );
}
