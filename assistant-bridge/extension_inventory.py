"""Owner-private list of Android extensions, excluding flagged or restricted ones."""
from __future__ import annotations

import json
import sqlite3
import time

from command_queue import PACKAGE, BLOCKED_SOURCE_NAMES


def _safe_item(item):
    if not isinstance(item, dict):
        return None
    package = item.get("package_name")
    name = item.get("name")
    installed = item.get("installed")
    if (
        not isinstance(package, str) or len(package) > 180
        or not PACKAGE.fullmatch(package)
        or not package.startswith("eu.kanade.tachiyomi.extension.")
        or any(flag in package.casefold() for flag in BLOCKED_SOURCE_NAMES)
        or not isinstance(name, str) or not (1 <= len(name) <= 120)
        or any(flag in name.casefold() for flag in BLOCKED_SOURCE_NAMES)
        or type(installed) is not bool
    ):
        return None
    return {"package_name": package, "name": name, "installed": installed}


def store(db_path, device_id: str, raw_items: list[dict]) -> int:
    if not isinstance(raw_items, list) or len(raw_items) > 400:
        raise ValueError("Invalid extensions snapshot")
    clean = []
    seen = set()
    for item in raw_items:
        entry = _safe_item(item)
        if entry is not None and (entry["package_name"], entry["installed"]) not in seen:
            clean.append(entry)
            seen.add((entry["package_name"], entry["installed"]))
    with sqlite3.connect(db_path) as db:
        db.execute("""CREATE TABLE IF NOT EXISTS extension_inventory (
            device_id TEXT PRIMARY KEY, payload_json TEXT NOT NULL, updated INTEGER NOT NULL
        )""")
        db.execute("INSERT OR REPLACE INTO extension_inventory VALUES (?,?,?)",
                   (device_id, json.dumps(clean), int(time.time())))
    return len(clean)


def get(db_path, device_id: str) -> dict:
    with sqlite3.connect(db_path) as db:
        db.execute("""CREATE TABLE IF NOT EXISTS extension_inventory (
            device_id TEXT PRIMARY KEY, payload_json TEXT NOT NULL, updated INTEGER NOT NULL
        )""")
        row = db.execute("SELECT payload_json,updated FROM extension_inventory WHERE device_id=?", (device_id,)).fetchone()
    if row is None:
        return {"device_id": device_id, "updated": None, "extensions": []}
    return {"device_id": device_id, "updated": row[1], "extensions": json.loads(row[0])}


def revoke_all(db_path):
    if db_path.exists():
        with sqlite3.connect(db_path) as db:
            db.execute("DROP TABLE IF EXISTS extension_inventory")
