import { useCallback, useEffect, useState } from "react";
import { ArchiveTab } from "./components/ArchiveTab";
import { CouponTab } from "./components/CouponTab";
import { LeagueFilter } from "./components/LeagueFilter";
import { LeaguesTab } from "./components/LeaguesTab";
import { MatchesTab } from "./components/MatchesTab";
import { MovesTab } from "./components/MovesTab";
import { useCoupon } from "./coupon";
import type { DataSource } from "./data";
import { fmtDT } from "./format";
import { dispatchWorkflow, getToken } from "./github";
import type { Status } from "./types";

type Tab = "moves" | "drops" | "rises" | "coupon" | "archive" | "leagues";
const TABS: [Tab, string][] = [["moves", "Матчи"], ["drops", "Просадки"], ["rises", "Повышения"], ["coupon", "Купон"], ["archive", "Архив"], ["leagues", "Лиги"]];
const REFRESH_MS = 60_000; // перечитываем data.json / API раз в минуту — квоту это не тратит

function savedTab(): Tab {
  try {
    const t = localStorage.getItem("tab");
    if (TABS.some(([k]) => k === t)) return t as Tab;
  } catch { /* ignore */ }
  return "moves";
}

// Остаток квоты: один ключ — одно число; ключи по лигам — по каждой лиге
function quotaText(st: Status, titles: Record<string, string>) {
  const q = st.quotas && Object.keys(st.quotas).length > 1 ? st.quotas : null;
  if (!q) return st.quota?.remaining != null ? `квота: осталось ${st.quota.remaining}` : null;
  return "квота: " + Object.entries(q)
    .filter(([, v]) => v.remaining != null)
    .map(([k, v]) => `${k === "default" ? "общий ключ" : titles[k] ?? k} ${v.remaining}`).join(", ");
}

// Коротко — для телефона: число лиг и время снимков; полный текст открывается по нажатию
function metaShort(st: Status) {
  return `Лиг: ${st.tracked_sports.length} · снимок ${fmtDT(st.last_poll_at)} · следующий ${fmtDT(st.next_poll_at)}`;
}

function metaText(st: Status, titles: Record<string, string>, generatedAt?: string) {
  const p = st.policy;
  const parts = [`Лиг: ${st.tracked_sports.length}`, "новые матчи — каждые 15 мин"];
  if (p.summary) parts.push(`снимки: ${p.summary} (закрытие за ${Math.round(p.close_window_min ?? 60)} мин)`);
  else {
    if (p.league_mode) parts.push(`режим лиги: ${p.league_snapshot_markets === "h2h" ? "1X2" : "1X2 + основной тотал"} одним запросом на лигу`);
    parts.push(`снимки: раз в ${p.lines_far_hours} ч, за ${p.lines_near_window_hours} ч до матча — раз в ${p.lines_near_hours} ч, ` +
      `закрытие за ${Math.round(p.close_window_min ?? 60)} мин`);
  }
  parts.push(`последний снимок ${fmtDT(st.last_poll_at)}`, `следующий ${fmtDT(st.next_poll_at)}`);
  const quota = quotaText(st, titles);
  if (quota) parts.push(quota);
  if (generatedAt) parts.push(`данные собраны ${fmtDT(generatedAt)}`);
  return parts.join(" · ");
}

export function App({ source }: { source: DataSource }) {
  const [tab, setTab] = useState<Tab>(savedTab);
  const [league, setLeague] = useState<string>(() => { try { return localStorage.getItem("league") || ""; } catch { return ""; } });
  const [leagues, setLeagues] = useState<{ title: string; key: string; count: number }[]>([]);
  const [status, setStatus] = useState<Status | null>(null);
  const [version, setVersion] = useState(0);
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const picks = useCoupon();

  const onError = useCallback((m: string) => setMsg(m), []);
  const refresh = useCallback(async () => {
    try {
      await source.reload();
      setStatus(await source.status());
      setVersion((v) => v + 1);
    } catch (e) { setMsg("Ошибка: " + (e as Error).message); }
  }, [source]);

  useEffect(() => { refresh(); }, [refresh]);
  useEffect(() => {
    const id = setInterval(() => { if (tab !== "leagues") refresh(); }, REFRESH_MS);
    return () => clearInterval(id);
  }, [tab, refresh]);
  useEffect(() => { try { localStorage.setItem("tab", tab); } catch { /* ignore */ } }, [tab]);
  useEffect(() => { try { localStorage.setItem("league", league); } catch { /* ignore */ } }, [league]);
  // Лиги с предстоящими матчами — для переключателя
  useEffect(() => {
    source.events(false).then((evs) => {
      const counts = new Map<string, { key: string; count: number }>();
      evs.forEach((e) => counts.set(e.sport_title, { key: e.sport_key, count: (counts.get(e.sport_title)?.count ?? 0) + 1 }));
      setLeagues([...counts].map(([title, v]) => ({ title, ...v })).sort((a, b) => a.title.localeCompare(b.title)));
    }, () => {});
  }, [source, version]);
  const leagueFilter = leagues.some((l) => l.title === league) ? league : "";

  const repo = source.site?.repo ?? null;
  const pollNow = async () => {
    if (source.isStatic) {
      // С токеном — запускаем workflow сами, без токена — открываем его страницу на GitHub
      if (!getToken()) { window.open(`https://github.com/${repo}/actions/workflows/poll.yml`, "_blank", "noopener"); return; }
      const cost = status?.policy.league_mode ? "2 запроса квоты на каждую лигу" : "≈ 3 запроса квоты на каждый матч";
      if (!confirm(`Снять все предстоящие матчи сейчас, вне расписания? Это ${cost}.`)) return;
    }
    setBusy(true);
    try {
      if (source.isStatic) {
        await dispatchWorkflow(repo, source.site?.ref ?? null, { force: true });
        setMsg("Опрос запущен, сайт обновится примерно через 1–2 минуты.");
      } else {
        await source.pollNow();
        await refresh();
      }
    } catch (e) { setMsg("Не удалось запустить опрос: " + (e as Error).message); }
    finally { setBusy(false); }
  };

  const banners = [
    status?.demo && !source.isStatic ? "Демо-режим: ключ ODDS_API_KEY не задан — коэффициенты не меняются." : null,
    status?.last_error ? "Ошибка последнего опроса: " + status.last_error : null,
    msg,
  ].filter(Boolean) as string[];

  return (
    <>
      <header className="top">
        <h1>Pinnacle · движение линии</h1>
        <nav>
          {TABS.map(([k, title]) => (
            <button key={k} className={tab === k ? "active" : ""} onClick={() => { setMsg(null); setTab(k); }}>
              {title}{k === "coupon" && picks.length > 0 && <span className="badge">{picks.length}</span>}
            </button>
          ))}
        </nav>
        <div className="spacer" />
        {(() => {
          const full = status ? metaText(status, Object.fromEntries((source.site?.sports ?? []).map((x) => [x.key, x.title])), source.site?.generated_at) : "Загрузка…";
          return (
            <div className="meta">
              <span className="metaFull">{full}</span>
              <details className="metaShort">
                <summary>{status ? metaShort(status) : "Загрузка…"}</summary>
                <div>{full}</div>
              </details>
            </div>
          );
        })()}
        {(!source.isStatic || repo) && (
          <button className="pollBtn" onClick={pollNow} disabled={busy} title="Внеплановый снимок всех лиг (тратит квоту)">
            {busy ? "Запускаю…" : "Опросить сейчас"}
          </button>
        )}
      </header>
      {banners.length > 0 && <div className="banner">{banners.map((b, i) => <div key={i}>{b}</div>)}</div>}
      <main>
        {tab !== "leagues" && tab !== "coupon" && tab !== "archive" && <LeagueFilter leagues={leagues} value={leagueFilter} onChange={setLeague} />}
        {tab === "moves" && <MatchesTab source={source} version={version} policy={status?.policy ?? {}} status={status} league={leagueFilter} onError={onError} />}
        {tab === "drops" && <MovesTab kind="drops" source={source} version={version} league={leagueFilter} onError={onError} />}
        {tab === "rises" && <MovesTab kind="rises" source={source} version={version} league={leagueFilter} onError={onError} />}
        {tab === "coupon" && <CouponTab source={source} version={version} onError={onError} />}
        {tab === "archive" && <ArchiveTab source={source} version={version} onError={onError} />}
        {tab === "leagues" && status && (
          <LeaguesTab source={source} tracked={status.tracked_sports} policy={status.policy} onSaved={refresh} />
        )}
      </main>
    </>
  );
}
