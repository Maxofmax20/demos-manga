"""Device-opt-in-only unattended library/extension metadata sync requests.

Separate from the manually approved command queue. No general Android actions
can be executed through this queue. All requests are authenticated and device-bound.
"""
import re
import sqlite3
import time
import uuid
from pathlib import Path

ID = re.compile(r"^[0-9a-f]{32}$")
TTL = 24 * 60 * 60


def initialize(path: Path):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with sqlite3.connect(path) as db:
        db.execute("""CREATE TABLE IF NOT EXISTS remote_sync_consent (
            device_id TEXT PRIMARY KEY, enabled INTEGER NOT NULL,
            updated INTEGER NOT NULL)""")
        db.execute("""CREATE TABLE IF NOT EXISTS remote_sync_requests (
            request_id TEXT PRIMARY KEY, device_id TEXT NOT NULL,
            state TEXT NOT NULL, created INTEGER NOT NULL,
            updated INTEGER NOT NULL, expires INTEGER NOT NULL)""")
        db.execute("CREATE INDEX IF NOT EXISTS idx_remote_sync_device ON remote_sync_requests(device_id,state,expires)")


def set_consent(path: Path, device_id: str, enabled: bool):
    if type(enabled) is not bool:
        raise ValueError("Consent must be boolean")
    now = int(time.time())
    with sqlite3.connect(path) as db:
        db.execute("INSERT OR REPLACE INTO remote_sync_consent VALUES (?,?,?)",
                   (device_id, int(enabled), now))
        if not enabled:
            db.execute("UPDATE remote_sync_requests SET state='cancelled', updated=? "
                       "WHERE device_id=? AND state IN ('pending','running')",
                       (now, device_id))
    return {"device_id": device_id, "enabled": enabled}


def enabled(path: Path, device_id: str):
    with sqlite3.connect(path) as db:
        row = db.execute("SELECT enabled FROM remote_sync_consent WHERE device_id=?", (device_id,)).fetchone()
    return bool(row and row[0])


def request(path: Path, device_id: str):
    now = int(time.time())
    with sqlite3.connect(path) as db:
        db.execute("BEGIN IMMEDIATE")
        db.execute("UPDATE remote_sync_requests SET state='expired',updated=? "
                   "WHERE state IN ('pending','running') AND expires<=?", (now, now))
        consent = db.execute("SELECT enabled FROM remote_sync_consent WHERE device_id=?", (device_id,)).fetchone()
        if not consent or not consent[0]:
            raise ValueError("Remote sync is not enabled on this phone; opt in using Demos Manga settings")
        existing = db.execute("SELECT request_id FROM remote_sync_requests "
                              "WHERE device_id=? AND state IN ('pending','running') AND expires>? "
                              "ORDER BY created DESC LIMIT 1", (device_id, now)).fetchone()
        if existing:
            return lookup(path, existing[0], connection=db)
        request_id = uuid.uuid4().hex
        db.execute("INSERT INTO remote_sync_requests VALUES (?,?,?,?,?,?)",
                   (request_id, device_id, "pending", now, now, now + TTL))
        return lookup(path, request_id, connection=db)


def lookup(path: Path, request_id: str, connection=None):
    if not isinstance(request_id, str) or not ID.fullmatch(request_id):
        return None
    def _lookup(db):
        row = db.execute("SELECT device_id,state,updated,expires FROM remote_sync_requests "
                         "WHERE request_id=?", (request_id,)).fetchone()
        if row is None:
            return None
        dev, state, updated, expires = row
        if state in {"pending","running"} and expires <= time.time():
            state = "expired"
        return {"request_id": request_id, "device_id": dev,
                "state": state, "updated": updated, "expires": expires}
    if connection is not None:
        return _lookup(connection)
    with sqlite3.connect(path) as db:
        return _lookup(db)


def claim(path: Path, device_id: str):
    if not enabled(path, device_id):
        return None
    now = int(time.time())
    with sqlite3.connect(path) as db:
        db.execute("BEGIN IMMEDIATE")
        row = db.execute("SELECT request_id FROM remote_sync_requests "
                         "WHERE device_id=? AND state='pending' AND expires>? "
                         "ORDER BY created ASC LIMIT 1", (device_id, now)).fetchone()
        if row is None:
            return None
        request_id = row[0]
        db.execute("UPDATE remote_sync_requests SET state='running',updated=? "
                   "WHERE request_id=? AND state='pending'", (now, request_id))
        return lookup(path, request_id, connection=db)


def finish(path: Path, device_id: str, request_id: str, state: str):
    if state not in {"completed","failed"} or not ID.fullmatch(request_id or ""):
        raise ValueError("Invalid sync result")
    now = int(time.time())
    with sqlite3.connect(path) as db:
        updated = db.execute("UPDATE remote_sync_requests SET state=?, updated=? "
                             "WHERE request_id=? AND device_id=? AND state='running' AND expires>?",
                             (state, now, request_id, device_id, now))
    return updated.rowcount == 1


def revoke_all(path: Path):
    if not path.exists():
        return
    with sqlite3.connect(path) as db:
        db.execute("DELETE FROM remote_sync_consent")
        db.execute("DELETE FROM remote_sync_requests")
