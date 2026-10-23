"""Persistent, owner-scoped *proposals* for approved phone-side manga commands.

Never executes Android operations on the server. Installed APK prompts the phone
owner before acting; proposals expire and are tied to one paired device.
"""
from __future__ import annotations

import json
import re
import sqlite3
import time
import uuid
from pathlib import Path

VALID_ACTIONS = frozenset({
    "mark_read", "library_set", "refresh_chapters", "set_setting",
    "download_chapter", "open_reader", "open_screen",
    "install_extension", "uninstall_extension", "library_add",
})
BLOCKED_SOURCE_NAMES = ("nsfw", "adult", "hentai", "porn", "erotic", "18plus", "18+", "smut")
HEX_ID = re.compile(r"^[0-9a-f]{24}$")
PACKAGE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:[.][A-Za-z][A-Za-z0-9_]*){2,}$")
TTL_SECONDS = 30 * 60
PENDING_LIMIT = 20


def _is_ident(value):
    return isinstance(value, str) and bool(HEX_ID.fullmatch(value))


def validate_command(action: str, params: dict) -> dict:
    if action not in VALID_ACTIONS or not isinstance(params, dict):
        raise ValueError("Unknown remote action")
    expected = {
        "mark_read": ({"manga_id", "read"}, {"chapter_id"}),
        "library_set": ({"manga_id", "favorite"}, set()),
        "refresh_chapters": ({"manga_id"}, set()),
        "set_setting": ({"name", "value"}, set()),
        "download_chapter": ({"manga_id", "chapter_id"}, set()),
        "open_reader": ({"manga_id", "chapter_id"}, set()),
        "open_screen": ({"screen"}, set()),
        "install_extension": ({"package_name"}, set()),
        "uninstall_extension": ({"package_name"}, set()),
        "library_add": ({"source_id", "source_url"}, set()),
    }[action]
    required, optional = expected
    if not required <= params.keys() or not params.keys() <= required | optional:
        raise ValueError("Unexpected or missing action fields")
    if "manga_id" in params and not _is_ident(params["manga_id"]):
        raise ValueError("Invalid manga identifier")
    if "chapter_id" in params and not _is_ident(params["chapter_id"]):
        raise ValueError("Invalid chapter identifier")
    for key in ("read", "favorite"):
        if key in params and type(params[key]) is not bool:
            raise ValueError("Expected boolean")
    if action == "set_setting":
        if params["name"] not in {"dark_mode", "wifi_only_downloads"} or type(params["value"]) is not bool:
            raise ValueError("Setting is not remotely manageable")
    if action == "open_screen" and params["screen"] not in {"library", "browse", "extensions", "settings"}:
        raise ValueError("Screen is not allowed")
    if action in {"install_extension", "uninstall_extension"}:
        package = params["package_name"]
        if not isinstance(package, str) or len(package) > 180 or not PACKAGE.fullmatch(package):
            raise ValueError("Invalid extension package")
        if not package.startswith("eu.kanade.tachiyomi.extension.") or any(n in package.casefold() for n in BLOCKED_SOURCE_NAMES):
            raise ValueError("Only compatible, non-restricted extension package names are accepted")
    if action == "library_add":
        if type(params["source_id"]) is not int or params["source_id"] <= 0:
            raise ValueError("Invalid source ID")
        url = params["source_url"]
        if not isinstance(url, str) or not 1 <= len(url) <= 300 or "://" in url or url.startswith("//") or any(ord(c) < 32 for c in url):
            raise ValueError("Source URLs must be relative source identifiers")
    return params


def initialize(path: Path):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with sqlite3.connect(path) as db:
        db.execute("""CREATE TABLE IF NOT EXISTS commands (
            command_id TEXT PRIMARY KEY, device_id TEXT NOT NULL,
            action TEXT NOT NULL, args_json TEXT NOT NULL,
            state TEXT NOT NULL, created INTEGER NOT NULL,
            expires INTEGER NOT NULL, result_json TEXT
        )""")
        db.execute("CREATE INDEX IF NOT EXISTS idx_device ON commands(device_id,state,expires)")


def create(path: Path, device_id: str, action: str, params: dict) -> dict:
    validate_command(action, params)
    now = int(time.time())
    with sqlite3.connect(path, timeout=5) as db:
        db.execute("UPDATE commands SET state='expired' WHERE expires < ? AND state = 'pending_confirmation'", (now,))
        count = db.execute(
            "SELECT COUNT(*) FROM commands WHERE device_id=? AND state='pending_confirmation' AND expires>?",
            (device_id, now),
        ).fetchone()[0]
        if count >= PENDING_LIMIT:
            raise ValueError("Too many pending actions")
        command_id = uuid.uuid4().hex
        db.execute(
            "INSERT INTO commands VALUES (?,?,?,?,?,?,?,?)",
            (command_id, device_id, action, json.dumps(params), "pending_confirmation", now, now + TTL_SECONDS, None),
        )
    return {"command_id": command_id, "device_id": device_id, "action": action, "state": "pending_confirmation", "expires": now + TTL_SECONDS}


def pending(path: Path, device_id: str) -> list[dict]:
    now = int(time.time())
    with sqlite3.connect(path) as db:
        rows = db.execute(
            "SELECT command_id,action,args_json,expires FROM commands WHERE device_id=? AND state='pending_confirmation' AND expires>? ORDER BY created ASC LIMIT 20",
            (device_id, now),
        ).fetchall()
    return [{"command_id": cid, "action": action, "args": json.loads(raw), "expires": expires} for cid, action, raw, expires in rows]


def finish(path: Path, device_id: str, command_id: str, state: str) -> bool:
    if state not in {"completed", "rejected", "failed"}:
        raise ValueError("Unsupported device result")
    if not isinstance(command_id, str) or not re.fullmatch(r"[0-9a-f]{32}", command_id):
        raise ValueError("Invalid command ID")
    now = int(time.time())
    with sqlite3.connect(path) as db:
        updated = db.execute(
            "UPDATE commands SET state=?, result_json=? WHERE command_id=? AND device_id=? AND state='pending_confirmation' AND expires>?",
            (state, json.dumps({"reported_at": now}), command_id, device_id, now),
        )
        return updated.rowcount == 1


def lookup(path: Path, command_id: str) -> dict | None:
    if not isinstance(command_id, str) or not re.fullmatch(r"[0-9a-f]{32}", command_id):
        return None
    with sqlite3.connect(path) as db:
        row = db.execute("SELECT action,state,expires,device_id FROM commands WHERE command_id=?", (command_id,)).fetchone()
    if row is None:
        return None
    action, state, expires, device_id = row
    if state == "pending_confirmation" and expires < time.time():
        state = "expired"
    return {"command_id": command_id, "action": action, "state": state, "expires": expires, "device_id": device_id}


def revoke_all(path: Path):
    if path.exists():
        with sqlite3.connect(path) as db:
            db.execute("DELETE FROM commands")
