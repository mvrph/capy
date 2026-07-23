# rewards-earner

Microservice that triggers the MS-Rewards daily tap sequence on a signed-in Android phone via **puppet_master** and emits `game=rewards` telemetry to MQTT.

## Architecture

```
POST /trigger/rewards
      │
      ▼
puppet_master (phone) → run MS-Rewards macro
      │
      ▼
MQTT broker (Mosquitto on HA Green)
  topic: home/game/rewards
  payload: {"game": "rewards", "result": {...}}
```

## Setup

```bash
cd rewards-earner
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
```

## Required environment variables

| Variable | Description |
|----------|-------------|
| `PUPPET_MASTER_BASE_URL` | puppet_master server URL on the phone, e.g. `http://192.168.1.x:8080` |
| `PUPPET_MASTER_REWARDS_MACRO_ID` | ID of the recorded MS-Rewards macro in puppet_master |
| `MQTT_HOST` | Mosquitto broker host (default: `localhost`) |
| `MQTT_PORT` | Mosquitto broker port (default: `1883`) |
| `MQTT_TOPIC` | Telemetry topic (default: `home/game/rewards`) |

## Run

```bash
export PUPPET_MASTER_BASE_URL=http://192.168.1.x:8080
export PUPPET_MASTER_REWARDS_MACRO_ID=<macro-id>
export MQTT_HOST=<ha-green-ip>
python app.py
```

## Trigger

```bash
curl -X POST http://localhost:5000/trigger/rewards
```

## Health check

```bash
curl http://localhost:5000/health
```

## Tests

```bash
pip install pytest
pytest test_app.py -v
```

## Blocked on (needs human action before live use)

1. **`PUPPET_MASTER_REWARDS_MACRO_ID`** — Record the MS-Rewards daily tap sequence in puppet_master on the signed-in phone, note the macro ID.
2. **`PUPPET_MASTER_BASE_URL`** — Find the IP:port of the puppet_master server running on the phone.
3. **Mosquitto broker** — Verify the broker is running on HA Green (`systemctl status mosquitto`).

Once these three are resolved, set the env vars and `POST /trigger/rewards` to run.
