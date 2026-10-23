import unittest
from server import _fields, extract_metadata


def vi(n):
    b = []
    while True:
        part = n & 127
        n >>= 7
        b.append(part | (128 if n else 0))
        if not n:
            return bytes(b)


def var(field, value):
    return vi(field << 3) + vi(value)


def obj(field, value):
    return vi((field << 3) | 2) + vi(len(value)) + value


class ParserTests(unittest.TestCase):
    def test_read_and_unread(self):
        chapters = obj(16, var(4, 1)) + obj(16, var(4, 0))
        book = obj(3, b"Example") + chapters
        parsed = extract_metadata(obj(1, book))
        self.assertEqual(parsed, [{"title": "Example", "chapters": 2, "read": 1, "unread": 1}])

    def test_adult_genres_excluded(self):
        book = obj(3, b"Do not index") + obj(7, b"Adult")
        self.assertEqual(extract_metadata(obj(1, book)), [])

    def test_settings_not_used(self):
        self.assertEqual(extract_metadata(obj(104, b"private settings")), [])

    def test_corrupt_protobuf_rejected(self):
        with self.assertRaises(ValueError):
            list(_fields(b"\x0a\x0aabc"))


if __name__ == "__main__":
    unittest.main()
