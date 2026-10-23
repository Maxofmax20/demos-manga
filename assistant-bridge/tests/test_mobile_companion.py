import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

import server


class MobileCompanionTests(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(server.app)
        self.mcp_token = "m" * 40
        self.sync_token = "s" * 40
        self.mcp_patch = patch.object(server, "MCP_TOKEN", self.mcp_token)
        self.sync_patch = patch.object(server, "SYNC_TOKEN", self.sync_token)
        self.mcp_patch.start()
        self.sync_patch.start()
        self.addCleanup(self.mcp_patch.stop)
        self.addCleanup(self.sync_patch.stop)

    def test_no_token_is_denied(self):
        for path in ("/api/assistant/stats", "/api/assistant/search"):
            with self.subTest(path=path):
                response = self.client.get(path)
                self.assertEqual(response.status_code, 401)
                self.assertEqual(response.headers.get("Cache-Control"), "no-store")

    def test_wrong_or_sync_token_is_denied(self):
        for token in ("wrong", self.sync_token):
            for path in ("/api/assistant/stats", "/api/assistant/search"):
                with self.subTest(token=token[:5], path=path):
                    response = self.client.get(path, headers={"Authorization": f"Bearer {token}"})
                    self.assertEqual(response.status_code, 401)

    def test_stats_empty_library(self):
        with patch.object(server, "get_library", return_value=[]):
            response = self.client.get(
                "/api/assistant/stats", headers={"Authorization": f"Bearer {self.mcp_token}"}
            )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["titles"], 0)
        self.assertIn("has_sync_data", response.json())
        self.assertEqual(response.headers["Cache-Control"], "no-store")

    def test_search_returns_metadata_only(self):
        safe_books = [
            {"title": "Example", "chapters": 5, "read": 3, "unread": 2},
            {"title": "Another", "chapters": 4, "read": 4, "unread": 0},
        ]
        with patch.object(server, "get_library", return_value=safe_books):
            response = self.client.get(
                "/api/assistant/search?query=Exam&unread_only=true&limit=1",
                headers={"Authorization": f"Bearer {self.mcp_token}"},
            )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), [safe_books[0]])
        self.assertEqual(response.headers["Cache-Control"], "no-store")


if __name__ == "__main__":
    unittest.main()


def test_assistant_library_is_bearer_protected_and_paginated(tmp_path, monkeypatch):
    # No real credentials or personal library data used in tests.
    from fastapi.testclient import TestClient
    import server
    fake = [{"title": f"Safe Manga {i:03d}", "chapters": 10, "read": i % 10,
             "unread": 10 - i % 10} for i in range(125)]
    monkeypatch.setattr(server, "get_library", lambda: fake)
    monkeypatch.setattr(server, "MCP_TOKEN", "x" * 48)
    monkeypatch.setattr(server, "BACKUP_PATH", tmp_path / "not-synced.protobuf")
    client = TestClient(server.app)
    headers = {"Authorization": "Bearer " + "x" * 48}
    assert client.get("/api/assistant/library").status_code == 401
    response = client.get("/api/assistant/library?offset=100&limit=50", headers=headers)
    assert response.status_code == 200
    assert response.json()["total"] == 125
    assert len(response.json()["items"]) == 25
    assert response.headers["cache-control"] == "no-store"
    assert client.get("/api/assistant/library?limit=101", headers=headers).status_code == 400
