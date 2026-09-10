"""Контракты публичной runtime-конфигурации FastAPI frontend."""

import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi import HTTPException

import app as frontend_app


class ConfigEndpointTest(unittest.TestCase):
    def config(self, values: dict[str, str]):
        environment = {"AVATAR_PROVIDER": "simli", **values}
        with patch.dict(os.environ, environment, clear=True):
            return frontend_app.config()

    def test_simli_config_never_exposes_server_side_credentials(self):
        config = self.config(
            {
                "SIMLI_API_KEY": "server-only",
                "ELEVENLABS_API_KEY": "server-only",
            }
        )

        self.assertEqual(config["avatar_provider"], "simli")
        self.assertNotIn("agent_id", config)
        self.assertNotIn("client_key", config)
        self.assertNotIn("SIMLI_API_KEY", config)
        self.assertNotIn("ELEVENLABS_API_KEY", config)

    def test_did_config_reads_only_environment_values(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(frontend_app, "BASE_DIR", Path(directory)):
                config = self.config(
                    {
                        "AVATAR_PROVIDER": "did",
                        "DID_AGENT_ID": "agent-from-environment",
                        "DID_CLIENT_KEY": "client-from-environment",
                    }
                )

        self.assertEqual(config["agent_id"], "agent-from-environment")
        self.assertEqual(config["client_key"], "client-from-environment")

    def test_did_config_requires_both_runtime_values(self):
        for missing in ("DID_AGENT_ID", "DID_CLIENT_KEY"):
            values = {
                "AVATAR_PROVIDER": "did",
                "DID_AGENT_ID": "agent-from-environment",
                "DID_CLIENT_KEY": "client-from-environment",
            }
            values.pop(missing)

            with self.assertRaises(HTTPException) as caught:
                self.config(values)

            self.assertEqual(caught.exception.status_code, 503)
            self.assertEqual(caught.exception.detail, "D-ID configuration is unavailable.")
