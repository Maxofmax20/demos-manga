import unittest,tempfile,hashlib,json
from pathlib import Path
from unittest.mock import patch
from fastapi.testclient import TestClient
import server,categories
class CategoryApiTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);root=Path(self.tmp.name)
  for field,value in [('DATA_DIR',root),('DEVICE_KEYS_PATH',root/'keys.json'),('COMMAND_DB',root/'commands.db'),('MCP_TOKEN','m'*40),('SYNC_TOKEN','s'*40)]:
   p=patch.object(server,field,value);p.start();self.addCleanup(p.stop)
  self.c=TestClient(server.app);self.owner={'Authorization':'Bearer '+'m'*40}
  d=self.c.post('/api/assistant/pair',headers=self.owner).json();self.device=d['device_id'];self.phone={'X-API-Token':d['token']}
  self.s={'revision':'a'*64,'scopes':{s:s in ['read_library','organize_library'] for s in categories.SCOPES},'categories':[{'id':'0','name':'','order':-1,'hidden':False,'flags':'0'},{'id':'8','name':'مكتملة','order':0,'hidden':False,'flags':'0'}],'manga':[{'id':'b'*24,'title':'عنوان','category_ids':[]}]}
 def test_missing_and_unauthorized_are_honest(self):
  self.assertEqual(self.c.get('/api/assistant/categories',params={'device_id':self.device}).status_code,401)
  self.assertEqual(self.c.get('/api/assistant/categories',params={'device_id':self.device},headers=self.owner).status_code,409)
  self.assertEqual(self.c.post('/api/device/category-snapshot',json=self.s,headers=self.owner).status_code,401)
 def test_complete_preview_queue_device_report(self):
  self.assertEqual(self.c.post('/api/device/category-snapshot',json=self.s,headers=self.phone).status_code,200)
  body={'device_id':self.device,'expected_revision':'a'*64,'changes':[{'manga_id':'b'*24,'category_ids':['8'],'mode':'add'}]}
  preview=self.c.post('/api/assistant/category-preview',headers=self.owner,json=body).json()
  self.assertEqual(preview['changes'][0]['before'],[]);self.assertEqual(preview['changes'][0]['after'],['8'])
  apply={'device_id':self.device,'preview_id':preview['preview_id'],'confirmed':True}
  queued=self.c.post('/api/assistant/category-apply',headers=self.owner,json=apply);self.assertEqual(queued.status_code,202);command=queued.json()['command_id']
  inbox=self.c.get('/api/device/commands',headers=self.phone).json();self.assertEqual(len(inbox),1)
  self.assertEqual(inbox[0]['action'],'category_changes')
  # Queue alone must never rewrite the snapshot.
  self.assertEqual(categories.page(server.COMMAND_DB,self.device)['items'][0]['category_ids'],[])
  self.assertEqual(self.c.post(f'/api/device/commands/{command}/result',headers=self.phone,json={'state':'rejected'}).status_code,200)
  self.assertEqual(self.c.post(f'/api/device/commands/{command}/result',headers=self.phone,json={'state':'completed'}).status_code,409)
 def test_revoked_scope_filters_pending_and_denies_reads(self):
  self.c.post('/api/device/category-snapshot',json=self.s,headers=self.phone)
  self.s['scopes']['read_library']=False;self.s['categories']=[];self.s['manga']=[]
  self.assertEqual(self.c.post('/api/device/category-snapshot',json=self.s,headers=self.phone).status_code,200)
  self.assertEqual(self.c.get('/api/assistant/categories',params={'device_id':self.device},headers=self.owner).status_code,403)
