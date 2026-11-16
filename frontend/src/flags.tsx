// Флаг страны лиги рядом с её названием (SVG из пакета flag-icons, в сборку попадают только эти флаги)
const FILES = import.meta.glob(
  "../node_modules/flag-icons/flags/4x3/{gb-eng,gb-sct,ru,es,it,de,fr,nl,pt,tr,be,at,ch,dk,se,no,pl,gr,jp,kr,au,ar,br,cl,mx,us,eu}.svg",
  { eager: true, query: "?url", import: "default" },
) as Record<string, string>;
const URLS: Record<string, string> = Object.fromEntries(
  Object.entries(FILES).map(([path, url]) => [path.split("/").pop()!.replace(".svg", ""), url]),
);

// Ключ лиги API -> код флага. Англия и Шотландия — свои флаги, еврокубки и Лига наций — флаг Европы
const EXACT: Record<string, string> = { soccer_epl: "gb-eng", soccer_efl_champ: "gb-eng", soccer_spl: "gb-sct" };
const BY_COUNTRY: [string, string][] = [
  ["england", "gb-eng"], ["russia", "ru"], ["spain", "es"], ["italy", "it"], ["germany", "de"], ["france", "fr"],
  ["netherlands", "nl"], ["portugal", "pt"], ["turkey", "tr"], ["belgium", "be"], ["austria", "at"],
  ["switzerland", "ch"], ["denmark", "dk"], ["sweden", "se"], ["norway", "no"], ["poland", "pl"], ["greece", "gr"],
  ["japan", "jp"], ["korea", "kr"], ["australia", "au"], ["argentina", "ar"], ["brazil", "br"], ["chile", "cl"],
  ["mexico", "mx"], ["usa", "us"], ["uefa", "eu"],
];

export function flagCode(sportKey: string | undefined): string | null {
  if (!sportKey) return null;
  if (EXACT[sportKey]) return EXACT[sportKey];
  const rest = sportKey.replace(/^soccer_/, "");
  return BY_COUNTRY.find(([country]) => rest.startsWith(country + "_") || rest === country)?.[1] ?? null;
}

export function Flag({ sportKey, title }: { sportKey?: string; title?: string }) {
  const code = flagCode(sportKey);
  const url = code ? URLS[code] : undefined;
  if (!url) return null;
  return <img className="flag" src={url} alt="" title={title} width={16} height={12} />;
}
