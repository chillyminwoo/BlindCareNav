# BlindCareNav Control Frontend

Kakao Map control dashboard for the Hwagok demo area.

The Android app sends destination and detection events to the backend. This frontend polls the backend, draws tactile blocks, shows AI detection markers, and calculates Tmap route candidates with a tactile-block score.

## Setup

```powershell
cd C:\Users\KCCISTC\khs_project_blindNav\project\frontend
Copy-Item .env.example .env
```

Edit `.env`:

```text
VITE_API_BASE_URL=http://127.0.0.1:8000
VITE_KAKAO_JS_KEY=...
VITE_KAKAO_REST_KEY=...
VITE_TMAP_APP_KEY=...
```

When using Vite proxy on the same PC, `VITE_API_BASE_URL` can be empty.

## Run

```powershell
npm install
npm run dev
```

Open:

```text
http://localhost:5173
```

## Backend APIs Used

```text
GET  /api/tactile-blocks?area=hwagok
GET  /api/risks?area=hwagok
GET  /api/admin/summary?area=hwagok
GET  /api/admin/detections?area=hwagok&limit=50
GET  /api/get_destination
POST /api/control/route-candidates
GET  /video_feed
```
