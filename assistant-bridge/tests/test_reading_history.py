import pathlib
import sys
import tempfile
import unittest
sys.path.insert(0,str(pathlib.Path(__file__).parents[1]))
from reading_history import dated_history,new_read_flags,save_observed_events,read_activity

def varint(v):
 b=bytearray()
 while True:
  c=v&127;v>>=7
  if v:b.append(c|128)
  else:b.append(c);return bytes(b)
def wire(n,v):return varint((n<<3)|2)+varint(len(v))+v
def num(n,v):return varint(n<<3)+varint(v)
def fields(raw):
 i=0
 while i<len(raw):
  tag,i=read_var(raw,i);n=tag>>3;w=tag&7
  if w==2:
   count,i=read_var(raw,i);value=raw[i:i+count];i+=count
  else:value,i=read_var(raw,i)
  yield n,w,value
def read_var(raw,i):
 value=0;shift=0
 while True:
  c=raw[i];i+=1;value|=(c&127)<<shift
  if c<128:return value,i
  shift+=7
def book(read=False,dated=False,genres=None):
 chapter=wire(1,b"/ch1")+wire(2,b"Chapter 1")+num(4,int(read))
 b=num(1,101)+wire(2,b"/book")+wire(3,b"Safe Title")+wire(16,chapter)
 for genre in (genres or []):b+=wire(7,genre.encode())
 if dated:b+=wire(104,wire(1,b"/ch1")+num(2,1790000000000))
 return wire(1,b)
class ReadingHistoryTests(unittest.TestCase):
 def test_actual_dates_are_real(self):
  self.assertEqual(dated_history(book(read=True),fields,bytes.decode,set()),[])
  rows=dated_history(book(read=True,dated=True),fields,bytes.decode,set(),1790001000000)
  self.assertEqual(len(rows),1)
  self.assertEqual(rows[0]["timeKind"],"actual")
 def test_new_read_is_observed_not_backdated(self):
  rows=new_read_flags(book(),book(read=True),fields,bytes.decode,set(),1790000000000)
  self.assertEqual(len(rows),1)
  self.assertEqual(rows[0]["timeKind"],"observed_sync")
  self.assertEqual(new_read_flags(book(read=True),book(read=True),fields,bytes.decode,set()),[])
 def test_private_genres_do_not_leak(self):
  self.assertEqual(new_read_flags(book(genres=["mature"]),
   book(read=True,genres=["mature"]),fields,bytes.decode,{"mature"}),[])
 def test_events_deduplicated(self):
  with tempfile.TemporaryDirectory() as folder:
   target=pathlib.Path(folder)/"history.json"
   self.assertEqual(save_observed_events(target,book(),book(read=True),
       fields,bytes.decode,set()),1)
   self.assertEqual(save_observed_events(target,book(),book(read=True),
       fields,bytes.decode,set()),1)
   rows=read_activity(book(read=True),target,fields,bytes.decode,set())
   self.assertEqual(len(rows["items"]),1)
 def test_actual_date_takes_priority(self):
  with tempfile.TemporaryDirectory() as folder:
   target=pathlib.Path(folder)/"history.json"
   save_observed_events(target,book(),book(read=True),fields,bytes.decode,set())
   rows=read_activity(book(read=True,dated=True),target,fields,bytes.decode,set())
   self.assertEqual(len(rows["items"]),1)
   self.assertEqual(rows["items"][0]["timeKind"],"actual")
if __name__=="__main__":unittest.main()
