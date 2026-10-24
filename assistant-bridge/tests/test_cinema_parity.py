import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from fastapi.testclient import TestClient
import server
from test_chapters_metadata import v, obj

def fake_backup():
    chapters = [obj(1,b"/c-1")+obj(2,b"First")+v(4,1),
                obj(1,b"/c-2")+obj(2,b"Second")+v(4,0)]
    book=v(1,4321)+obj(2,b"/manga/item")+obj(3,b"Example")+b"".join(obj(16,x) for x in chapters)
    return obj(1,book)

class CinemaParityTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        folder=Path(self.temp.name)
        self.raw=fake_backup()
        backup=folder/"sync.protobuf";backup.write_bytes(self.raw)
        for name,value in (("BACKUP_PATH",backup),("MCP_TOKEN","m"*48),
                           ("SYNC_TOKEN","s"*48),("COMMAND_DB",folder/"commands.db"),
                           ("DEVICE_KEYS_PATH",folder/"device-keys.json")):
            mocked=patch.object(server,name,value);mocked.start()
            self.addCleanup(mocked.stop)
        self.client=TestClient(server.app)
        self.headers={"Authorization":"Bearer "+"m"*48}
        self.manga_id=hashlib.sha256(b"4321|/manga/item").hexdigest()[:24]

    def test_owner_auth_revision_exact_chapters(self):
        for endpoint in ("/api/assistant/library-revision",
                         "/api/assistant/library-record?manga_id="+self.manga_id):
            self.assertEqual(self.client.get(endpoint).status_code,401)
        overview=self.client.get("/api/assistant/library-revision",headers=self.headers)
        self.assertEqual(overview.status_code,200)
        self.assertEqual(overview.json()["revision"],hashlib.sha256(self.raw).hexdigest())
        self.assertEqual(overview.json()["titles"],1)
        response=self.client.get("/api/assistant/library-record?manga_id="+self.manga_id,
                                 headers=self.headers)
        self.assertEqual(response.status_code,200,response.text)
        data=response.json()
        self.assertEqual(data["id"],self.manga_id)
        self.assertEqual(data["total_chapters"],2)
        self.assertEqual(data["read_chapters"],1)
        self.assertIsNone(data["category_ids"])
        self.assertEqual([e["read"] for e in data["chapters"]],[True,False])
        self.assertEqual(data["revision"],overview.json()["revision"])
        self.assertEqual(response.headers["cache-control"],"private, no-store")
        self.assertEqual(self.client.get("/api/assistant/library-record?manga_id=bad",
                                         headers=self.headers).status_code,400)

    def test_unknown_or_restricted_not_exposed(self):
        self.assertEqual(self.client.get("/api/assistant/library-record?manga_id="+"0"*24,
                                         headers=self.headers).status_code,404)
        raw=obj(1,v(1,123)+obj(2,b"/adult")+obj(3,b"Excluded")+obj(7,b"adult"))
        server.BACKUP_PATH.write_bytes(raw)
        ident=hashlib.sha256(b"123|/adult").hexdigest()[:24]
        self.assertEqual(self.client.get("/api/assistant/library-record?manga_id="+ident,
                                         headers=self.headers).status_code,404)

if __name__=="__main__":unittest.main()
