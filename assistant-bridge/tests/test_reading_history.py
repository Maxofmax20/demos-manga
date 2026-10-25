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

    def test_actual_date_supersedes_sync_detection_in_counts(self):
        import json
        timestamp=1723000000000
        raw=backup(True,timestamp)
        actual=dated_history(raw,_fields,_utf8,BLOCKED_GENRES,
                             now_ms=timestamp+10000)
        self.assertEqual(len(actual),1)
        observed={**actual[0], "timeKind":"observed_sync",
                  "timestamp":timestamp+999}
        with tempfile.TemporaryDirectory() as directory:
            file=Path(directory)/"observed.json"
            file.write_text(json.dumps([observed,observed]))
            page=read_activity(raw,file,_fields,_utf8,BLOCKED_GENRES,
                               limit=40,offset=0)
            self.assertEqual(page["total"],1)
            self.assertEqual(page["datedCount"],1)
            self.assertEqual(page["observedCount"],0)
            self.assertEqual(len(page["items"]),1)
            self.assertEqual(page["items"][0]["timeKind"],"actual")

    def test_only_unique_sync_observations_count(self):
        import json
        raw=backup(True)
        rows=new_read_flags(backup(False),raw,_fields,_utf8,BLOCKED_GENRES,
                            observed_ms=1723000000000)
        self.assertEqual(len(rows),1)
        later={**rows[0],"timestamp":1723000005000}
        with tempfile.TemporaryDirectory() as directory:
            file=Path(directory)/"observed.json"
            file.write_text(json.dumps(rows+[later]))
            page=read_activity(raw,file,_fields,_utf8,BLOCKED_GENRES,
                               limit=40,offset=0)
            self.assertEqual(page["total"],1)
            self.assertEqual(page["datedCount"],0)
            self.assertEqual(page["observedCount"],1)
            self.assertEqual(page["items"][0]["timestamp"],
                             later["timestamp"])

    def test_future_date_rejected(self):
        self.assertEqual(dated_history(backup(True,9999999999999999),_fields,
                         _utf8,BLOCKED_GENRES),[])

    # KMK -->
    def test_duplicate_actual_records_keep_latest_date_before_pagination(self):
        timestamp=1723000000000
        # Duplicate backup records can contain different last-read values.
        raw=backup(True,timestamp)+backup(True,timestamp+1000)
        actual=dated_history(raw,_fields,_utf8,BLOCKED_GENRES,
                             now_ms=timestamp+5000,max_items=1)
        self.assertEqual(len(actual),1)
        self.assertEqual(actual[0]["timestamp"],timestamp+1000)
        with tempfile.TemporaryDirectory() as directory:
            page=read_activity(raw,Path(directory)/"events.json",_fields,_utf8,
                               BLOCKED_GENRES,limit=1)
            self.assertEqual(page["total"],1)
            self.assertEqual(page["datedCount"],1)
            self.assertFalse(page["hasMore"])
    # KMK <--

# KMK -->
class RecentTitleHistoryTests(unittest.TestCase):
    def test_one_latest_actual_event_per_title_before_pagination(self):
        import json
        raw=b""
        for i in range(3):
            book=n(1,423)+m(2,f"/s{i}".encode())+m(3,f"Title {i}".encode())
            for ch in range(5):
                url=f"/c{ch}".encode()
                book+=m(16,m(1,url)+m(2,b"Chapter")+n(4,1))
                book+=m(104,m(1,url)+n(2,1723000000000+i*100+ch))
            raw+=m(1,book)
        with tempfile.TemporaryDirectory() as t:
            file=Path(t)/"activity.json"
            file.write_text(json.dumps([dict(mangaId="f"*24,chapterId="e"*24,title="Observed",chapter="Chapter",timestamp=1790000000000,timeKind="observed_sync")]))
            page=read_activity(raw,file,_fields,_utf8,BLOCKED_GENRES,2,0,True)
            self.assertEqual(page["total"],3)
            self.assertEqual([r["title"] for r in page["items"]],["Title 2","Title 1"])
            self.assertEqual(page["items"][0]["timestamp"],1723000000204)
            self.assertTrue(page["hasMore"])
# KMK <--
