import unittest,tempfile,hashlib,json
from pathlib import Path
from unittest.mock import patch
from fastapi.testclient import TestClient
import server

class DevicePairingTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        for field,value in [('DATA_DIR',Path(self.temp.name)),('DEVICE_KEYS_PATH',Path(self.temp.name)/'keys.json'),('BACKUP_PATH',Path(self.temp.name)/'sync.protobuf'),('MCP_TOKEN','m'*40),('SYNC_TOKEN','s'*40)]:
            p=patch.object(server,field,value);p.start();self.addCleanup(p.stop)
        self.c=TestClient(server.app);self.h={'Authorization':'Bearer '+'m'*40}
    def test_pair_opt_in_hash_scope_revoke(self):
        self.assertEqual(self.c.post('/api/assistant/pair').status_code,401)
        r=self.c.post('/api/assistant/pair',headers=self.h);self.assertEqual(r.status_code,200)
        key=r.json()['token'];stored=server.DEVICE_KEYS_PATH.read_text();self.assertNotIn(key,stored)
        self.assertIn(hashlib.sha256(key.encode()).hexdigest(),stored)
        self.assertEqual(server.DEVICE_KEYS_PATH.stat().st_mode & 0o777,0o600)
        self.assertEqual(self.c.get('/api/assistant/stats',headers={'Authorization':'Bearer '+key}).status_code,401)
        self.assertEqual(self.c.get('/api/sync/content',headers={'X-API-Token':key}).status_code,404)
        self.assertFalse(server.BACKUP_PATH.exists())
        self.assertEqual(self.c.post('/api/assistant/unpair',headers=self.h).status_code,200)
        self.assertEqual(self.c.get('/api/sync/content',headers={'X-API-Token':key}).status_code,401)
    def test_device_limit_and_expiry(self):
        keys=[self.c.post('/api/assistant/pair',headers=self.h).json()['token'] for _ in range(3)]
        self.assertEqual(self.c.post('/api/assistant/pair',headers=self.h).status_code,409)
        stored=json.loads(server.DEVICE_KEYS_PATH.read_text());stored[0]['expires']=1;server._save_device_keys(stored)
        self.assertEqual(self.c.get('/api/sync/content',headers={'X-API-Token':keys[0]}).status_code,401)
        self.assertEqual(self.c.post('/api/assistant/pair',headers=self.h).status_code,200)

    def test_opt_in_upload_then_metadata_and_etag(self):
        from test_metadata import obj,var
        key=self.c.post('/api/assistant/pair',headers=self.h).json()['token']
        sh={'X-API-Token':key}
        raw=obj(1,obj(3,b'Test title')+obj(16,var(4,1))+obj(16,var(4,0)))
        r=self.c.put('/api/sync/content',headers=sh,content=raw)
        self.assertEqual(r.status_code,200);etag=r.headers['ETag']
        self.assertEqual(self.c.get('/api/sync/content',headers={**sh,'If-None-Match':etag}).status_code,304)
        self.assertEqual(self.c.put('/api/sync/content',headers={**sh,'If-Match':'wrong-etag'},content=raw).status_code,412)
        stats=self.c.get('/api/assistant/stats',headers=self.h).json()
        self.assertTrue(stats['has_sync_data']);self.assertEqual(stats['unread_chapters'],1)
        found=self.c.get('/api/assistant/search?unread_only=true',headers=self.h).json()
        self.assertEqual(found,[{'title':'Test title','chapters':2,'read':1,'unread':1}])
        self.assertEqual(self.c.get('/api/sync/content',headers={'X-API-Token':'m'*40}).status_code,401)
