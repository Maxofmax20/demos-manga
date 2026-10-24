"""Demos Manga: private SyncYomi-compatible backup receiver and metadata-only MCP."""
from __future__ import annotations

import hashlib
import hmac
import os
import json
import secrets
import time
import tempfile
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException, Request, Response
from fastapi.responses import JSONResponse
from mcp.server.mcpserver import MCPServer
import command_queue
import extension_inventory
import remote_sync
import categories
from chapters_metadata import chapters_from_backup
from reading_history import read_activity,save_observed_events

DATA_DIR = Path(os.getenv("MANGA_DATA_DIR", "/var/lib/demos-manga"))
SYNC_TOKEN = os.getenv("MANGA_SYNC_TOKEN", "")
MCP_TOKEN = os.getenv("MANGA_MCP_TOKEN", "")
MAX_BACKUP_BYTES = 16 * 1024 * 1024
BLOCKED_GENRES = frozenset({"adult", "mature", "explicit", "erotica", "smut", "nsfw", "hentai", "ecchi"})
BACKUP_PATH = DATA_DIR / "sync.protobuf"
READ_ACTIVITY_PATH = DATA_DIR / "reading-activity.json"
COMMAND_DB = DATA_DIR / "remote-commands.sqlite3"


def _varint(raw: bytes, offset: int) -> tuple[int, int]:
    result = 0
    for shift in range(0, 70, 7):
        if offset >= len(raw):
            raise ValueError("Truncated protobuf varint")
        b = raw[offset]
        offset += 1
        result |= (b & 0x7f) << shift
        if not (b & 0x80):
            return result, offset
    raise ValueError("Invalid protobuf varint")


def _fields(raw: bytes):
    """Parse a narrow subset of protobuf without decoding sensitive app settings."""
    pos = 0
    while pos < len(raw):
        tag, pos = _varint(raw, pos)
        number, wire = tag >> 3, tag & 7
        if not number:
            raise ValueError("Invalid protobuf field")
        if wire == 0:
            value, pos = _varint(raw, pos)
        elif wire == 1:
            if pos + 8 > len(raw):
                raise ValueError("Truncated 64-bit field")
            value, pos = raw[pos:pos + 8], pos + 8
        elif wire == 2:
            size, pos = _varint(raw, pos)
            if size > len(raw) - pos:
                raise ValueError("Truncated length-delimited field")
            value, pos = raw[pos:pos + size], pos + size
        elif wire == 5:
            if pos + 4 > len(raw):
                raise ValueError("Truncated 32-bit field")
            value, pos = raw[pos:pos + 4], pos + 4
        else:
            raise ValueError("Unsupported protobuf wire type")
        yield number, wire, value


def _utf8(raw: bytes) -> str:
    return raw.decode("utf-8", errors="replace").strip()


def extract_metadata(raw: bytes) -> list[dict]:
    """Read only library titles/genres/chapter flags from Komikku backup field 1."""
    result = []
    for field, wire, manga in _fields(raw):
        if field != 1 or wire != 2:
            continue
        title = ""
        source_id = None
        source_url = None
        genres = []
        chapters = 0
        read = 0
        for subfield, subwire, value in _fields(manga):
            if subfield == 1 and subwire == 0:
                source_id = value
            elif subfield == 2 and subwire == 2:
                source_url = _utf8(value)
            elif subfield == 3 and subwire == 2:
                title = _utf8(value)
            elif subfield == 7 and subwire == 2:
                genres.append(_utf8(value).casefold())
            elif subfield == 16 and subwire == 2:
                chapters += 1
                for cfield, cwire, cvalue in _fields(value):
                    if cfield == 4 and cwire == 0 and cvalue == 1:
                        read += 1
        if not title or any(g in BLOCKED_GENRES for g in genres):
            continue
        entry = {"title": title[:180], "chapters": chapters, "read": read, "unread": chapters - read}
        if source_id is not None and source_url:
            signed_source = source_id - (1 << 64) if source_id >= 1 << 63 else source_id
            entry["id"] = hashlib.sha256(f"{signed_source}|{source_url}".encode()).hexdigest()[:24]
        result.append(entry)
        if len(result) >= 10000:
            break
    return result


def get_library() -> list[dict]:
    try:
        if not BACKUP_PATH.is_file():
            return []
        return extract_metadata(BACKUP_PATH.read_bytes())
    except (OSError, ValueError):
        return []


# KMK -->
DEVICE_KEYS_PATH = DATA_DIR / "device-keys.json"


def _device_keys() -> list[dict]:
    try:
        return json.loads(DEVICE_KEYS_PATH.read_text())
    except FileNotFoundError:
        return []


def _save_device_keys(keys: list[dict]):
    DATA_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(dir=DATA_DIR, prefix=".devices-")
    try:
        with os.fdopen(fd, "w") as output:
            json.dump(keys, output)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(name, 0o600)
        os.replace(name, DEVICE_KEYS_PATH)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def _device_identity(token: str) -> str | None:
    if len(token) < 32 or len(token) > 256:
        return None
    digest = hashlib.sha256(token.encode()).hexdigest()
    for device in _device_keys():
        if device["expires"] > time.time() and hmac.compare_digest(device["hash"], digest):
            return device["id"]
    return None


def _device_token_matches(token: str) -> bool:
    return _device_identity(token) is not None
# KMK <--


def _token_matches(given: str, secret: str) -> bool:
    return len(secret) >= 32 and hmac.compare_digest(given, secret)


mcp = MCPServer("Demos Manga Library")


@mcp.tool()
def library_stats() -> dict:
    """Get aggregate reading progress from the user's synced manga library."""
    books = get_library()
    return {
        "titles": len(books),
        "total_chapters": sum(b["chapters"] for b in books),
        "read_chapters": sum(b["read"] for b in books),
        "unread_chapters": sum(b["unread"] for b in books),
        "has_sync_data": BACKUP_PATH.exists(),
    }


@mcp.tool()
def search_library(query: str = "", unread_only: bool = False, limit: int = 25) -> list[dict]:
    """Search your own synchronized manga library by title; no chapter content."""
    words = query.casefold().strip()
    cap = max(1, min(limit, 100))
    return [
        book for book in get_library()
        if (not words or words in book["title"].casefold()) and (not unread_only or book["unread"] > 0)
    ][:cap]


@asynccontextmanager
async def lifespan(app: FastAPI):
    DATA_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    if len(SYNC_TOKEN) < 32 or len(MCP_TOKEN) < 32 or SYNC_TOKEN == MCP_TOKEN:
        raise RuntimeError("Configure two distinct random MANGA_SYNC_TOKEN and MANGA_MCP_TOKEN values (32+ chars)")
    command_queue.initialize(COMMAND_DB)
    remote_sync.initialize(COMMAND_DB)
    async with mcp.session_manager.run():
        yield


app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)


@app.middleware("http")
async def require_auth(request: Request, call_next):
    path = request.url.path
    if path == "/health":
        return await call_next(request)
    if path.startswith("/api/sync/"):
        if not (_token_matches(request.headers.get("X-API-Token", ""), SYNC_TOKEN) or _device_token_matches(request.headers.get("X-API-Token", ""))):
            return JSONResponse({"detail": "Unauthorized"}, status_code=401)
    elif path.startswith("/api/device/"):
        if not _device_token_matches(request.headers.get("X-API-Token", "")):
            return JSONResponse({"detail": "Unauthorized"}, status_code=401, headers={"Cache-Control": "no-store"})
    elif path.startswith("/mcp") or path.startswith("/api/assistant/"):
        auth = request.headers.get("authorization", "")
        if not auth.startswith("Bearer ") or not _token_matches(auth[7:], MCP_TOKEN):
            return JSONResponse({"detail": "Unauthorized"}, status_code=401, headers={"Cache-Control": "no-store"})
    else:
        return JSONResponse({"detail": "Not found"}, status_code=404)
    return await call_next(request)


@app.get("/health")
def health():
    return {"status": "ok"}


# KMK -->
# Cloud-hosted ChatGPT Sites can call these metadata-only endpoints using a
# bearer credential stored in Sites protected configuration. Never expose
# raw backup bytes, credentials or chapter pages through this API.
@app.post("/api/assistant/pair")
def pair_device(response: Response):
    # Explicit owner action only; this endpoint is not an MCP tool.
    keys = [k for k in _device_keys() if k["expires"] > time.time()]
    if len(keys) >= 3:
        raise HTTPException(409, "Three devices already paired. Revoke an old device first.")
    token = secrets.token_urlsafe(48)
    device_id = secrets.token_hex(8)
    expires = int(time.time()) + 90 * 86400
    keys.append({"id": device_id, "hash": hashlib.sha256(token.encode()).hexdigest(), "expires": expires})
    _save_device_keys(keys)
    response.headers["Cache-Control"] = "no-store"
    return {"device_id": device_id, "token": token, "expires": expires, "server_url": "https://manga.demos25.me"}


@app.post("/api/assistant/unpair")
async def unpair_devices(response: Response):
    _save_device_keys([])
    command_queue.revoke_all(COMMAND_DB)
    extension_inventory.revoke_all(COMMAND_DB)
    remote_sync.revoke_all(COMMAND_DB)
    response.headers["Cache-Control"] = "no-store"
    return {"revoked": True}


@app.get("/api/assistant/stats")
def assistant_stats(response: Response):
    response.headers["Cache-Control"] = "no-store"
    return library_stats()


@app.get("/api/assistant/reading-history")
def assistant_reading_history(response: Response, limit: int = 60):
    """Private reader timeline: actual dated reads or observed sync changes.

    No chapter text/images, account secrets or full device backup returned.
    """
    if not 1 <= limit <= 100:
        raise HTTPException(400, "Invalid reading-history limit")
    response.headers["Cache-Control"] = "no-store"
    if not BACKUP_PATH.exists():
        return {"items": [], "datedCount": 0, "observedCount": 0,
                "hasHistoricalDates": False, "source": "Demos Manga private reader sync"}
    try:
        return read_activity(BACKUP_PATH.read_bytes(),READ_ACTIVITY_PATH,
                             _fields,_utf8,BLOCKED_GENRES,limit)
    except (OSError,ValueError):
        raise HTTPException(503,"Reader history snapshot unavailable")


@app.get("/api/assistant/search")
def assistant_search(response: Response, query: str = "", unread_only: bool = False, limit: int = 25):
    response.headers["Cache-Control"] = "no-store"
    return search_library(query=query, unread_only=unread_only, limit=limit)
# KMK <--


@app.get("/api/assistant/library")
def assistant_library(response: Response, offset: int = 0, limit: int = 50,
                      query: str = "", unread_only: bool = False):
    """Safe owner-only pagination for Cinema Library; same filtered metadata
    as search_library, with no raw backups, chapter content, or credentials.
    Protected by the existing /api/assistant/ bearer middleware.
    """
    if not 0 <= offset <= 10000 or not 1 <= limit <= 100 or len(query) > 200:
        raise HTTPException(400, "Invalid manga page")
    response.headers["Cache-Control"] = "no-store"
    books = get_library()
    needle = query.casefold().strip()
    matches = [book for book in books
               if (not needle or needle in book["title"].casefold())
               and (not unread_only or book["unread"] > 0)]
    return {
        "items": matches[offset:offset + limit],
        "total": len(matches),
        "offset": offset,
        "limit": limit,
        "has_sync_data": BACKUP_PATH.exists(),
    }


@app.get("/api/assistant/library-revision")
def assistant_library_revision(response: Response):
    """One authoritative progress revision for Cinema's cached manga mirror.

    Changes only after a real owner-phone SyncYomi upload; never claim a
    queued command altered chapter flags. Metadata only, no backup content.
    """
    response.headers["Cache-Control"] = "private, no-store"
    if not BACKUP_PATH.is_file():
        return {"has_sync_data": False, "revision": None,
                "source_updated_at": None, "titles": 0, "chapters": 0, "read": 0}
    raw = BACKUP_PATH.read_bytes()
    books = extract_metadata(raw)
    return {"has_sync_data": True, "revision": hashlib.sha256(raw).hexdigest(),
            "source_updated_at": BACKUP_PATH.stat().st_mtime,
            "titles": len(books), "chapters": sum(book["chapters"] for book in books),
            "read": sum(book["read"] for book in books)}


@app.get("/api/assistant/library-record")
def assistant_library_record(manga_id: str, response: Response):
    """Exact per-source manga identity, chapter flags and consented categories.

    A title-match cannot substitute for exact stable identity: different
    scans/translations can share a title but have different chapters.
    """
    if not command_queue.HEX_ID.fullmatch(manga_id):
        raise HTTPException(400, "Invalid manga identifier")
    response.headers["Cache-Control"] = "private, no-store"
    if not BACKUP_PATH.is_file():
        raise HTTPException(503, "Reader backup is not yet synchronized")
    raw = BACKUP_PATH.read_bytes()
    entry = next((item for item in extract_metadata(raw) if item.get("id") == manga_id), None)
    if entry is None:
        raise HTTPException(404, "Manga not found in permitted reader snapshot")
    chapters = chapters_from_backup(raw, manga_id, _fields, _utf8, BLOCKED_GENRES)
    if len(chapters) != entry["chapters"] or sum(bool(c["read"]) for c in chapters) != entry["read"]:
        raise HTTPException(409, "Reader chapter snapshot is incomplete; sync the phone again")
    category_ids = None
    category_revision = None
    category_updated_at = None
    try:
        devices = [d["id"] for d in _device_keys() if d["expires"] > time.time()]
        if len(devices) == 1:
            category_snapshot = categories.snapshot(COMMAND_DB, devices[0])
            matches = categories.page(COMMAND_DB, devices[0], manga_id=manga_id)["items"]
            if len(matches) == 1:
                category_ids = matches[0]["category_ids"]
                category_revision = category_snapshot["revision"]
                category_updated_at = category_snapshot["updated"]
    except (ValueError, PermissionError, OSError):
        # Missing opt-in category permissions never affect chapter truth.
        pass
    return {"id": manga_id, "title": entry["title"],
            "chapters": chapters, "total_chapters": entry["chapters"],
            "read_chapters": entry["read"], "unread_chapters": entry["unread"],
            "revision": hashlib.sha256(raw).hexdigest(),
            "source_updated_at": BACKUP_PATH.stat().st_mtime,
            "category_ids": category_ids, "category_revision": category_revision,
            "category_updated_at": category_updated_at,
            "authoritative": True}


@app.get("/api/assistant/chapters")
def assistant_chapters(manga_id: str, response: Response, offset: int = 0, limit: int = 100):
    response.headers["Cache-Control"] = "no-store"
    if not BACKUP_PATH.exists():
        return []
    chapters = chapters_from_backup(BACKUP_PATH.read_bytes(), manga_id, _fields, _utf8, BLOCKED_GENRES)
    return chapters[max(0, offset):max(0, offset) + max(1, min(limit, 100))]



# Unattended sync has its own deliberately narrow queue. Only the paired phone
# can enable consent, and no general command or extension mutation uses it.
@app.post("/api/assistant/remote-sync", status_code=202)
async def assistant_remote_sync_request(request: Request, response: Response):
    raw = await request.body()
    if len(raw) > 1024:
        raise HTTPException(413, "Sync request too large")
    try:
        body = json.loads(raw)
        if not isinstance(body, dict) or set(body) != {"device_id"}:
            raise ValueError("Only a paired device ID is accepted")
        device_id = body["device_id"]
        if not isinstance(device_id, str) or device_id not in [
            d["id"] for d in _device_keys() if d["expires"] > time.time()
        ]:
            raise ValueError("Unknown or expired paired device")
        result = remote_sync.request(COMMAND_DB, device_id)
    except (TypeError, ValueError, KeyError) as exc:
        raise HTTPException(400, str(exc)) from exc
    response.headers["Cache-Control"] = "no-store"
    return result


@app.get("/api/assistant/remote-sync/{request_id}")
def assistant_remote_sync_status(request_id: str, response: Response):
    response.headers["Cache-Control"] = "no-store"
    result = remote_sync.lookup(COMMAND_DB, request_id)
    if result is None:
        raise HTTPException(404, "Remote sync request not found")
    return result


@app.post("/api/device/remote-sync/consent")
async def device_remote_sync_consent(request: Request, response: Response):
    device_id = _device_identity(request.headers.get("X-API-Token", ""))
    if device_id is None:
        raise HTTPException(401, "Unauthorized")
    raw = await request.body()
    try:
        if len(raw) > 200:
            raise ValueError("Invalid consent request")
        body = json.loads(raw)
        if not isinstance(body, dict) or set(body) != {"enabled"}:
            raise ValueError("Invalid consent request")
        result = remote_sync.set_consent(COMMAND_DB, device_id, body["enabled"])
    except (ValueError, TypeError, KeyError) as exc:
        raise HTTPException(400, str(exc)) from exc
    response.headers["Cache-Control"] = "no-store"
    return result


@app.get("/api/device/remote-sync/next")
def device_remote_sync_next(request: Request, response: Response):
    device_id = _device_identity(request.headers.get("X-API-Token", ""))
    if device_id is None:
        raise HTTPException(401, "Unauthorized")
    response.headers["Cache-Control"] = "no-store"
    return {"request": remote_sync.claim(COMMAND_DB, device_id)}


@app.post("/api/device/remote-sync/{request_id}/result")
async def device_remote_sync_result(request_id: str, request: Request, response: Response):
    device_id = _device_identity(request.headers.get("X-API-Token", ""))
    if device_id is None:
        raise HTTPException(401, "Unauthorized")
    try:
        body = await request.json()
        if not isinstance(body, dict) or set(body) != {"state"}:
            raise ValueError("Invalid remote sync result")
        if not remote_sync.finish(COMMAND_DB, device_id, request_id, body["state"]):
            raise HTTPException(409, "Remote sync request expired or already finished")
    except (ValueError, TypeError, KeyError) as exc:
        raise HTTPException(400, str(exc)) from exc
    response.headers["Cache-Control"] = "no-store"
    return {"request_id": request_id, "state": body["state"]}


# KMK -->
# The server only stores requests. A compatible Android client must prompt
# the phone owner and explicitly report the result before any action occurs.
@app.get("/api/assistant/devices")
def assistant_devices(response: Response):
    response.headers["Cache-Control"] = "no-store"
    return [{"device_id": d["id"], "expires": d["expires"]} for d in _device_keys() if d["expires"] > time.time()]


@app.post("/api/assistant/commands", status_code=202)
async def assistant_create_command(request: Request, response: Response):
    raw = await request.body()
    if len(raw) > 4096:
        raise HTTPException(413, "Command request too large")
    try:
        content = json.loads(raw)
        if not isinstance(content, dict) or set(content) != {"device_id", "action", "args"}:
            raise ValueError("Invalid command envelope")
        device_id = content["device_id"]
        if not isinstance(device_id, str) or device_id not in [d["id"] for d in _device_keys() if d["expires"] > time.time()]:
            raise ValueError("Unknown or expired paired device")
        command_queue.validate_command(content["action"], content["args"])
        if content["action"] in {"install_extension", "uninstall_extension"}:
            snapshot = extension_inventory.get(COMMAND_DB, device_id)
            if snapshot["updated"] is None or time.time() - snapshot["updated"] > 24 * 3600:
                raise ValueError("Extension inventory missing or stale. Open ChatGPT cloud requests on your phone to refresh.")
            package = content["args"]["package_name"]
            matches = [e for e in snapshot["extensions"] if e["package_name"] == package]
            required_state = content["action"] == "uninstall_extension"
            if len(matches) != 1 or matches[0]["installed"] != required_state:
                raise ValueError("Extension is not in the required available/installed state on your phone")
        command_queue.initialize(COMMAND_DB)
        result = command_queue.create(COMMAND_DB, device_id, content["action"], content["args"])
    except (ValueError, TypeError, KeyError) as exc:
        raise HTTPException(400, str(exc)) from exc
    response.headers["Cache-Control"] = "no-store"
    return result


@app.get("/api/assistant/commands/{command_id}")
def assistant_command_status(command_id: str, response: Response):
    response.headers["Cache-Control"] = "no-store"
    result = command_queue.lookup(COMMAND_DB, command_id)
    if result is None:
        raise HTTPException(404, "Command not found")
    return result


@app.get("/api/assistant/extensions")
def assistant_extensions(device_id: str, response: Response):
    response.headers["Cache-Control"] = "no-store"
    if device_id not in [d["id"] for d in _device_keys() if d["expires"] > time.time()]:
        raise HTTPException(404, "Paired device not found")
    return extension_inventory.get(COMMAND_DB, device_id)


@app.post("/api/device/extensions")
async def device_extensions(request: Request, response: Response):
    device_id = _device_identity(request.headers.get("X-API-Token", ""))
    if device_id is None:
        raise HTTPException(401, "Unauthorized")
    raw = await request.body()
    if len(raw) > 80000:
        raise HTTPException(413, "Extension snapshot too large")
    try:
        snapshot = json.loads(raw)
        count = extension_inventory.store(COMMAND_DB, device_id, snapshot)
    except (ValueError, TypeError) as exc:
        raise HTTPException(400, str(exc)) from exc
    response.headers["Cache-Control"] = "no-store"
    return {"count": count}


@app.get("/api/device/commands")
def device_commands(request: Request, response: Response):
    device_id = _device_identity(request.headers.get("X-API-Token", ""))
    if device_id is None:
        raise HTTPException(401, "Unauthorized")
    response.headers["Cache-Control"] = "no-store"
    commands = command_queue.pending(COMMAND_DB, device_id)
    try:
        categories.snapshot(COMMAND_DB, device_id, True)
    except (ValueError, PermissionError):
        commands = [c for c in commands if c['action'] != 'category_changes']
    return commands


@app.post("/api/device/commands/{command_id}/result")
async def device_command_result(command_id: str, request: Request, response: Response):
    device_id = _device_identity(request.headers.get("X-API-Token", ""))
    if device_id is None:
        raise HTTPException(401, "Unauthorized")
    try:
        payload = await request.json()
        if not isinstance(payload, dict) or set(payload) != {"state"}:
            raise ValueError("Invalid result")
        finished = command_queue.finish(COMMAND_DB, device_id, command_id, payload["state"])
    except (ValueError, KeyError, TypeError) as exc:
        raise HTTPException(400, str(exc)) from exc
    if not finished:
        raise HTTPException(409, "Command is expired, missing, or already resolved")
    response.headers["Cache-Control"] = "no-store"
    return {"command_id": command_id, "state": payload["state"]}
# KMK <--


@app.get("/api/sync/content")
def get_sync_content(if_none_match: str | None = Header(None)):
    if not BACKUP_PATH.is_file():
        raise HTTPException(404, "No synced backup yet")
    raw = BACKUP_PATH.read_bytes()
    etag = '"' + hashlib.sha256(raw).hexdigest() + '"'
    if if_none_match == etag:
        return Response(status_code=304, headers={"ETag": etag})
    return Response(raw, media_type="application/octet-stream", headers={"ETag": etag, "Cache-Control": "no-store"})


@app.put("/api/sync/content")
async def put_sync_content(request: Request, if_match: str | None = Header(None)):
    declared_length = request.headers.get("content-length")
    if declared_length and int(declared_length) > MAX_BACKUP_BYTES:
        raise HTTPException(413, "Backup too large")
    raw = await request.body()
    if not raw or len(raw) > MAX_BACKUP_BYTES:
        raise HTTPException(413, "Invalid backup size")
    try:
        list(_fields(raw))
    except ValueError:
        raise HTTPException(400, "Invalid protobuf envelope")
    if BACKUP_PATH.exists() and if_match:
        existing_etag = '"' + hashlib.sha256(BACKUP_PATH.read_bytes()).hexdigest() + '"'
        if if_match != existing_etag:
            raise HTTPException(412, "Backup changed on server")
    previous_backup = BACKUP_PATH.read_bytes() if BACKUP_PATH.exists() else b""
    DATA_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=DATA_DIR, prefix=".sync-")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(raw)
            f.flush()
            os.fsync(f.fileno())
        os.chmod(tmp, 0o600)
        os.replace(tmp, BACKUP_PATH)
        try:
            save_observed_events(READ_ACTIVITY_PATH, previous_backup,raw,
                                 _fields,_utf8,BLOCKED_GENRES)
        except (ValueError,OSError):
            # Never make existing SyncYomi backup depend on activity capture.
            pass
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)
    return Response(status_code=200, headers={"ETag": '"' + hashlib.sha256(raw).hexdigest() + '"'})


@app.post("/api/sync/event")
async def sync_event(request: Request):
    # Compatibility endpoint: do not retain device models or message bodies.
    return Response(status_code=204)



# KMK -->
def category_device(device_id):
    if device_id not in [d["id"] for d in _device_keys() if d["expires"] > time.time()]:
        raise HTTPException(404, "Paired device not found")
    categories.initialize(COMMAND_DB)
    command_queue.initialize(COMMAND_DB)


@app.post("/api/device/category-snapshot")
async def device_category_snapshot(request: Request):
    device_id = _device_identity(request.headers.get("X-API-Token", ""))
    if device_id is None:
        raise HTTPException(401, "Unauthorized")
    raw = await request.body()
    if len(raw) > 8 * 1024 * 1024:
        raise HTTPException(413, "Metadata snapshot too large")
    try:
        return categories.store(COMMAND_DB, device_id, json.loads(raw))
    except (ValueError, TypeError, KeyError) as exc:
        raise HTTPException(400, str(exc)) from exc


@app.get("/api/assistant/categories")
def assistant_categories(device_id: str, category_id: str | None = None):
    category_device(device_id)
    try:
        result = categories.snapshot(COMMAND_DB, device_id)
        if category_id is not None:
            matches = [c for c in result['categories'] if c['id'] == category_id]
            if not matches:
                raise HTTPException(404, "Category not found")
            result['categories'] = matches
        return result
    except PermissionError as exc:
        raise HTTPException(403, str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(409, str(exc)) from exc


@app.get("/api/assistant/category-library")
def assistant_category_library(device_id: str, offset: int = 0, limit: int = 100,
                               category_id: str | None = None, manga_id: str | None = None, query: str = ""):
    category_device(device_id)
    try:
        return categories.page(COMMAND_DB, device_id, offset, limit, category_id, manga_id, query)
    except PermissionError as exc:
        raise HTTPException(403, str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(409, str(exc)) from exc


@app.post("/api/assistant/category-preview")
async def assistant_category_preview(request: Request):
    raw = await request.body()
    if len(raw) > 150000:
        raise HTTPException(413, "Preview too large")
    try:
        body = json.loads(raw)
        if not isinstance(body, dict) or not set(body) <= {'device_id','expected_revision','changes','category_order'}:
            raise ValueError("Invalid preview fields")
        category_device(body['device_id'])
        return categories.preview(COMMAND_DB, body['device_id'], body['expected_revision'], body.get('changes', []), body.get('category_order'))
    except PermissionError as exc:
        raise HTTPException(403, str(exc)) from exc
    except (ValueError, TypeError, KeyError) as exc:
        raise HTTPException(409, str(exc)) from exc


@app.post("/api/assistant/category-apply", status_code=202)
async def assistant_category_apply(request: Request):
    try:
        raw = await request.body()
        if len(raw) > 1000:
            raise ValueError('Apply request too large')
        body = json.loads(raw)
        if not isinstance(body,dict) or set(body) != {'device_id','preview_id','confirmed'}:
            raise ValueError('Invalid apply fields')
        category_device(body['device_id'])
        return categories.apply(COMMAND_DB, body['device_id'], body['preview_id'], body['confirmed'], command_queue)
    except PermissionError as exc:
        raise HTTPException(403, str(exc)) from exc
    except (ValueError, TypeError, KeyError) as exc:
        raise HTTPException(409, str(exc)) from exc
@app.get("/api/assistant/category-history")
def assistant_category_history(device_id: str, offset: int = 0, limit: int = 50):
    import sqlite3
    category_device(device_id)
    try:
        categories.snapshot(COMMAND_DB,device_id)
        if not 0 <= offset <= 1000000 or not 1 <= limit <= 100:
            raise ValueError('Invalid history pagination')
        with sqlite3.connect(COMMAND_DB) as db:
            rows=db.execute('SELECT id,created,command_id FROM category_previews WHERE device=? ORDER BY created DESC,id LIMIT ? OFFSET ?',(device_id,limit,offset)).fetchall()
        return {'items':[{'preview_id':i,'created':created,'command':command_queue.lookup(COMMAND_DB,cid) if cid else None} for i,created,cid in rows], 'offset':offset,'limit':limit}
    except PermissionError as exc:
        raise HTTPException(403,str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(409,str(exc)) from exc


@app.post("/api/assistant/category-undo-preview")
async def assistant_category_undo(request: Request):
    import sqlite3
    try:
        body=await request.json()
        if not isinstance(body,dict) or set(body)!={'device_id','preview_id','expected_revision'}:
            raise ValueError('Invalid undo preview')
        device=body['device_id'];category_device(device)
        snap=categories.snapshot(COMMAND_DB,device,True)
        with sqlite3.connect(COMMAND_DB) as db:
            row=db.execute('SELECT data,command_id FROM category_previews WHERE id=? AND device=?',(body['preview_id'],device)).fetchone()
        if not row or not row[1] or command_queue.lookup(COMMAND_DB,row[1])['state']!='completed':
            raise ValueError('Only a phone-confirmed completed change can be undone')
        proposal=json.loads(row[0])
        if proposal['category_order'] is not None:
            raise ValueError('Category reorder undo is not yet supported; preview an explicit reorder instead')
        changes=[]
        for c in proposal['changes']:
            current=categories.page(COMMAND_DB,device,manga_id=c['manga_id'])['items']
            if len(current)!=1 or categories.category_ids(current[0]['category_ids'])!=categories.category_ids(c['after']):
                raise ValueError('A later phone edit conflicts with undo')
            changes.append({'manga_id':c['manga_id'],'category_ids':c['before'],'mode':'replace'})
        return categories.preview(COMMAND_DB,device,body['expected_revision'],changes)
    except PermissionError as exc:
        raise HTTPException(403,str(exc)) from exc
    except (ValueError,TypeError,KeyError) as exc:
        raise HTTPException(409,str(exc)) from exc


# KMK <--

# Mount the SDK's standards-based Streamable HTTP endpoint at /mcp.
app.mount("/", mcp.streamable_http_app())
