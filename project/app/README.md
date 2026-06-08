# 누니 Android App

Android native camera + YOLO demo app for the A-eye MVP.

## What It Does

- Opens the rear camera with CameraX.
- Runs the team YOLO TFLite 8-class model on-device.
- Converts detections into Korean TTS guidance.
- Uses large yellow/black controls for low-vision and screen-reader-friendly operation.
- Lets the phone test connectivity to the A-eye FastAPI server.
- Posts obstacle detection events to the FastAPI server with GPS and heading.
- Sends voice commands to `POST /api/assistant/command` first, with local rule fallback.

## Voice-First Demo Flow

On launch, the app stays on the black/yellow Nuni listening surface. It waits for the wake phrase instead of showing buttons or opening a speech popup.

Flow:

```text
앱 실행
→ "누니야" 호출
→ "어떤 일을 할까요?"
→ 명령 인식
→ POST /api/assistant/command
→ TTS 응답
→ 다시 "누니야" 대기
```

The current implementation sends commands to the FastAPI assistant endpoint first. Local rules remain as a fallback when the server is unavailable.

Try commands such as:

```text
누니야 화곡역 3번 출구로 안내해줘
누니야 화곡역 삼번 출구로 안내해줘
누니야 다시 안내
누니야 다음 안내
누니야 주변 편의점
누니야 근처 화장실 알려줘
누니야 위험 정보
누니야 장애물 알려줘
누니야 현재 위치
누니야 긴급 연락
누니야 안내 종료
누니야 설정 열어
누니야 즐겨찾기
누니야 즐겨찾기 저장
누니야 최근 목적지
누니야 카메라 스트리밍 시작
누니야 스트리밍 모드 켜줘
누니야 카메라 스트리밍 중지
누니야 안전한 길로 다시 안내해줘
누니야 일반 안내 모드
누니야 점자 안내 모드
```

When a route starts, the app enters general guidance mode by default. General mode asks the backend to summarize current YOLO detections every 10-15 seconds. Tactile mode focuses on braille-block sections and interrupts when the model sees a braille block and an obstacle together.

## Current Demo Model

`app/src/main/assets/blindcare_best_float32.tflite`

The active model is a YOLO float32 TFLite model exported from the team `best.pt`. It uses the 8 labels in `app/src/main/assets/blindcare_labels.txt`.

Current labels:

```text
bicycle
braille_block
car
green_light
kickboard
motorcycle
person
red_light
```

The previous demo models are still kept as:

```text
app/src/main/assets/yolov8n_demo.tflite
app/src/main/assets/yolov8n_coco80_float32.tflite
app/src/main/assets/coco.txt
```

`YoloDetector` supports common YOLOv8 TFLite output layouts:

- `[1, 12, 8400]`
- `[1, 8400, 12]`
- `[1, 84, 8400]`
- `[1, 8400, 84]`

If the teammate model uses a different output layout, adjust `YoloDetector.parseOutput`.

Current verified model shape:

```text
input:  [1, 640, 640, 3] float32
output: [1, 12, 8400] float32
```

## Build

Open `project/app` in Android Studio and run the `app` configuration.

Command-line build on this PC:

```powershell
cd C:\Users\KCCISTC\khs_project_blindNav\project\app
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
& 'C:\Users\KCCISTC\.gradle\wrapper\dists\gradle-8.14.3-all\10utluxaxniiv4wxiphsi49nj\gradle-8.14.3\bin\gradle.bat' :app:assembleDebug --no-daemon
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Phone Testing

Install with Android Studio, or with `adb` if it is available:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

When testing the FastAPI server from a real phone:

- `localhost` and `127.0.0.1` mean the phone itself, not the PC.
- Use a tunnel URL such as ngrok or Cloudflare Tunnel, or put PC and phone on the same Wi-Fi and use the PC LAN IP.
- Enter that base URL in the app's server URL field.

Examples:

```text
https://your-demo-tunnel.ngrok-free.app
http://192.168.0.23:8000
```

When a risk object is detected, the app posts to:

```text
POST /api/mobile/detections
```

The backend stores JSONL logs in:

```text
project/backend/data/mobile_detection_logs_hwagok.jsonl
```

You can inspect recent detections with:

```text
GET /api/admin/detections?area=hwagok&limit=20
```

## Next Integration Point

Team camera/YOLO code can replace:

- `YoloDetector.java` if it already has inference logic.
- `ImageUtils.java` if it already has optimized frame conversion.
- `MainActivity.analyzeImage` if the team has a compressed-frame pipeline.

Keep the final event shape simple:

```json
{
  "label": "person",
  "confidence": 0.82,
  "direction": "front",
  "distanceLevel": "near"
}
```
