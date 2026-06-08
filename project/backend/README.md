# BlindCareNav Backend

FastAPI API server for the A-eye blind pedestrian navigation MVP.

This folder is backend-only. The control web dashboard should live in `../frontend`, and the Android app lives in `../app`.

## Run

```powershell
cd C:\Users\KCCISTC\khs_project_blindNav\project\backend
python -m pip install -r requirements.txt
python -m uvicorn app:app --host 127.0.0.1 --port 8000 --ws websockets
```

API base URL:

```text
http://localhost:8000
```

## Core APIs

```text
GET  /api/health
GET  /api/tactile-blocks?area=hwagok
GET  /api/risks?area=hwagok
GET  /api/nearby?area=hwagok&lat=37.5421&lng=126.8413
POST /api/guidance
GET  /api/admin/summary?area=hwagok
POST /api/places/search
POST /api/assistant/command
POST /api/mobile/route
POST /api/mobile/scene-guidance
POST /api/mobile/emergency
POST /api/mobile/detections
POST /api/set_destination
GET  /api/get_destination
POST /api/control/route-candidates
GET  /api/control/current-route
POST /api/control/clear_destination
GET  /api/admin/detections?area=hwagok&limit=50
GET  /api/admin/logs?area=hwagok&type=detections&limit=50
GET  /api/admin/logs?area=hwagok&type=emergency&limit=50
POST /api/admin/logs/clear?area=hwagok&type=detections
POST /api/admin/logs/clear?area=hwagok&type=emergency
GET  /video_feed
WS   /ws/stream
```

## Smoke Tests

```powershell
$base="http://127.0.0.1:8000"

Invoke-RestMethod "$base/api/health"
Invoke-RestMethod "$base/api/admin/summary?area=hwagok"

$body = @{
  area="hwagok"
  destinationKeyword="화곡역 삼번 출구로 안내해줘"
  currentLocation=@{ lat=37.5421; lng=126.8413 }
} | ConvertTo-Json -Depth 5

Invoke-RestMethod "$base/api/mobile/route" `
  -Method Post `
  -ContentType "application/json" `
  -Body $body

$assistantBody = @{
  area="hwagok"
  deviceId="demo-phone-01"
  utterance="누니야 화곡역 3번 출구로 안내해줘"
  currentLocation=@{ lat=37.5421; lng=126.8413 }
} | ConvertTo-Json -Depth 5

Invoke-RestMethod "$base/api/assistant/command" `
  -Method Post `
  -ContentType "application/json" `
  -Body $assistantBody
```

Before a demo, clear local runtime logs if needed:

```powershell
Invoke-RestMethod "$base/api/admin/logs/clear?area=hwagok&type=detections" -Method Post
Invoke-RestMethod "$base/api/admin/logs/clear?area=hwagok&type=emergency" -Method Post
```

## Data

Data is intentionally file-based for the MVP:

```text
data/tactile_blocks_hwagok.geojson
data/risk_points_hwagok.json
data/nearby_places_hwagok.json
```

Runtime logs such as `data/mobile_detection_logs_hwagok.jsonl` are ignored by Git.

## YOLO Stream Model

`/ws/stream` and `/video_feed` first look for the team model:

```text
models/best.pt
```

If that file is missing, the backend falls back to the legacy file:

```text
yolo11n.pt
```

The model files are ignored by Git. Keep `models/best.pt` locally in this backend folder when server-side stream annotation is needed.

The current team model classes are:

```text
bicycle, braille_block, car, green_light, kickboard, motorcycle, person, red_light
```

## Local LLM

The assistant endpoint supports LM Studio or any OpenAI-compatible local server.
By default it tries the demo LM Studio URL below and falls back to rule-based handling if the model is unavailable.

```powershell
$env:BLINDCARE_ASSISTANT_USE_LLM="1"
$env:BLINDCARE_OPENAI_BASE_URL="http://172.19.176.1:1234/v1"
$env:BLINDCARE_OPENAI_MODEL="your-loaded-model-id"
```

If `BLINDCARE_OPENAI_MODEL` is omitted, the backend calls `GET /v1/models` and uses the first loaded model.
