// Запуск workflow с сайта (GitHub Pages): fine-grained токен с правом Actions: Read and write.
// Токен хранится только в этом браузере (localStorage) и отправляется только на api.github.com.
const TOKEN_KEY = "gh_token";

export function getToken(): string {
  try { return localStorage.getItem(TOKEN_KEY) || ""; } catch { return ""; }
}
export function saveToken(v: string): boolean {
  try { localStorage.setItem(TOKEN_KEY, v); return true; } catch { return false; }
}
export function forgetToken() {
  try { localStorage.removeItem(TOKEN_KEY); } catch { /* ignore */ }
}

export async function dispatchWorkflow(repo: string | null, ref: string | null, inputs: Record<string, string | boolean>) {
  const token = getToken();
  if (!token) throw new Error("сначала вставьте токен GitHub на вкладке «Лиги» и нажмите «Запомнить»");
  if (!repo) throw new Error("неизвестен репозиторий");
  const r = await fetch(`https://api.github.com/repos/${repo}/actions/workflows/poll.yml/dispatches`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, Accept: "application/vnd.github+json", "Content-Type": "application/json" },
    body: JSON.stringify({ ref: ref || "master", inputs }),
  });
  if (r.status === 204) return;
  const body = await r.json().catch(() => ({}));
  const hints: Record<number, string> = {
    401: "токен недействителен или истёк",
    403: "у токена нет права Actions: Read and write для этого репозитория",
    404: "токен не видит репозиторий — проверьте Repository access",
  };
  throw new Error(hints[r.status] || (body as { message?: string }).message || `HTTP ${r.status}`);
}
