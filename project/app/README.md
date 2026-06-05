# A-eye Native App

Android native camera + YOLO demo app for the A-eye MVP.

## What It Does

- Opens the rear camera with CameraX.
- Runs a YOLOv8n TFLite COCO 80-class model on-device.
- Converts detections into Korean TTS guidance.
- Uses large yellow/black controls for low-vision and screen-reader-friendly operation.
- Lets the phone test connectivity to the A-eye FastAPI server.
- Posts obstacle detection events to the FastAPI server with GPS and heading.

## Current Demo Model

`app/src/main/assets/yolov8n_coco80_float32.tflite`

The active model is a YOLOv8n float32 TFLite model exported from a COCO-pretrained YOLOv8n checkpoint. It uses the 80 labels in `app/src/main/assets/coco.txt`.

The previous smaller person-only demo model is still kept as:

```text
app/src/main/assets/yolov8n_demo.tflite
```

`YoloDetector` supports common YOLOv8 TFLite output layouts:

- `[1, 84, 8400]`
- `[1, 8400, 84]`

If the teammate model uses a different output layout, adjust `YoloDetector.parseOutput`.

Current verified model shape:

```text
input:  [1, 640, 640, 3] float32
output: [1, 84, 8400] float32
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
