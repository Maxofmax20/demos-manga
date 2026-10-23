import hashlib
import json
import sqlite3
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

import command_queue
import extension_inventory
import server


class ExtensionCommandIntegrityTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.db = Path(self.directory.name) / "test.sqlite3"
        command_queue.initialize(self.db)
        self.owner_key = "o" * 48
        self.device_key = "p" * 48
        self.device_id = "paired-phone"
        keys = [{
            "id": self.device_id,
            "hash": hashlib.sha256(self.device_key.encode()).hexdigest(),
            "expires": time.time() + 3600,
        }]
        for target, value in [
            ("COMMAND_DB", self.db),
            ("MCP_TOKEN", self.owner_key),
            ("SYNC_TOKEN", "s" * 48),
            ("_device_keys", lambda: keys),
        ]:
            patcher = patch.object(server, target, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        self.client = TestClient(server.app)
        self.auth = {"Authorization": "Bearer " + self.owner_key}
        self.package = "eu.kanade.tachiyomi.extension.en.example"

    def queue(self, action, package=None):
        body = {
            "device_id": self.device_id,
            "action": action,
            "args": {"package_name": package or self.package},
        }
        return self.client.post("/api/assistant/commands", headers=self.auth, json=body)

    def snapshot(self, installed, updated=None):
        extension_inventory.store(
            self.db,
            self.device_id,
            [{"package_name": self.package, "name": "Example", "installed": installed}],
        )
        if updated is not None:
            with sqlite3.connect(self.db) as db:
                db.execute(
                    "UPDATE extension_inventory SET updated = ? WHERE device_id = ?",
                    (updated, self.device_id),
                )

    def test_cannot_install_without_current_snapshot(self):
        res = self.queue("install_extension")
        self.assertEqual(res.status_code, 400)
        self.assertIn("missing or stale", res.json()["detail"])

    def test_install_only_if_currently_available(self):
        self.snapshot(False)
        self.assertEqual(self.queue("install_extension").status_code, 202)
        self.assertEqual(self.queue("uninstall_extension").status_code, 400)
        self.assertEqual(
            self.queue("install_extension", "eu.kanade.tachiyomi.extension.en.unknown").status_code,
            400,
        )

    def test_uninstall_only_if_currently_installed(self):
        self.snapshot(True)
        self.assertEqual(self.queue("uninstall_extension").status_code, 202)
        self.assertEqual(self.queue("install_extension").status_code, 400)

    def test_stale_snapshot_denied(self):
        self.snapshot(True, updated=int(time.time()) - 2 * 86400)
        self.assertEqual(self.queue("uninstall_extension").status_code, 400)

    def test_installed_snapshot_takes_precedence(self):
        snapshot = [
            {"package_name": self.package, "name": "Example", "installed": False},
            {"package_name": self.package, "name": "Example", "installed": True},
        ]
        self.assertEqual(extension_inventory.store(self.db, self.device_id, snapshot), 1)
        state = extension_inventory.get(self.db, self.device_id)
        self.assertEqual(state["extensions"], [snapshot[1]])


if __name__ == "__main__":
    unittest.main()
