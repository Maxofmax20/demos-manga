"""Demos Manga: private SyncYomi-compatible backup receiver and metadata-only MCP."""
from __future__ import annotations

import hashlib
import hmac
import os
import tempfile
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException, Request, Response
from fastapi.responses import JSONResponse
from mcp.server.mcpserver import MCPServer

DATA_DIR = Path(os.getenv("MANGA_DATA_DIR", "/var/lib/demos-manga"))
SYNC_TOKEN = os.getenv("MANGA_SYNC_TOKEN", "")
MCP_TOKEN = os.getenv("MANGA_MCP_TOKEN", "")
MAX_BACKUP_BYTES = 16 * 1024 * 1024
BLOCKED_GENRES = frozenset({"adult", "mature", "explicit", "erotica", "smut", "nsfw", "hentai", "ecchi"})
BACKUP_PATH = DATA_DIR / "sync.protobuf"


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
        genres = []
        chapters = 0
        read = 0
        for subfield, subwire, value in _fields(manga):
            if subfield == 3 and subwire == 2:
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
        result.append({"title": title[:180], "chapters": chapters, "read": read, "unread": chapters - read})
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
    async with mcp.session_manager.run():
        yield


app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)


@app.middleware("http")
async def require_auth(request: Request, call_next):
    path = request.url.path
    if path == "/health":
        return await call_next(request)
    if path.startswith("/api/sync/"):
        if not _token_matches(request.headers.get("X-API-Token", ""), SYNC_TOKEN):
            return JSONResponse({"detail": "Unauthorized"}, status_code=401)
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
@app.get("/api/assistant/stats")
def assistant_stats(response: Response):
    response.headers["Cache-Control"] = "no-store"
    return library_stats()


@app.get("/api/assistant/search")
def assistant_search(response: Response, query: str = "", unread_only: bool = False, limit: int = 25):
    response.headers["Cache-Control"] = "no-store"
    return search_library(query=query, unread_only=unread_only, limit=limit)
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
