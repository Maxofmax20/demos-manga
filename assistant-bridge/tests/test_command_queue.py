import hashlib
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

import command_queue
import server


class CommandValidationTests(unittest.TestCase):
    def test_action_allowlist(self):
        valid = [
            ("mark_read", {"manga_id": "1" * 24, "read": True}),
            ("library_set", {"manga_id": "1" * 24, "favorite": False}),
            ("refresh_chapters", {"manga_id": "1" * 24}),
            ("set_setting", {"name": "dark_mode", "value": True}),
            ("download_chapter", {"manga_id": "1" * 24, "chapter_id": "2" * 24}),
            ("open_reader", {"manga_id": "1" * 24, "chapter_id": "2" * 24}),
            ("open_screen", {"screen": "extensions"}),
            ("install_extension", {"package_name": "eu.kanade.tachiyomi.extension.en.example"}),
            ("uninstall_extension", {"package_name": "eu.kanade.tachiyomi.extension.en.example"}),
            ("library_add", {"source_id": 1234, "source_url": "/manga/123"}),
        ]
        for action, params in valid:
            with self.subTest(action=action):
                self.assertEqual(command_queue.validate_command(action, params), params)

    def test_reject_unsafe_commands(self):
        invalid = [
            ("run_shell", {}),
            ("install_extension", {"package_name": "com.random.injector"}),
            ("install_extension", {"package_name": "eu.kanade.tachiyomi.extension.en.nsfw"}),
            ("install_extension", {"package_name": "eu.kanade.tachiyomi.extension.en.hentai"}),
            ("install_extension", {"package_name": "https://example.org/whatever.apk"}),
            ("set_setting", {"name": "disable_content_filter", "value": True}),
            ("set_setting", {"name": "dark_mode", "value": "yes"}),
            ("open_screen", {"screen": "device_settings"}),
            ("library_add", {"source_id": 5, "source_url": "https://example.org/test"}),
            ("mark_read", {"manga_id": "bad", "read": True}),
        ]
        for action, params in invalid:
            with self.subTest(action=action):
                with self.assertRaises(ValueError):
                    command_queue.validate_command(action, params)

    def test_device_isolation_and_replay(self):
        with tempfile.TemporaryDirectory() as folder:
            db = Path(folder) / "commands.sqlite3"
            command_queue.initialize(db)
            created = command_queue.create(db, "device-one", "open_screen", {"screen": "library"})
            cid = created["command_id"]
            self.assertEqual(len(command_queue.pending(db, "device-one")), 1)
            self.assertEqual(command_queue.pending(db, "device-two"), [])
            self.assertFalse(command_queue.finish(db, "device-two", cid, "completed"))
            self.assertTrue(command_queue.finish(db, "device-one", cid, "rejected"))
            self.assertFalse(command_queue.finish(db, "device-one", cid, "completed"))
            self.assertEqual(command_queue.lookup(db, cid)["state"], "rejected")


class CommandEndpointsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.db = Path(self.tmp.name) / "commands.sqlite3"
        command_queue.initialize(self.db)
        self.device_token = "d" * 48
        self.owner_token = "m" * 48
        self.device = {"id": "paired-phone", "hash": hashlib.sha256(self.device_token.encode()).hexdigest(), "expires": time.time() + 86400}
        patches = [
            patch.object(server, "COMMAND_DB", self.db),
            patch.object(server, "MCP_TOKEN", self.owner_token),
            patch.object(server, "SYNC_TOKEN", "s" * 48),
            patch.object(server, "_device_keys", return_value=[self.device]),
        ]
        for p in patches:
            p.start()
            self.addCleanup(p.stop)
        self.client = TestClient(server.app)
        self.owner_auth = {"Authorization": "Bearer " + self.owner_token}
        self.phone_auth = {"X-API-Token": self.device_token}

    def test_read_only_does_not_execute_automatically(self):
        request = {"device_id": "paired-phone", "action": "mark_read", "args": {"manga_id": "a" * 24, "read": True}}
        queued = self.client.post("/api/assistant/commands", headers=self.owner_auth, json=request)
        self.assertEqual(queued.status_code, 202)
        cid = queued.json()["command_id"]
        self.assertEqual(queued.json()["state"], "pending_confirmation")
        self.assertEqual(self.client.get(f"/api/assistant/commands/{cid}", headers=self.owner_auth).json()["state"], "pending_confirmation")
        self.assertEqual(self.client.get("/api/device/commands", headers=self.phone_auth).json()[0]["command_id"], cid)
        self.assertEqual(self.client.post(f"/api/device/commands/{cid}/result", headers=self.phone_auth, json={"state": "completed"}).status_code, 200)
        self.assertEqual(self.client.get(f"/api/assistant/commands/{cid}", headers=self.owner_auth).json()["state"], "completed")
        self.assertEqual(self.client.get("/api/device/commands", headers=self.phone_auth).json(), [])

    def test_unauthorized_and_unpaired_denied(self):
        request = {"device_id": "paired-phone", "action": "open_screen", "args": {"screen": "extensions"}}
        self.assertEqual(self.client.post("/api/assistant/commands", json=request).status_code, 401)
        self.assertEqual(self.client.get("/api/device/commands").status_code, 401)
        self.assertEqual(self.client.get("/api/device/commands", headers={"X-API-Token": "s" * 48}).status_code, 401)
        request["device_id"] = "unknown"
        self.assertEqual(self.client.post("/api/assistant/commands", headers=self.owner_auth, json=request).status_code, 400)

    def test_unsafe_request_rejected(self):
        for action, args in [
            ("install_extension", {"package_name": "com.bad.payload"}),
            ("set_setting", {"name": "disable_nsfw_filter", "value": True}),
        ]:
            request = {"device_id": "paired-phone", "action": action, "args": args}
            self.assertEqual(self.client.post("/api/assistant/commands", headers=self.owner_auth, json=request).status_code, 400)


if __name__ == "__main__":
    unittest.main()
