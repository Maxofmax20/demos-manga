"""Owner-private reader activity from dated backups or verified sync deltas.

Never infer a read date from a read=True chapter flag. Actual timestamps
come only from BackupHistory field 104. New chapter transitions are labeled
observed_sync, not misrepresented as actual read times.
"""
import hashlib
import json
import os
import tempfile
import time
from pathlib import Path

EARLIEST=946684800000
MAX_ITEMS=300
MAX_HISTORY_EVENTS=20000

def _books(raw,fields,decode,blocked):
    for n,w,payload in fields(raw):
        if n!=1 or w!=2:continue
        source=None;url=None;title="";genres=[];chapters=[];history=[]
        for number,typ,value in fields(payload):
            if number==1 and typ==0:source=value
            elif number==2 and typ==2:url=decode(value)
            elif number==3 and typ==2:title=decode(value)[:180]
            elif number==7 and typ==2:genres.append(decode(value).casefold())
            elif number==16 and typ==2:chapters.append(value)
            elif number==104 and typ==2:history.append(value)
        if source is None or not url or not title or any(g in blocked for g in genres):
            continue
        signed=source-(1<<64) if source>=1<<63 else source
        mid=hashlib.sha256(f"{signed}|{url}".encode()).hexdigest()[:24]
        yield mid,title,chapters,history

def _chapter(blob,fields,decode):
    url=None;name="";read=False
    for n,t,value in fields(blob):
        if n==1 and t==2:url=decode(value)
        elif n==2 and t==2:name=decode(value)[:160]
        elif n==4 and t==0:read=bool(value)
    return url,name,read

def _id(mid,url):
    return hashlib.sha256(f"{mid}|{url}".encode()).hexdigest()[:24]

def dated_history(raw,fields,decode,blocked,now_ms=None,max_items=MAX_ITEMS):
    if now_ms is None:now_ms=int(time.time()*1000)
    result=[]
    for mid,title,chapters,histories in _books(raw,fields,decode,blocked):
        names={}
        for blob in chapters:
            url,name,_=_chapter(blob,fields,decode)
            if url:names[url]=name
        for blob in histories:
            url=None;last=0
            for n,t,value in fields(blob):
                if n==1 and t==2:url=decode(value)
                elif n==2 and t==0:last=value
            if not url or url not in names or not(EARLIEST<=last<=now_ms+86400000):
                continue
            result.append(dict(mangaId=mid,chapterId=_id(mid,url),title=title,
                               chapter=names[url],timestamp=last,timeKind="actual"))
    return sorted(result,key=lambda x:x["timestamp"],reverse=True)[:max_items]

def _flags(raw,fields,decode,blocked):
    values={}
    for mid,title,chapters,_ in _books(raw,fields,decode,blocked):
        for c in chapters:
            url,name,read=_chapter(c,fields,decode)
            if url:values[(mid,url)]=(title,name,read)
    return values

def new_read_flags(previous,current,fields,decode,blocked,observed_ms=None):
    if observed_ms is None:observed_ms=int(time.time()*1000)
    before=_flags(previous,fields,decode,blocked)
    after=_flags(current,fields,decode,blocked)
    events=[]
    for (mid,url),(title,name,read) in after.items():
        old=before.get((mid,url))
        if not read or old is None or old[2]:continue
        events.append(dict(mangaId=mid,chapterId=_id(mid,url),title=title,
              chapter=name,timestamp=observed_ms,timeKind="observed_sync"))
    return events[:MAX_ITEMS]

def save_observed_events(file:Path,previous:bytes,current:bytes,fields,decode,blocked):
    if not previous or previous==current:return 0
    delta=new_read_flags(previous,current,fields,decode,blocked)
    if not delta:return 0
    try:
        previous_rows=json.loads(file.read_text()) if file.is_file() else []
        if not isinstance(previous_rows,list):previous_rows=[]
    except(OSError,ValueError):previous_rows=[]
    merged={}
    for row in previous_rows+delta:
        if isinstance(row,dict) and row.get("timeKind")=="observed_sync":
            mid=row.get("mangaId","")
            cid=row.get("chapterId","")
            if isinstance(mid,str) and isinstance(cid,str) and len(mid)==24 and len(cid)==24:
                merged[(mid,cid)]=row
    ordered=sorted(merged.values(),key=lambda x:x["timestamp"],reverse=True)[:MAX_ITEMS]
    fd,tmp=tempfile.mkstemp(dir=file.parent,prefix=".reading-history-")
    try:
        with os.fdopen(fd,"w") as out:
            json.dump(ordered,out,ensure_ascii=False)
            out.flush();os.fsync(out.fileno())
        os.chmod(tmp,0o600);os.replace(tmp,file)
    finally:
        if os.path.exists(tmp):os.unlink(tmp)
    return len(delta)

def read_activity(raw,file:Path,fields,decode,blocked,limit=60,offset=0):
    assert 1<=limit<=100 and 0<=offset<=MAX_HISTORY_EVENTS
    actual=dated_history(raw,fields,decode,blocked,
                         max_items=MAX_HISTORY_EVENTS)
    try:observed=json.loads(file.read_text()) if file.exists() else []
    except(OSError,ValueError):observed=[]
    if not isinstance(observed,list):observed=[]
    verified=[r for r in observed if isinstance(r,dict) and
         r.get("timeKind")=="observed_sync" and isinstance(r.get("timestamp"),int)
         and isinstance(r.get("mangaId"),str) and isinstance(r.get("chapterId"),str)]
    ids={(x["mangaId"],x["chapterId"]) for x in actual}
    rows=actual+[x for x in verified if (x["mangaId"],x["chapterId"]) not in ids]
    rows.sort(key=lambda x:x["timestamp"],reverse=True)
    total=len(rows)
    next_offset=offset+limit if offset+limit<total else None
    return dict(items=rows[offset:offset+limit],
       datedCount=len(actual),observedCount=len(verified),
       total=total,offset=offset,hasMore=next_offset is not None,
       nextOffset=next_offset,
       hasHistoricalDates=bool(actual),source="Demos Manga private reader sync")
