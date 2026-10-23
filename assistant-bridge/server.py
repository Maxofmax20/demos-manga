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
from chapters_metadata import chapters_from_backup

DATA_DIR = Path(os.getenv("MANGA_DATA_DIR", "/var/lib/demos-manga"))
SYNC_TOKEN = os.getenv("MANGA_SYNC_TOKEN", "")
MCP_TOKEN = os.getenv("MANGA_MCP_TOKEN", "")
MAX_BACKUP_BYTES = 16 * 1024 * 1024
BLOCKED_GENRES = frozenset({"adult", "mature", "explicit", "erotica", "smut", "nsfw", "hentai", "ecchi"})
BACKUP_PATH = DATA_DIR / "sync.protobuf"
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
    response.headers["Cache-Control"] = "no-store"
    return {"revoked": True}


@app.get("/api/assistant/stats")
def assistant_stats(response: Response):
    response.headers["Cache-Control"] = "no-store"
    return library_stats()


@app.get("/api/assistant/search")
def assistant_search(response: Response, query: str = "", unread_only: bool = False, limit: int = 25):
    response.headers["Cache-Control"] = "no-store"
    return search_library(query=query, unread_only=unread_only, limit=limit)
# KMK <--


@app.get("/api/assistant/chapters")
def assistant_chapters(manga_id: str, response: Response, offset: int = 0, limit: int = 100):
    response.headers["Cache-Control"] = "no-store"
    if not BACKUP_PATH.exists():
        return []
    chapters = chapters_from_backup(BACKUP_PATH.read_bytes(), manga_id, _fields, _utf8, BLOCKED_GENRES)
    return chapters[max(0, offset):max(0, offset) + max(1, min(limit, 100))]


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
    return command_queue.pending(COMMAND_DB, device_id)


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
    DATA_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=DATA_DIR, prefix=".sync-")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(raw)
            f.flush()
            os.fsync(f.fileno())
        os.chmod(tmp, 0o600)
        os.replace(tmp, BACKUP_PATH)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)
    return Response(status_code=200, headers={"ETag": '"' + hashlib.sha256(raw).hexdigest() + '"'})


@app.post("/api/sync/event")
async def sync_event(request: Request):
    # Compatibility endpoint: do not retain device models or message bodies.
    return Response(status_code=204)


# Mount the SDK's standards-based Streamable HTTP endpoint at /mcp.
app.mount("/", mcp.streamable_http_app())
