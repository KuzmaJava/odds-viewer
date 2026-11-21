import type { ArchiveEntry } from "./types";

// Поиск по команде: названия в API латиницей, поэтому кириллицу переводим в латиницу
// («Ростов» → rostov, «Крылья» ≈ Kryliya), y/i и j/i считаем одной буквой
const TR: Record<string, string> = {
  а: "a", б: "b", в: "v", г: "g", д: "d", е: "e", ё: "e", ж: "zh", з: "z", и: "i", й: "i", к: "k", л: "l", м: "m",
  н: "n", о: "o", п: "p", р: "r", с: "s", т: "t", у: "u", ф: "f", х: "kh", ц: "ts", ч: "ch", ш: "sh", щ: "shch",
  ъ: "", ы: "y", ь: "", э: "e", ю: "yu", я: "ya",
};

export function norm(s: string): string {
  return [...s.toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "")]
    .map((c) => TR[c] ?? c).join("")
    .replace(/[yj]/g, "i").replace(/i+/g, "i").replace(/[^a-z0-9]+/g, " ").trim();
}

export interface ArchiveFilter { q: string; league: string; month: string }

/** Матчи архива под фильтр, новые сверху. Каждое слово запроса должно найтись в названиях команд. */
export function filterArchive(list: ArchiveEntry[], f: ArchiveFilter): ArchiveEntry[] {
  const words = norm(f.q).split(" ").filter(Boolean);
  return list
    .filter((e) => (!f.league || e.sport_title === f.league) && (!f.month || e.month === f.month))
    .filter((e) => {
      if (!words.length) return true;
      const name = norm(`${e.home} ${e.away}`);
      return words.every((w) => name.includes(w));
    })
    .sort((a, b) => b.commence_time.localeCompare(a.commence_time));
}

/** Изменение от открытия до закрытия, %. */
export const closePct = (v?: [number | null, number | null]) =>
  v && v[0] && v[1] != null ? Math.round((v[1] / v[0] - 1) * 10000) / 100 : null;
