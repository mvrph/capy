"""Unit tests for rewards-earner (no live hardware required)."""

import json
import pytest
from unittest.mock import patch, MagicMock


def test_health_returns_ok(client):
    resp = client.get("/health")
    assert resp.status_code == 200
    data = resp.get_json()
    assert data["status"] == "ok"


def test_health_reports_not_configured_when_env_missing(client):
    resp = client.get("/health")
    data = resp.get_json()
    assert data["configured"] is False


def test_trigger_rewards_503_when_env_missing(client):
    resp = client.post("/trigger/rewards")
    assert resp.status_code == 503
    data = resp.get_json()
    assert data["status"] == "error"
    assert "PUPPET_MASTER_BASE_URL" in data["detail"]


def test_trigger_rewards_calls_puppet_master(client, monkeypatch):
    monkeypatch.setenv("PUPPET_MASTER_BASE_URL", "http://192.168.1.99:8080")
    monkeypatch.setenv("PUPPET_MASTER_REWARDS_MACRO_ID", "7")

    import app as rewards_app
    import importlib
    importlib.reload(rewards_app)

    mock_response = MagicMock()
    mock_response.json.return_value = {"status": "completed"}
    mock_response.raise_for_status = MagicMock()

    with patch("requests.post", return_value=mock_response) as mock_post, \
         patch("paho.mqtt.publish.single") as mock_mqtt:

        with rewards_app.app.test_client() as c:
            resp = c.post("/trigger/rewards")

        assert resp.status_code == 200
        mock_post.assert_called_once_with(
            "http://192.168.1.99:8080/macros/7/run", timeout=30
        )
        mock_mqtt.assert_called_once()
        call_kwargs = mock_mqtt.call_args
        payload = json.loads(call_kwargs[1]["payload"])
        assert payload["game"] == "rewards"


def test_trigger_rewards_502_on_puppet_master_failure(client, monkeypatch):
    monkeypatch.setenv("PUPPET_MASTER_BASE_URL", "http://192.168.1.99:8080")
    monkeypatch.setenv("PUPPET_MASTER_REWARDS_MACRO_ID", "7")

    import app as rewards_app
    import importlib
    importlib.reload(rewards_app)

    import requests as req
    with patch("requests.post", side_effect=req.ConnectionError("refused")):
        with rewards_app.app.test_client() as c:
            resp = c.post("/trigger/rewards")

    assert resp.status_code == 502


@pytest.fixture
def client():
    import app as rewards_app
    rewards_app.app.config["TESTING"] = True
    with rewards_app.app.test_client() as c:
        yield c
