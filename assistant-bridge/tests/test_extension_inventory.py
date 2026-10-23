import hashlib
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

import extension_inventory
import server


class ExtensionInventoryTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.db = Path(self.folder.name) / "test.sqlite3"
        self.owner = "m" * 48
        self.device_token = "d" * 48
        patches = [
            patch.object(server, "COMMAND_DB", self.db),
            patch.object(server, "MCP_TOKEN", self.owner),
            patch.object(server, "SYNC_TOKEN", "s" * 48),
            patch.object(server, "_device_keys", return_value=[
                {"id": "phone", "hash": hashlib.sha256(self.device_token.encode()).hexdigest(), "expires": time.time() + 3600},
            ]),
        ]
        for patcher in patches:
            patcher.start()
            self.addCleanup(patcher.stop)
        self.client = TestClient(server.app)

    def test_device_inventory_filtered_and_owner_only(self):
        sample = [
            {"package_name": "eu.kanade.tachiyomi.extension.en.example", "name": "Example", "installed": True},
            {"package_name": "eu.kanade.tachiyomi.extension.en.adult", "name": "Adult", "installed": False},
            {"package_name": "not.allowed.plugin", "name": "Untrusted", "installed": True},
        ]
        result = self.client.post("/api/device/extensions", json=sample,
                                  headers={"X-API-Token": self.device_token})
        self.assertEqual(result.status_code, 200)
        self.assertEqual(result.json()["count"], 1)
        self.assertEqual(self.client.get("/api/assistant/extensions?device_id=phone").status_code, 401)
        response = self.client.get(
            "/api/assistant/extensions?device_id=phone",
            headers={"Authorization": "Bearer " + self.owner},
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["extensions"], sample[:1])
        self.assertEqual(response.headers["Cache-Control"], "no-store")

    def test_snapshot_requires_pairing(self):
        response = self.client.post(
            "/api/device/extensions",
            json=[],
            headers={"X-API-Token": "s" * 48},
        )
        self.assertEqual(response.status_code, 401)


if __name__ == "__main__":
    unittest.main()
