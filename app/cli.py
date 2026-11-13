"""Запуск трекера без сервера — для GitHub Actions (cron раз в час).

    python -m app.cli run   [--force]   # опросить лиги, которым пора, обновить state и собрать сайт
    python -m app.cli build             # только собрать сайт из state (без запросов к API)

Состояние хранится в JSON-файле (data/state.json), который коммитится в репозиторий.
"""
import argparse
import json
import os
import re
import shutil
import sys
from datetime import timedelta
from pathlib import Path

from app.config import build_client, build_poller, env_sports
from app.export import site_data
from app.poller import iso, utc_now
from app.store import Store

ROOT = Path(__file__).resolve().parent.parent
KEEP_DAYS = 14          # начавшиеся матчи храним две недели, потом удаляем из state
CRON_SLACK_MIN = 20     # допуск: cron стартует раз в час и может опаздывать


def write_state(store: Store, path: Path) -> None:
    data = store.dump(drop_commenced_before=iso(utc_now() - timedelta(days=KEEP_DAYS)))
    # По строке на матч — чтобы diff в git оставался читаемым и маленьким
    lines = ['{"version": 1,', f'"settings": {json.dumps(data["settings"], ensure_ascii=False, sort_keys=True)},', '"events": [']
    lines.append(",\n".join(json.dumps(e, ensure_ascii=False) for e in data["events"]))
    lines.append("]}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def build_site(poller, client, out: Path, sports) -> None:
    out.mkdir(parents=True, exist_ok=True)
    shutil.copy(ROOT / "static" / "index.html", out / "index.html")
    data = site_data(poller, client.demo, sports, os.getenv("GITHUB_REPOSITORY"),
                     os.getenv("DEFAULT_REF") or os.getenv("GITHUB_REF_NAME"))
    (out / "data.json").write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")


def parse_sports(raw: str):
    """'a, b' -> ['a', 'b']; пустая строка -> None. Ключи лиг: только a-z, 0-9 и _."""
    if not raw or not raw.strip():
        return None
    keys = sorted({k.strip() for k in raw.split(",") if k.strip()})
    bad = [k for k in keys if not re.fullmatch(r"[a-z0-9_]+", k)]
    if bad:
        raise SystemExit(f"Некорректные ключи лиг: {bad}")
    return keys


def set_output(name: str, value: str) -> None:
    gh_out = os.getenv("GITHUB_OUTPUT")
    if gh_out:
        with open(gh_out, "a") as f:
            f.write(f"{name}={value}\n")


def main(argv=None) -> int:
    try:
        from dotenv import load_dotenv
        load_dotenv()
    except ImportError:
        pass

    ap = argparse.ArgumentParser()
    ap.add_argument("command", choices=["run", "build"])
    ap.add_argument("--force", action="store_true", help="опросить все лиги, не глядя на расписание")
    ap.add_argument("--state", default=str(ROOT / "data" / "state.json"))
    ap.add_argument("--out", default=str(ROOT / "_site"))
    ap.add_argument("--sports", default="", help="новый список лиг через запятую (с сайта, через workflow_dispatch)")
    args = ap.parse_args(argv)

    state_path, out = Path(args.state), Path(args.out)
    store = Store(":memory:")
    if state_path.exists():
        store.load(json.loads(state_path.read_text(encoding="utf-8")))

    client = build_client()
    poller = build_poller(client, store)
    sports = store.get_setting("sports_catalog", [])
    changed = args.command == "build"

    # Откуда берём список лиг: 1) с сайта (--sports), сохраняется в state;
    # 2) ранее сохранённый с сайта; 3) переменная репозитория TRACK_SPORTS; 4) soccer_epl.
    new_sports = parse_sports(args.sports)
    if new_sports is not None:
        if new_sports != poller.sports or not store.get_setting("tracked_sports_from_ui"):
            changed = True
        poller.sports = new_sports
        store.set_setting("tracked_sports_from_ui", True)
    elif not store.get_setting("tracked_sports_from_ui") and env_sports():
        poller.sports = env_sports()

    if args.command == "run":
        if client.demo:
            print("ODDS_API_KEY не задан", file=sys.stderr)
            return 1
        now = utc_now()
        due = poller.sports if args.force else poller.due_sports(now, timedelta(minutes=CRON_SLACK_MIN))
        print(f"Отслеживаемые лиги: {poller.sports}; к опросу: {due or 'нет'}")
        if due:
            result = poller.poll_once(due)
            print(json.dumps(result, ensure_ascii=False))
            try:  # список лиг — бесплатный запрос, нужен странице для вкладки «Лиги»
                sports = [s for s in client.sports() if s.get("active") and not s.get("has_outrights")
                          and s.get("group") == "Soccer"]
                store.set_setting("sports_catalog", sports)
            except Exception as e:  # noqa: BLE001
                print(f"Не удалось получить список лиг: {e}", file=sys.stderr)
            changed = True

    if changed and args.command == "run":
        write_state(store, state_path)
    if changed:
        build_site(poller, client, out, sports)
    set_output("changed", "true" if changed else "false")
    return 0


if __name__ == "__main__":
    sys.exit(main())
