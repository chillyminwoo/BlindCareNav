# A-eye Native App Merge

Kakao Map 관제 화면, Tmap 보행자 경로 API, A-eye JSON 기반 FastAPI API, 팀원 YOLO WebSocket 스트리밍을 한 폴더에 합친 시연용 작업본입니다.

## 구조

```text
native-app-merge/
  app.py                         # 통합 FastAPI 서버
  yolo11n.pt                     # 팀원 YOLO 스트리밍 모델
  data/                          # JSON/GeoJSON 기반 A-eye 데이터
  src/KakaoMapView.jsx           # Kakao 관제 지도 + Tmap route + 앱 감지 마커
  src/MobileStream.jsx           # 모바일 웹 카메라 스트리밍 화면
  src/engine/routeEngine.js      # Tmap pedestrian route 호출
  src/services/api.js            # FastAPI API 클라이언트
```

## 환경 변수

`.env.example`을 참고해 `.env.local`을 만드세요.

```text
VITE_KAKAO_JS_KEY=...
VITE_KAKAO_REST_KEY=...
VITE_TMAP_APP_KEY=...
VITE_API_BASE_URL=http://localhost:8000
VITE_STREAM_BASE_URL=http://localhost:8000
```

API 키는 코드에 직접 넣지 않습니다.

## 개발 실행

터미널 1, 통합 FastAPI 서버:

```powershell
cd C:\Users\KCCISTC\tmapdemo\vite-project\native-app-merge
python -m pip install -r requirements.txt
python -m uvicorn app:app --reload --host 0.0.0.0 --port 8000
```

터미널 2, Vite 프론트:

```powershell
cd C:\Users\KCCISTC\tmapdemo\vite-project\native-app-merge
npm install
npm run dev -- --host 0.0.0.0
```

관제 화면:

```text
http://localhost:5173
```

모바일 웹 스트리밍 화면:

```text
http://localhost:5173?mode=mobile
```

## 배포형 시연

프론트를 빌드하면 FastAPI 서버 하나로 정적 파일까지 서빙할 수 있습니다.

```powershell
npm run build
python -m uvicorn app:app --host 0.0.0.0 --port 8000
```

이후 접속:

```text
http://localhost:8000
http://localhost:8000?mode=mobile
```

## 병합된 기능

- Kakao Map 기반 관제 UI
- Tmap 보행자 경로 API 기반 route 후보 생성
- 점자블록 GeoJSON 표시
- Overpass 횡단보도 POI 표시
- 기존 위험 지점 마커 표시
- Android native 앱 또는 모바일 API가 올린 `/api/mobile/detections` 로그를 노란색 `AI` 마커로 표시
- 팀원 모바일 웹 카메라 프레임을 `/ws/stream`으로 보내고, `/video_feed`에서 YOLO 추론 화면 표시

## 주요 API

```text
GET  /api/tactile-blocks?area=hwagok
GET  /api/risks?area=hwagok
GET  /api/nearby?area=hwagok&lat=...&lng=...
POST /api/guidance
POST /api/places/search
POST /api/mobile/detections
GET  /api/admin/detections?area=hwagok&limit=50
GET  /api/admin/summary?area=hwagok
GET  /video_feed
WS   /ws/stream
```

## 주의

- `node_modules`와 `dist`는 공유 대상이 아닙니다.
- `.env.local`은 `.gitignore`에 걸려 있으므로 키 저장용으로만 사용하세요.
- 이 작업본은 관제 웹 중심 병합본입니다. Android native 앱은 기존 `native-app/` 폴더를 유지합니다.
