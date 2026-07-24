"""
rewards-earner — triggers the MS-Rewards puppet_master macro on a signed-in phone
and emits game=rewards telemetry over MQTT.

Required env vars (see README):
  PUPPET_MASTER_BASE_URL   e.g. http://192.168.1.x:8080
  PUPPET_MASTER_REWARDS_MACRO_ID  e.g. 42
  MQTT_HOST                Mosquitto broker hostname/IP  (default: localhost)
  MQTT_PORT                broker port                  (default: 1883)
  MQTT_TOPIC               telemetry topic              (default: home/game/rewards)
"""

import os
import json
import logging
import requests
import paho.mqtt.publish as mqtt_publish
from flask import Flask, jsonify

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

app = Flask(__name__)

PUPPET_MASTER_BASE_URL = os.environ.get("PUPPET_MASTER_BASE_URL", "")
PUPPET_MASTER_REWARDS_MACRO_ID = os.environ.get("PUPPET_MASTER_REWARDS_MACRO_ID", "")
MQTT_HOST = os.environ.get("MQTT_HOST", "localhost")
MQTT_PORT = int(os.environ.get("MQTT_PORT", "1883"))
MQTT_TOPIC = os.environ.get("MQTT_TOPIC", "home/game/rewards")


def _trigger_macro():
    """POST to puppet_master to run the MS-Rewards macro."""
    if not PUPPET_MASTER_BASE_URL or not PUPPET_MASTER_REWARDS_MACRO_ID:
        raise EnvironmentError(
            "PUPPET_MASTER_BASE_URL and PUPPET_MASTER_REWARDS_MACRO_ID must be set"
        )
    url = f"{PUPPET_MASTER_BASE_URL}/macros/{PUPPET_MASTER_REWARDS_MACRO_ID}/run"
    resp = requests.post(url, timeout=30)
    resp.raise_for_status()
    log.info("puppet_master macro triggered: %s", url)
    return resp.json()


def _emit_telemetry(result: dict):
    """Publish game=rewards telemetry to MQTT."""
    payload = json.dumps({"game": "rewards", "result": result})
    mqtt_publish.single(
        MQTT_TOPIC,
        payload=payload,
        hostname=MQTT_HOST,
        port=MQTT_PORT,
    )
    log.info("telemetry published to %s: %s", MQTT_TOPIC, payload)


@app.route("/trigger/rewards", methods=["POST"])
def trigger_rewards():
    try:
        result = _trigger_macro()
        _emit_telemetry(result)
        return jsonify({"status": "ok", "result": result}), 200
    except EnvironmentError as exc:
        log.error("configuration error: %s", exc)
        return jsonify({"status": "error", "detail": str(exc)}), 503
    except requests.RequestException as exc:
        log.error("puppet_master call failed: %s", exc)
        return jsonify({"status": "error", "detail": str(exc)}), 502
    except Exception as exc:
        log.error("unexpected error: %s", exc)
        return jsonify({"status": "error", "detail": str(exc)}), 500


@app.route("/health", methods=["GET"])
def health():
    configured = bool(PUPPET_MASTER_BASE_URL and PUPPET_MASTER_REWARDS_MACRO_ID)
    return jsonify({
        "status": "ok",
        "configured": configured,
        "mqtt_host": MQTT_HOST,
        "mqtt_port": MQTT_PORT,
        "mqtt_topic": MQTT_TOPIC,
    }), 200


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
