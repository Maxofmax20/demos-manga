"""Device-authoritative, opt-in category metadata and immutable change previews.
No Android database edits happen here. The phone executes a revision-bound proposal
inside its own transaction and must explicitly approve it.
"""
import json,re,sqlite3,time,uuid
ID=re.compile(r"^[0-9a-f]{24}$")
REV=re.compile(r"^[0-9a-f]{64}$")
SCOPES=("read_library","organize_library","chapter_progress","downloads_updates","sources_extensions","tracking","app_preferences","backups_restores","diagnostics")
def initialize(path):
 with sqlite3.connect(path) as db:
  db.executescript("""CREATE TABLE IF NOT EXISTS category_snapshots(device TEXT PRIMARY KEY,revision TEXT NOT NULL,updated INTEGER NOT NULL,scopes TEXT NOT NULL,data TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS category_previews(id TEXT PRIMARY KEY,device TEXT NOT NULL,revision TEXT NOT NULL,created INTEGER NOT NULL,data TEXT NOT NULL,command_id TEXT UNIQUE);
CREATE TABLE IF NOT EXISTS category_titles(device TEXT NOT NULL,id TEXT NOT NULL,title TEXT NOT NULL,data TEXT NOT NULL,PRIMARY KEY(device,id));""")
def category_ids(value):
 if not isinstance(value,list) or len(value)>500 or any(not isinstance(x,str) or not re.fullmatch(r"[1-9][0-9]{0,17}",x) for x in value) or len(set(value))!=len(value):raise ValueError("Category IDs must be unique positive decimal strings; Default uses an empty list")
 return sorted(value,key=int)
def validate_snapshot(s):
 if not isinstance(s,dict) or set(s)!={"revision","scopes","categories","manga"} or not REV.fullmatch(str(s.get("revision",""))):raise ValueError("Invalid category snapshot")
 scopes=s['scopes']
 if not isinstance(scopes,dict) or set(scopes)!=set(SCOPES) or any(type(v) is not bool for v in scopes.values()):raise ValueError("Invalid permission scopes")
 if not scopes['read_library']:
  if s['categories'] or s['manga']:raise ValueError("Read scope is disabled")
  return s
 cats=s['categories'];books=s['manga']
 if not isinstance(cats,list) or len(cats)>500 or not isinstance(books,list) or len(books)>20000:raise ValueError("Snapshot too large")
 seen=set()
 for c in cats:
  if not isinstance(c,dict) or set(c)!={"id","name","order","hidden","flags"} or not re.fullmatch(r"0|[1-9][0-9]{0,17}",str(c['id'])) or c['id'] in seen:raise ValueError("Invalid or duplicate category")
  if not isinstance(c['name'],str) or len(c['name'])>1000 or type(c['order']) is not int or type(c['hidden']) is not bool or not isinstance(c['flags'],str):raise ValueError("Invalid category fields")
  seen.add(c['id'])
 if '0' not in seen:raise ValueError("Missing Android Default category")
 mids=set()
 for m in books:
  if not isinstance(m,dict) or set(m)!={"id","title","category_ids"} or not ID.fullmatch(str(m['id'])) or m['id'] in mids or not isinstance(m['title'],str) or len(m['title'])>1000:raise ValueError("Invalid or duplicate manga")
  if not set(category_ids(m['category_ids']))<=seen:raise ValueError("Unknown category membership")
  mids.add(m['id'])
 return s
def store(path,device,s):
 validate_snapshot(s);initialize(path)
 with sqlite3.connect(path) as db:
  db.execute('INSERT OR REPLACE INTO category_snapshots VALUES(?,?,?,?,?)',(device,s['revision'],int(time.time()),json.dumps(s['scopes']),json.dumps({'categories':s['categories']},ensure_ascii=False)))
  db.execute('DELETE FROM category_titles WHERE device=?',(device,))
  db.executemany('INSERT INTO category_titles VALUES(?,?,?,?)',[(device,m['id'],m['title'],json.dumps(m,ensure_ascii=False)) for m in s['manga']])
 return {'revision':s['revision'],'titles':len(s['manga'])}
def snapshot(path,device,write=False):
 initialize(path)
 with sqlite3.connect(path) as db:row=db.execute('SELECT revision,updated,scopes,data FROM category_snapshots WHERE device=?',(device,)).fetchone()
 if not row:raise ValueError("Category data unavailable: install the category-capable app, enable Read library scope, and open ChatGPT cloud requests")
 revision,updated,raw,data=row;scopes=json.loads(raw)
 if not scopes['read_library'] or (write and not scopes['organize_library']):raise PermissionError("Required phone scope is disabled or revoked")
 if write and time.time()-updated>86400:raise ValueError("Category snapshot is stale; refresh from the phone")
 return {'device_id':device,'revision':revision,'updated':updated,'scopes':scopes,**json.loads(data)}
def page(path,device,offset=0,limit=100,category_id=None,manga_id=None,query=''):
 s=snapshot(path,device)
 if type(offset) is not int or offset<0 or offset>1000000 or type(limit) is not int or not 1<=limit<=100 or len(query)>200:raise ValueError('Invalid pagination')
 if category_id is not None and category_id not in [c['id'] for c in s['categories']]:raise ValueError('Unknown category ID')
 # Indexed IDs and SQL pagination avoid reconstructing all library records per page.
 where='device=?';args=[device]
 if manga_id is not None:where+=' AND id=?';args.append(manga_id)
 if query:where+=' AND instr(lower(title),lower(?))>0';args.append(query)
 if category_id is not None:
  if category_id=='0':where+=" AND json_array_length(json_extract(data,'$.category_ids'))=0"
  else:where+=" AND EXISTS(SELECT 1 FROM json_each(json_extract(category_titles.data,'$.category_ids')) WHERE value=?)";args.append(category_id)
 with sqlite3.connect(path) as db:
  total=db.execute('SELECT count(*) FROM category_titles WHERE '+where,args).fetchone()[0]
  items=[json.loads(r[0]) for r in db.execute('SELECT data FROM category_titles WHERE '+where+' ORDER BY title COLLATE NOCASE,id LIMIT ? OFFSET ?',args+[limit,offset])]
 return {'device_id':device,'revision':s['revision'],'updated':s['updated'],'items':items,'total':total,'offset':offset,'limit':limit,'next_offset':offset+len(items) if offset+len(items)<total else None}
def preview(path,device,revision,changes,order=None):
 s=snapshot(path,device,True)
 if revision!=s['revision']:raise ValueError('Revision conflict: refresh and preview again')
 if not isinstance(changes,list) or len(changes)>500:raise ValueError('Use batches of at most 500 manga')
 cats={c['id'] for c in s['categories'] if c['id']!='0'};seen=set();result=[]
 with sqlite3.connect(path) as db:
  for change in changes:
   if not isinstance(change,dict) or set(change)!={'manga_id','category_ids','mode'}:raise ValueError('Invalid category change')
   mid=change['manga_id'];ids=category_ids(change['category_ids']);mode=change['mode']
   if not ID.fullmatch(str(mid)) or mid in seen or mode not in ['add','remove','replace'] or not set(ids)<=cats:raise ValueError('Invalid, duplicate or unknown change identity')
   seen.add(mid);row=db.execute('SELECT data FROM category_titles WHERE device=? AND id=?',(device,mid)).fetchone()
   if not row:raise ValueError('Manga is missing or restricted')
   m=json.loads(row[0]);before=category_ids(m['category_ids']);after=sorted((set(before)|set(ids)) if mode=='add' else (set(before)-set(ids)) if mode=='remove' else set(ids),key=int)
   result.append({'manga_id':mid,'title':m['title'],'before':before,'after':after})
 if order is not None and (not isinstance(order,list) or len(order)!=len(set(order)) or set(order)!=cats):raise ValueError('Reorder must contain every non-system category exactly once')
 if not changes and order is None:raise ValueError('Empty preview')
 payload={'expected_revision':revision,'changes':result,'category_order':order}
 ident=uuid.uuid4().hex
 with sqlite3.connect(path) as db:
  count=db.execute('SELECT count(*) FROM category_previews WHERE device=? AND created>?',(device,int(time.time())-60)).fetchone()[0]
  if count>=30:raise ValueError('Too many category previews; retry later')
  db.execute('INSERT INTO category_previews VALUES(?,?,?,?,?,NULL)',(ident,device,revision,int(time.time()),json.dumps(payload,ensure_ascii=False)))
 return {'preview_id':ident,'device_id':device,**payload,'state':'preview_only','expires':int(time.time())+1800}
def apply(path,device,preview_id,confirmed,queue):
 if confirmed is not True:raise ValueError('Explicit approval of this exact preview is required')
 s=snapshot(path,device,True)
 with sqlite3.connect(path) as db:row=db.execute('SELECT device,revision,created,data,command_id FROM category_previews WHERE id=?',(preview_id,)).fetchone()
 if not row or row[0]!=device:raise ValueError('Preview not found for this device')
 if row[4]:return queue.lookup(path,row[4])
 if row[1]!=s['revision'] or time.time()-row[2]>1800:raise ValueError('Preview expired or revision changed')
 # Queue insertion and preview linkage share one SQLite transaction (idempotent).
 with sqlite3.connect(path,timeout=10) as db:
  db.execute('BEGIN IMMEDIATE')
  linked=db.execute('SELECT command_id FROM category_previews WHERE id=?',(preview_id,)).fetchone()[0]
  if linked:return queue.lookup(path,linked)
  now=int(time.time())
  count=db.execute("SELECT count(*) FROM commands WHERE device_id=? AND state='pending_confirmation' AND expires>?",(device,now)).fetchone()[0]
  if count>=20:raise ValueError('Too many pending actions')
  cid=uuid.uuid4().hex;payload=json.loads(row[3]);payload['preview_id']=preview_id
  db.execute('INSERT INTO commands VALUES(?,?,?,?,?,?,?,?)',(cid,device,'category_changes',json.dumps(payload,ensure_ascii=False),'pending_confirmation',now,now+1800,None))
  db.execute('UPDATE category_previews SET command_id=? WHERE id=?',(cid,preview_id))
 return queue.lookup(path,cid)
