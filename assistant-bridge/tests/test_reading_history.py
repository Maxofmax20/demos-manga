import tempfile
import unittest
from pathlib import Path
from reading_history import dated_history, new_read_flags, read_activity
from server import _fields, _utf8, BLOCKED_GENRES

def number(x):
    b=bytearray()
    while True:
        y=x&127
        x>>=7
        b.append(y|(128 if x else 0))
        if not x:return bytes(b)

def n(field,value):
    return number(field<<3)+number(value)

def m(field,value):
    return number(field<<3|2)+number(len(value))+value

def backup(read=False,history=None):
    chapter=m(1,b"/chap-7")+m(2,b"Chapter 7")+n(4,1 if read else 0)
    manga=n(1,423)+m(2,b"/series")+m(3,b"Sample")+m(16,chapter)
    if history is not None:manga+=m(104,m(1,b"/chap-7")+n(2,history))
    return m(1,manga)

class PrivateHistoryTests(unittest.TestCase):
    def test_dated_history_uses_real_chapter_timestamp(self):
        timestamp=1723000000000
        result=dated_history(backup(True,timestamp),_fields,_utf8,BLOCKED_GENRES,
                             now_ms=timestamp+50000)
        self.assertEqual(len(result),1)
        self.assertEqual(result[0]["timeKind"],"actual")
        self.assertEqual(result[0]["timestamp"],timestamp)
        self.assertEqual(len(result[0]["chapterId"]),24)
    def test_read_flags_are_not_real_history(self):
        self.assertEqual(dated_history(backup(True),_fields,_utf8,BLOCKED_GENRES),[])
        actual=new_read_flags(backup(False),backup(True),_fields,_utf8,BLOCKED_GENRES,
                              observed_ms=1723000000000)
        self.assertEqual(len(actual),1)
        self.assertEqual(actual[0]["timeKind"],"observed_sync")
        self.assertEqual(new_read_flags(backup(True),backup(True),_fields,_utf8,
                         BLOCKED_GENRES),[])
    def test_private_history_absent_until_owner_permits_dates(self):
        with tempfile.TemporaryDirectory() as t:
            x=read_activity(backup(True),Path(t)/"activity.json",_fields,_utf8,
                            BLOCKED_GENRES)
            self.assertEqual(x["items"],[])
            self.assertFalse(x["hasHistoricalDates"])
    def test_history_is_paginated_without_losing_real_dates(self):
        from reading_history import read_activity
        with tempfile.TemporaryDirectory() as t:
            raw=m(1,n(1,423)+m(2,b"/series")+m(3,b"Sample")+
                b"".join(m(16,m(1,f"/c{i}".encode())+m(2,f"Ch {i}".encode())+n(4,1))
                  for i in range(7))+
                b"".join(m(104,m(1,f"/c{i}".encode())+n(2,1723000000000+i))
                  for i in range(7)))
            pages=[read_activity(raw,Path(t)/"events.json",_fields,_utf8,
                    BLOCKED_GENRES,limit=3,offset=offset) for offset in (0,3,6)]
            self.assertEqual(pages[0]["total"],7)
            self.assertTrue(pages[0]["hasMore"])
            self.assertEqual(pages[0]["nextOffset"],3)
            self.assertEqual(len(pages[1]["items"]),3)
            self.assertEqual(len(pages[2]["items"]),1)
            self.assertFalse(pages[2]["hasMore"])
            self.assertIsNone(pages[2]["nextOffset"])
            ids=[x["chapterId"] for page in pages for x in page["items"]]
            self.assertEqual(len(set(ids)),7)

    def test_future_date_rejected(self):
        self.assertEqual(dated_history(backup(True,9999999999999999),_fields,
                         _utf8,BLOCKED_GENRES),[])
