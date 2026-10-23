"""Metadata-only chapter list for authenticated library management."""
from __future__ import annotations

import hashlib

from command_queue import HEX_ID


def chapters_from_backup(raw: bytes, manga_id: str, field_parser, read_text, blocked_genres) -> list[dict]:
    if not HEX_ID.fullmatch(manga_id):
        return []
    for field, wire, book in field_parser(raw):
        if field != 1 or wire != 2:
            continue
        source = None
        source_url = None
        genres = []
        chapter_blobs = []
        for number, typ, value in field_parser(book):
            if number == 1 and typ == 0:
                source = value
            elif number == 2 and typ == 2:
                source_url = read_text(value)
            elif number == 7 and typ == 2:
                genres.append(read_text(value).casefold())
            elif number == 16 and typ == 2:
                chapter_blobs.append(value)
        if source is None or not source_url:
            continue
        signed_source = source - (1 << 64) if source >= 1 << 63 else source
        book_key = hashlib.sha256(f"{signed_source}|{source_url}".encode()).hexdigest()[:24]
        if book_key != manga_id or any(genre in blocked_genres for genre in genres):
            continue
        chapters = []
        for raw_chapter in chapter_blobs:
            url = None
            title = ""
            read = False
            for number, typ, value in field_parser(raw_chapter):
                if number == 1 and typ == 2:
                    url = read_text(value)
                elif number == 2 and typ == 2:
                    title = read_text(value)[:160]
                elif number == 4 and typ == 0:
                    read = bool(value)
            if url:
                chapter_id = hashlib.sha256(f"{manga_id}|{url}".encode()).hexdigest()[:24]
                chapters.append({"id": chapter_id, "name": title, "read": read})
        return chapters
    return []
