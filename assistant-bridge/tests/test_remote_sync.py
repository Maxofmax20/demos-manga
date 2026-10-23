import hashlib
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch
from fastapi.testclient import TestClient
import server
import remote_sync

class RemoteSyncTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.db = Path(self.tmp.name) / "remote.sqlite"
        self.owner = "o" * 48
        self.phone = "p" * 48
        self.device = "paired-phone"
        self.keys = [{"id": self.device, "hash": hashlib.sha256(self.phone.encode()).hexdigest(),
                      "expires": time.time() + 86400}]
        remote_sync.initialize(self.db)
        for key, value in [
            ("COMMAND_DB", self.db),
            ("MCP_TOKEN", self.owner),
            ("SYNC_TOKEN", "s" * 48),
            ("_device_keys", lambda: self.keys),
        ]:
            patcher = patch.object(server, key, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        self.c = TestClient(server.app)
        self.owner_auth = {"Authorization": "Bearer " + self.owner}
        self.phone_auth = {"X-API-Token": self.phone}

    def queue(self):
        return self.c.post("/api/assistant/remote-sync", headers=self.owner_auth,
                           json={"device_id": self.device})

    def test_requires_phone_optin_and_owner_scope(self):
        self.assertEqual(self.queue().status_code, 400)
        self.assertEqual(self.c.post("/api/assistant/remote-sync",
            json={"device_id": self.device}).status_code, 401)
        self.assertEqual(self.c.post("/api/device/remote-sync/consent",
            headers=self.owner_auth, json={"enabled": True}).status_code, 401)
        self.assertEqual(self.c.post("/api/device/remote-sync/consent",
            headers=self.phone_auth, json={"enabled": "yes"}).status_code, 400)
        self.assertEqual(self.c.post("/api/device/remote-sync/consent",
            headers=self.phone_auth, json={"enabled": True}).status_code, 200)
        self.assertEqual(self.queue().status_code, 202)

    def test_claim_finish_dedupe_and_owner_status(self):
        remote_sync.set_consent(self.db, self.device, True)
        first = self.queue().json()
        self.assertEqual(first["state"], "pending")
        self.assertEqual(self.queue().json()["request_id"], first["request_id"])
        fetched = self.c.get("/api/device/remote-sync/next", headers=self.phone_auth).json()["request"]
        self.assertEqual(fetched["request_id"], first["request_id"])
        self.assertEqual(fetched["state"], "running")
        self.assertIsNone(self.c.get("/api/device/remote-sync/next", headers=self.phone_auth).json()["request"])
        self.assertEqual(self.c.post(f"/api/device/remote-sync/{first['request_id']}/result",
            headers=self.phone_auth, json={"state": "completed"}).status_code, 200)
        self.assertEqual(self.c.get(f"/api/assistant/remote-sync/{first['request_id']}",
            headers=self.owner_auth).json()["state"], "completed")
        self.assertNotEqual(self.queue().json()["request_id"], first["request_id"])

    def test_revocation_blocks_pending_and_clears_requests(self):
        remote_sync.set_consent(self.db, self.device, True)
        req = self.queue().json()
        self.assertEqual(self.c.post("/api/device/remote-sync/consent",
            headers=self.phone_auth, json={"enabled": False}).status_code, 200)
        self.assertEqual(self.c.get(f"/api/assistant/remote-sync/{req['request_id']}",
            headers=self.owner_auth).json()["state"], "cancelled")
        self.assertEqual(self.queue().status_code, 400)
        self.assertIsNone(self.c.get("/api/device/remote-sync/next",
                                    headers=self.phone_auth).json()["request"])

    def test_cannot_complete_another_device_request(self):
        remote_sync.set_consent(self.db, self.device, True)
        req = self.queue().json()
        self.assertEqual(self.c.post(f"/api/device/remote-sync/{req['request_id']}/result",
             headers=self.phone_auth, json={"state": "completed"}).status_code, 409)
        self.assertEqual(self.c.post(f"/api/device/remote-sync/{req['request_id']}/result",
             json={"state": "completed"}).status_code, 401)

if __name__ == "__main__":
    unittest.main()
