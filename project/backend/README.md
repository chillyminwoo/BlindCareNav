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
POST /api/mobile/route
POST /api/mobile/emergency
POST /api/mobile/detections
GET  /api/admin/detections?area=hwagok&limit=50
GET  /video_feed
WS   /ws/stream
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

`/ws/stream` and `/video_feed` expect a local model file:

```text
yolo11n.pt
```

The model file is ignored by Git. Keep it locally in this backend folder when server-side stream annotation is needed.
