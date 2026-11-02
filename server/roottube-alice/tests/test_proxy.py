"""Offline smoke tests: authentication, request limits, and response parsing."""
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

import httpx
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
os.environ.setdefault("YANDEX_API_KEY", "offline-unit-test-key")
os.environ.setdefault("YANDEX_FOLDER_ID", "offline-unit-test-folder")
os.environ.setdefault("ALICE_PROXY_TOKEN", "offline-unit-test-proxy-token-1234567890")

import main  # noqa: E402

client = TestClient(main.app)
TEST_AUTH = {"Authorization": f"Bearer {main.PROXY_TOKEN}"}


class ProxyTests(unittest.TestCase):
    def test_health(self):
        self.assertEqual(client.get("/health").json(), {"status": "ok"})

    def test_requires_auth(self):
        response = client.post("/v1/translate", json={"text": "hello"})
        self.assertEqual(response.status_code, 401)
        bad = client.post(
            "/v1/translate",
            json={"text": "hello"},
            headers={"Authorization": "Bearer bad-token"},
        )
        self.assertEqual(bad.status_code, 401)

    def test_rejects_large_request(self):
        response = client.post(
            "/v1/translate", headers=TEST_AUTH, json={"text": "a" * 5000}
        )
        self.assertEqual(response.status_code, 413)

    def test_valid_translation(self):
        async def fake_post(_self, url, *, headers, json):
            self.assertIn("aliceai-llm-flash", json["model"])
            self.assertTrue(headers["Authorization"].startswith("Api-Key "))
            return httpx.Response(
                200,
                json={"choices": [{"message": {"content": "Привет, братан!"}}]},
                request=httpx.Request("POST", url),
            )

        with patch.object(httpx.AsyncClient, "post", fake_post):
            response = client.post(
                "/v1/translate", headers=TEST_AUTH, json={"text": "Hey bro!"}
            )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(
            response.json(), {"translation": "Привет, братан!"}
        )


if __name__ == "__main__":
    unittest.main()
