import asyncio
import json
import os
import re
import time
from datetime import datetime, timezone
from math import asin, atan2, cos, degrees, radians, sin, sqrt
from pathlib import Path

from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

try:
    import cv2
    import numpy as np
    from ultralytics import YOLO
except Exception:
    cv2 = None
    np = None
    YOLO = None


app = FastAPI(title="A-eye BlindCareNav backend API")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

BASE_DIR = Path(__file__).parent
DATA_DIR = BASE_DIR / "data"
MODEL_PATH = BASE_DIR / "yolo11n.pt"

TACTILE_FILES = {
    "hwagok": "tactile_blocks_hwagok.geojson",
}

RISK_FILES = {
    "hwagok": "risk_points_hwagok.json",
}

NEARBY_FILES = {
    "hwagok": "nearby_places_hwagok.json",
}

DIRECTION_LABELS = {
    "straight": "직진",
    "left": "왼쪽",
    "right": "오른쪽",
    "uturn": "뒤쪽",
}

RISK_LABELS = {
    "blocked_tactile_block": "점자블록을 막고 있는 장애물",
    "construction": "보도 공사 구간",
    "low_branch": "낮은 가지",
    "uneven_surface": "보도 요철",
    "crossing": "횡단보도 진입부",
}

KOREAN_OBJECT_LABELS = {
    "person": "사람",
    "car": "차량",
    "truck": "차량",
    "bus": "버스",
    "bicycle": "자전거",
    "motorcycle": "오토바이",
    "bench": "벤치",
    "chair": "의자",
    "backpack": "가방",
    "handbag": "가방",
    "suitcase": "캐리어",
    "umbrella": "우산",
    "bottle": "병",
    "cup": "컵",
}

STREAM_RISK_LABELS = set(KOREAN_OBJECT_LABELS.keys())
latest_processed_frame = None
last_tts_time = 0.0
tts_cooldown_second = 4.0
yolo_model = None


class Location(BaseModel):
    lat: float
    lng: float


class PlaceSearchRequest(BaseModel):
    area: str = "hwagok"
    keyword: str = ""
    currentLocation: Location | None = None


class GuidanceRequest(BaseModel):
    currentLocation: Location | None = None
    nextStep: dict | None = None
    risks: list[dict] = Field(default_factory=list)


class MobileRouteRequest(BaseModel):
    area: str = "hwagok"
    destinationKeyword: str = ""
    currentLocation: Location | None = None


class EmergencyRequest(BaseModel):
    area: str = "hwagok"
    deviceId: str = "demo-phone-01"
    lat: float
    lng: float
    heading: float | None = None
    message: str = ""


class DetectionItem(BaseModel):
    label: str
    confidence: float = 0.0
    direction: str = "front"
    distanceLevel: str = "unknown"


class MobileDetectionRequest(BaseModel):
    area: str = "hwagok"
    deviceId: str = "demo-phone-01"
    lat: float
    lng: float
    heading: float | None = None
    detections: list[DetectionItem] = Field(default_factory=list)
    message: str = ""


def load_area_json(area: str, file_map: dict[str, str], missing_label: str):
    file_name = file_map.get(area)

    if not file_name:
        raise HTTPException(status_code=404, detail="지원하지 않는 구역입니다.")

    file_path = DATA_DIR / file_name

    if not file_path.exists():
        raise HTTPException(status_code=404, detail=f"{missing_label} 파일이 없습니다.")

    with file_path.open("r", encoding="utf-8") as data_file:
        return json.load(data_file)


def haversine_meter(lat1: float, lng1: float, lat2: float, lng2: float) -> int:
    earth_radius_meter = 6371000
    d_lat = radians(lat2 - lat1)
    d_lng = radians(lng2 - lng1)
    rad_lat1 = radians(lat1)
    rad_lat2 = radians(lat2)

    a = (
        sin(d_lat / 2) ** 2
        + cos(rad_lat1) * cos(rad_lat2) * sin(d_lng / 2) ** 2
    )
    c = 2 * atan2(sqrt(a), sqrt(1 - a))

    return round(earth_radius_meter * c)


def add_distance(item: dict, lat: float, lng: float) -> dict:
    distance_meter = haversine_meter(lat, lng, item["lat"], item["lng"])

    return {
        **item,
        "distanceMeter": distance_meter,
        "description": f"{item.get('description', '')} 현재 위치 기준 약 {distance_meter}m",
    }


def detection_log_path(area: str) -> Path:
    if area != "hwagok":
        raise HTTPException(status_code=404, detail="지원하지 않는 구역입니다.")

    return DATA_DIR / f"mobile_detection_logs_{area}.jsonl"


def distance_level_to_meter(distance_level: str) -> int:
    return {
        "near": 3,
        "middle": 6,
        "mid": 6,
        "far": 10,
    }.get(distance_level, 0)


def estimate_marker_location(lat: float, lng: float, heading: float | None, distance_meter: int):
    if heading is None or distance_meter <= 0:
        return {"lat": lat, "lng": lng, "estimated": False}

    earth_radius_meter = 6371000
    bearing = radians(heading)
    start_lat = radians(lat)
    start_lng = radians(lng)
    angular_distance = distance_meter / earth_radius_meter

    marker_lat = asin(
        sin(start_lat) * cos(angular_distance)
        + cos(start_lat) * sin(angular_distance) * cos(bearing)
    )
    marker_lng = start_lng + atan2(
        sin(bearing) * sin(angular_distance) * cos(start_lat),
        cos(angular_distance) - sin(start_lat) * sin(marker_lat),
    )

    return {
        "lat": round(degrees(marker_lat), 7),
        "lng": round(degrees(marker_lng), 7),
        "estimated": True,
    }


def normalize_keyword(value: str) -> str:
    return value.strip().lower()


def compact_keyword(value: str) -> str:
    return "".join(normalize_keyword(value).split())


def clean_destination_keyword(value: str) -> str:
    text = normalize_keyword(value)
    removable_phrases = [
        "안내해줘",
        "안내해 줘",
        "안내 해줘",
        "안내 해 줘",
        "안내해 주세요",
        "안내해",
        "길 안내해줘",
        "길 안내해 줘",
        "길 안내",
        "경로 안내",
        "경로 찾아줘",
        "경로 찾아 줘",
        "찾아줘",
        "찾아 줘",
        "가줘",
        "가 줘",
        "가자",
    ]

    for phrase in removable_phrases:
        text = text.replace(phrase, " ")

    text = re.sub(r"\s+(으로|로)\s*$", " ", text)
    text = re.sub(r"(으로|로)\s*$", " ", text)
    return " ".join(text.split())


def place_matches_keyword(place: dict, keyword: str) -> bool:
    if not keyword:
        return True

    searchable = " ".join(
        [
            place.get("name", ""),
            place.get("type", ""),
            place.get("address", ""),
            place.get("description", ""),
            " ".join(place.get("tags", [])),
        ]
    ).lower()

    compact_searchable = compact_keyword(searchable)
    compact_value = compact_keyword(keyword)

    return keyword in searchable or bool(compact_value and compact_value in compact_searchable)


def find_place_candidates(area: str, keyword: str, current_location: Location | None = None):
    nearby_data = load_area_json(area, NEARBY_FILES, "주변시설 JSON")
    normalized_keyword = normalize_keyword(keyword)
    items = [
        item
        for item in nearby_data.get("items", [])
        if place_matches_keyword(item, normalized_keyword)
    ]

    if current_location:
        items = sorted(
            [add_distance(item, current_location.lat, current_location.lng) for item in items],
            key=lambda item: item["distanceMeter"],
        )

    return items


def build_mobile_route_response(area: str, keyword: str, current_location: Location | None):
    cleaned_keyword = clean_destination_keyword(keyword)

    if not cleaned_keyword:
        fallback_places = find_place_candidates(area, "", current_location)[:3]
        return {
            "ok": False,
            "reason": "목적지가 비어 있습니다. 다시 말씀해 주세요.",
            "alternatives": [
                {
                    "id": item["id"],
                    "name": item["name"],
                    "type": item["type"],
                    "distanceMeter": item.get("distanceMeter"),
                }
                for item in fallback_places
            ],
        }

    candidates = find_place_candidates(area, cleaned_keyword, current_location)

    if not candidates:
        fallback_places = find_place_candidates(area, "", current_location)[:3]
        return {
            "ok": False,
            "reason": "목적지 후보를 찾지 못했습니다. 다시 말씀해 주세요.",
            "alternatives": [
                {
                    "id": item["id"],
                    "name": item["name"],
                    "type": item["type"],
                    "distanceMeter": item.get("distanceMeter"),
                }
                for item in fallback_places
            ],
        }

    destination = candidates[0]
    start = current_location or Location(lat=37.54167, lng=126.84028)
    distance_meter = destination.get("distanceMeter")

    if distance_meter is None:
        distance_meter = haversine_meter(start.lat, start.lng, destination["lat"], destination["lng"])

    duration_minute = max(1, round(distance_meter / 55))
    first_distance = max(20, min(200, int(distance_meter)))
    next_direction = "right" if destination["lng"] >= start.lng else "left"
    next_label = DIRECTION_LABELS[next_direction]

    if distance_meter <= 60:
        current_instruction = f"{destination['name']} 주변입니다."
        next_instruction = "도착 지점 주변 안전을 확인하세요."
    else:
        current_instruction = f"직진 {first_distance}m"
        next_instruction = f"다음: {next_label}"

    return {
        "ok": True,
        "area": area,
        "keyword": cleaned_keyword,
        "destination": {
            "id": destination["id"],
            "name": destination["name"],
            "type": destination["type"],
            "lat": destination["lat"],
            "lng": destination["lng"],
            "address": destination.get("address", ""),
            "description": destination.get("description", ""),
        },
        "summary": {
            "distanceMeter": distance_meter,
            "durationMinute": duration_minute,
            "text": f"{destination['name']} · {distance_meter}m · 약 {duration_minute}분",
        },
        "steps": [
            {
                "instruction": current_instruction,
                "distanceMeter": first_distance,
                "direction": "straight",
            },
            {
                "instruction": next_instruction,
                "distanceMeter": max(0, distance_meter - first_distance),
                "direction": next_direction,
            },
        ],
    }


def get_yolo_model():
    global yolo_model

    if YOLO is None or cv2 is None or np is None:
        raise RuntimeError("YOLO stream dependencies are not installed.")

    if not MODEL_PATH.exists():
        raise RuntimeError(f"Model file not found: {MODEL_PATH}")

    if yolo_model is None:
        yolo_model = YOLO(str(MODEL_PATH))

    return yolo_model


def build_stream_alert(results, model) -> str | None:
    detected_classes = []

    for box in results[0].boxes:
        cls_id = int(box.cls[0])
        cls_name = model.names[cls_id]

        if cls_name in STREAM_RISK_LABELS:
            detected_classes.append(KOREAN_OBJECT_LABELS.get(cls_name, cls_name))

    if not detected_classes:
        return None

    unique_targets = list(dict.fromkeys(detected_classes))
    return f"전방에 {' 및 '.join(unique_targets)}이 감지되었습니다."


async def generate_frames():
    global latest_processed_frame

    while True:
        if latest_processed_frame is not None:
            yield (
                b"--frame\r\n"
                b"Content-Type: image/jpeg\r\n\r\n" + latest_processed_frame + b"\r\n"
            )

        await asyncio.sleep(0.05)


@app.get("/api/health")
def health_check():
    return {
        "status": "ok",
        "dataDir": str(DATA_DIR),
        "yoloReady": YOLO is not None and MODEL_PATH.exists(),
    }


@app.get("/")
def root():
    return {
        "service": "BlindCareNav backend",
        "health": "/api/health",
        "adminSummary": "/api/admin/summary?area=hwagok",
    }


@app.get("/api/tactile-blocks")
def get_tactile_blocks(area: str = "hwagok"):
    return load_area_json(area, TACTILE_FILES, "점자블록 GeoJSON")


@app.get("/api/risks")
def get_risks(area: str = "hwagok"):
    return load_area_json(area, RISK_FILES, "위험 지점 JSON")


@app.get("/api/nearby")
def get_nearby(
    area: str = "hwagok",
    lat: float = 37.54167,
    lng: float = 126.84028,
    type: str | None = None,
):
    nearby_data = load_area_json(area, NEARBY_FILES, "주변시설 JSON")
    place_type = normalize_keyword(type or "")
    items = nearby_data.get("items", [])

    if place_type and place_type != "all":
        items = [item for item in items if item.get("type") == place_type]

    sorted_items = sorted(
        [add_distance(item, lat, lng) for item in items],
        key=lambda item: item["distanceMeter"],
    )

    return {"area": area, "items": sorted_items}


@app.post("/api/guidance")
def create_guidance(payload: GuidanceRequest):
    next_step = payload.nextStep or {}
    distance_meter = next_step.get("distanceMeter")
    direction = DIRECTION_LABELS.get(next_step.get("direction"), next_step.get("direction"))
    phrases = []

    if distance_meter and direction:
        phrases.append(f"{distance_meter}미터 앞에서 {direction} 방향으로 이동하세요.")
    elif direction:
        phrases.append(f"다음 안내는 {direction} 방향입니다.")
    else:
        phrases.append("현재 위치를 확인했습니다.")

    level = "info"

    for risk in payload.risks:
        risk_type = risk.get("type", "")
        risk_distance = risk.get("distanceMeter")
        risk_label = RISK_LABELS.get(risk_type, "주의 지점")

        if risk.get("level") == "danger":
            level = "danger"
        elif level != "danger":
            level = "warning"

        if risk_distance:
            phrases.append(f"전방 {risk_distance}미터 지점에 {risk_label}이 있습니다.")
        else:
            phrases.append(f"주변에 {risk_label}이 있습니다.")

    return {"message": " ".join(phrases), "level": level}


@app.post("/api/mobile/route")
def create_mobile_route(payload: MobileRouteRequest):
    return build_mobile_route_response(
        payload.area,
        payload.destinationKeyword,
        payload.currentLocation,
    )


@app.get("/api/admin/summary")
def get_admin_summary(area: str = "hwagok"):
    tactile_data = load_area_json(area, TACTILE_FILES, "점자블록 GeoJSON")
    risk_data = load_area_json(area, RISK_FILES, "위험 지점 JSON")
    nearby_data = load_area_json(area, NEARBY_FILES, "주변시설 JSON")
    log_path = detection_log_path(area)

    tactile_features = tactile_data.get("features", [])
    risks = risk_data.get("items", [])
    places = nearby_data.get("items", [])
    open_risks = [risk for risk in risks if risk.get("status") == "open"]
    resolved_risks = [risk for risk in risks if risk.get("status") == "resolved"]
    danger_risks = [risk for risk in risks if risk.get("level") == "danger"]
    detection_log_count = 0

    if log_path.exists():
        with log_path.open("r", encoding="utf-8") as log_file:
            detection_log_count = sum(1 for line in log_file if line.strip())

    return {
        "area": area,
        "tactileBlockCount": len(tactile_features),
        "riskCount": len(risks),
        "openRiskCount": len(open_risks),
        "resolvedRiskCount": len(resolved_risks),
        "dangerRiskCount": len(danger_risks),
        "nearbyPlaceCount": len(places),
        "mobileDetectionLogCount": detection_log_count,
    }


@app.post("/api/places/search")
def search_places(payload: PlaceSearchRequest):
    items = find_place_candidates(payload.area, payload.keyword, payload.currentLocation)

    candidates = [
        {
            "id": item["id"],
            "name": item["name"],
            "type": item["type"],
            "lat": item["lat"],
            "lng": item["lng"],
            "distanceMeter": item.get("distanceMeter"),
            "address": item.get("address", ""),
            "description": item.get("description", ""),
        }
        for item in items[:8]
    ]

    return {"area": payload.area, "keyword": payload.keyword, "candidates": candidates}


@app.post("/api/mobile/emergency")
def create_mobile_emergency(payload: EmergencyRequest):
    if payload.area != "hwagok":
        raise HTTPException(status_code=404, detail="지원하지 않는 구역입니다.")

    log_path = DATA_DIR / f"mobile_emergency_logs_{payload.area}.jsonl"
    now = datetime.now(timezone.utc).isoformat()
    entry = {
        "timestamp": now,
        "area": payload.area,
        "deviceId": payload.deviceId,
        "lat": payload.lat,
        "lng": payload.lng,
        "heading": payload.heading,
        "message": payload.message or "보호자 위치 공유 요청",
        "mapUrl": f"https://maps.google.com/?q={payload.lat},{payload.lng}",
    }

    with log_path.open("a", encoding="utf-8") as log_file:
        log_file.write(json.dumps(entry, ensure_ascii=False) + "\n")

    print(
        "[mobile-emergency]",
        payload.deviceId,
        f"{payload.lat},{payload.lng}",
        flush=True,
    )

    return {
        "ok": True,
        "message": "긴급 위치 공유 이벤트를 서버에 기록했습니다.",
        "entry": entry,
    }


@app.post("/api/mobile/detections")
def create_mobile_detection(payload: MobileDetectionRequest):
    log_path = detection_log_path(payload.area)
    now = datetime.now(timezone.utc).isoformat()
    primary = payload.detections[0] if payload.detections else None
    marker_distance = distance_level_to_meter(primary.distanceLevel if primary else "unknown")
    marker = estimate_marker_location(payload.lat, payload.lng, payload.heading, marker_distance)

    log_entry = {
        "timestamp": now,
        "area": payload.area,
        "deviceId": payload.deviceId,
        "lat": payload.lat,
        "lng": payload.lng,
        "heading": payload.heading,
        "markerLat": marker["lat"],
        "markerLng": marker["lng"],
        "markerEstimated": marker["estimated"],
        "message": payload.message,
        "detections": [
            {
                "label": detection.label,
                "confidence": round(detection.confidence, 4),
                "direction": detection.direction,
                "distanceLevel": detection.distanceLevel,
            }
            for detection in payload.detections
        ],
    }

    with log_path.open("a", encoding="utf-8") as log_file:
        log_file.write(json.dumps(log_entry, ensure_ascii=False) + "\n")

    if primary:
        print(
            "[mobile-detection] "
            f"{payload.deviceId} {primary.label} {primary.confidence:.2f} "
            f"{primary.direction} {primary.distanceLevel} "
            f"{payload.lat},{payload.lng}"
        )
    else:
        print(f"[mobile-detection] {payload.deviceId} empty {payload.lat},{payload.lng}")

    return {
        "ok": True,
        "stored": True,
        "markerLat": log_entry["markerLat"],
        "markerLng": log_entry["markerLng"],
        "markerEstimated": log_entry["markerEstimated"],
    }


@app.get("/api/admin/detections")
def get_admin_detections(area: str = "hwagok", limit: int = 50):
    log_path = detection_log_path(area)

    if not log_path.exists():
        return {"area": area, "items": []}

    with log_path.open("r", encoding="utf-8") as log_file:
        rows = [json.loads(line) for line in log_file if line.strip()]

    safe_limit = max(1, min(limit, 200))
    return {"area": area, "items": list(reversed(rows[-safe_limit:]))}


@app.get("/video_feed")
async def video_feed():
    if YOLO is None or cv2 is None or np is None:
        raise HTTPException(status_code=503, detail="YOLO stream dependencies are not installed.")

    return StreamingResponse(
        generate_frames(),
        media_type="multipart/x-mixed-replace; boundary=frame",
    )


@app.websocket("/ws/stream")
async def websocket_endpoint(websocket: WebSocket):
    global latest_processed_frame, last_tts_time
    await websocket.accept()

    try:
        model = get_yolo_model()
    except Exception as error:
        await websocket.send_text(f"YOLO 서버 준비 실패: {error}")
        await websocket.close()
        return

    print("[mobile-stream] smartphone camera connected")

    try:
        while True:
            data = await websocket.receive_bytes()
            nparr = np.frombuffer(data, np.uint8)
            frame = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

            if frame is None:
                continue

            results = model(frame, verbose=False)
            annotated_frame = results[0].plot()
            current_time = time.time()

            if current_time - last_tts_time > tts_cooldown_second:
                alert_msg = build_stream_alert(results, model)

                if alert_msg:
                    await websocket.send_text(alert_msg)
                    last_tts_time = current_time

            _, buffer = cv2.imencode(".jpg", annotated_frame)
            latest_processed_frame = buffer.tobytes()
    except WebSocketDisconnect:
        print("[mobile-stream] smartphone camera disconnected")
    except Exception as error:
        print(f"[mobile-stream] processing error: {error}")


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8000)
