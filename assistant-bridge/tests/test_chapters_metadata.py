import hashlib
import unittest

from chapters_metadata import chapters_from_backup
from server import _fields, _utf8, extract_metadata, BLOCKED_GENRES


def varint(n):
    n &= (1 << 64) - 1
    output = bytearray()
    while True:
        part = n & 127
        n >>= 7
        output.append(part | (128 if n else 0))
        if n == 0:
            return bytes(output)


def v(field, n):
    return varint(field << 3) + varint(n)


def obj(field, data):
    return varint((field << 3) | 2) + varint(len(data)) + data


class ChapterReferenceTests(unittest.TestCase):
    def test_identifiers_match_android_and_chapters(self):
        source = -123456
        url = "/series/a"
        first = obj(1, b"/ch-1") + obj(2, b"Chapter 1") + v(4, 1)
        second = obj(1, b"/ch-2") + obj(2, b"Chapter 2") + v(4, 0)
        book = v(1, source) + obj(2, url.encode()) + obj(3, b"Example") + obj(16, first) + obj(16, second)
        backup = obj(1, book)
        expected = hashlib.sha256(f"{source}|{url}".encode()).hexdigest()[:24]
        library = extract_metadata(backup)
        self.assertEqual(library[0]["id"], expected)
        chapters = chapters_from_backup(backup, expected, _fields, _utf8, BLOCKED_GENRES)
        self.assertEqual(len(chapters), 2)
        self.assertEqual(chapters[0]["name"], "Chapter 1")
        self.assertTrue(chapters[0]["read"])
        self.assertEqual(chapters[1]["id"], hashlib.sha256(f"{expected}|/ch-2".encode()).hexdigest()[:24])
        self.assertFalse(chapters[1]["read"])

    def test_genre_restrictions_are_applied_to_chapter_list(self):
        manga = v(1, 100) + obj(2, b"/series") + obj(3, b"Example") + obj(7, b"Adult")
        backup = obj(1, manga)
        expected = hashlib.sha256(b"100|/series").hexdigest()[:24]
        self.assertEqual(chapters_from_backup(backup, expected, _fields, _utf8, BLOCKED_GENRES), [])
        self.assertEqual(extract_metadata(backup), [])


if __name__ == "__main__":
    unittest.main()
