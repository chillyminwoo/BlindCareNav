import asyncio
import json
import os
import re
import time
import urllib.error
import urllib.request
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
DEFAULT_MODEL_PATH = BASE_DIR / "models" / "best.pt"
LEGACY_MODEL_PATH = BASE_DIR / "yolo11n.pt"
MODEL_PATH = Path(os.getenv("BLINDCARE_YOLO_MODEL", str(DEFAULT_MODEL_PATH)))

if not MODEL_PATH.exists() and LEGACY_MODEL_PATH.exists():
    MODEL_PATH = LEGACY_MODEL_PATH

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

PLACE_TYPE_SEARCH_TEXT = {
    "store": "편의점 마트 가게 매장 상점",
    "toilet": "화장실 공중화장실",
    "pharmacy": "약국 약방",
    "hospital": "병원 의원 의료기관",
    "subway": "지하철 전철 역 출구 대중교통",
    "cafe": "카페 커피",
    "public_office": "주민센터 공공기관 관공서 구청 동사무소",
    "restaurant": "식당 음식점 밥집",
    "rest_area": "쉼터 휴식 공원",
}

KOREAN_OBJECT_LABELS = {
    "person": "사람",
    "braille_block": "점자블록",
    "car": "차량",
    "truck": "차량",
    "bus": "버스",
    "bicycle": "자전거",
    "motorcycle": "오토바이",
    "kickboard": "킥보드",
    "green_light": "초록 신호",
    "red_light": "빨간 신호",
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
STREAM_SERVER_YOLO = os.getenv("BLINDCARE_STREAM_SERVER_YOLO", "").lower() in {"1", "true", "yes"}
latest_control_destination: dict = {}
latest_control_route: dict = {}
latest_stream_status: dict = {
    "area": "hwagok",
    "deviceId": "demo-phone-01",
    "requested": False,
    "active": False,
    "requestedBy": "",
    "updatedAt": None,
    "message": "",
}
latest_mobile_locations: dict[str, dict] = {}
mobile_command_queues: dict[str, list[dict]] = {}
cached_lmstudio_model: str | None = None
lmstudio_disabled_until = 0.0


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


class AssistantCommandRequest(BaseModel):
    area: str = "hwagok"
    deviceId: str = "demo-phone-01"
    utterance: str = ""
    currentLocation: Location | None = None
    routeState: dict | None = None


class EmergencyRequest(BaseModel):
    area: str = "hwagok"
    deviceId: str = "demo-phone-01"
    lat: float
    lng: float
    heading: float | None = None
    message: str = ""


class SupportRequest(BaseModel):
    area: str = "hwagok"
    deviceId: str = "demo-phone-01"
    lat: float
    lng: float
    heading: float | None = None
    message: str = ""
    mode: str = "general"
    sceneDescription: str = ""
    detections: list[dict] = Field(default_factory=list)


class StreamControlRequest(BaseModel):
    area: str = "hwagok"
    deviceId: str = "demo-phone-01"
    requestedBy: str = "control-web"
    message: str = ""


class DetectionItem(BaseModel):
    label: str
    confidence: float = 0.0
    direction: str = "front"
    distanceLevel: str = "unknown"
    isStaticObstacle: bool = False

class TactileHazardRequest(BaseModel):
    area: str = "hwagok"
    deviceId: str = "demo-phone-01"
    lat: float
    lng: float
    heading: float | None = None
    detections: list[DetectionItem] = Field(default_factory=list)
    message: str = ""


class MobileDetectionRequest(BaseModel):
    area: str = "hwagok"
    deviceId: str = "demo-phone-01"
    lat: float
    lng: float
    heading: float | None = None
    detections: list[DetectionItem] = Field(default_factory=list)
    message: str = ""

class MobileLocationRequest(BaseModel):
    area: str = "hwagok"
    deviceId: str = "demo-phone-01"
    lat: float
    lng: float
    heading: float | None = None


class ControlDestinationRequest(BaseModel):
    area: str = "hwagok"
    destination: str = ""
    lat: float | None = None
    lng: float | None = None
    source: str = "app"
    mode: str = "navigation"


class RouteCandidateSyncRequest(BaseModel):
    area: str = "hwagok"
    destination: dict = Field(default_factory=dict)
    start: dict = Field(default_factory=dict)
    candidates: list[dict] = Field(default_factory=list)


class SceneGuidanceRequest(BaseModel):
    area: str = "hwagok"
    deviceId: str = "demo-phone-01"
    mode: str = "general"
    lat: float | None = None
    lng: float | None = None
    detections: list[DetectionItem] = Field(default_factory=list)
    routeState: dict | None = None


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


def route_point(lat: float, lng: float) -> dict:
    return {
        "lat": round(float(lat), 7),
        "lng": round(float(lng), 7),
    }


def direction_from_delta(start_lng: float, end_lng: float) -> str:
    return "right" if end_lng >= start_lng else "left"


def densify_route_points(points: list[dict], max_segment_meter: int = 35) -> list[dict]:
    if len(points) <= 1:
        return points

    geometry: list[dict] = []

    for index in range(len(points) - 1):
        start = points[index]
        end = points[index + 1]

        if not geometry:
            geometry.append(start)

        segment_meter = haversine_meter(start["lat"], start["lng"], end["lat"], end["lng"])
        split_count = max(1, segment_meter // max_segment_meter)

        for split_index in range(1, split_count + 1):
            ratio = split_index / split_count
            geometry.append(
                route_point(
                    start["lat"] + (end["lat"] - start["lat"]) * ratio,
                    start["lng"] + (end["lng"] - start["lng"]) * ratio,
                )
            )

    return geometry


def find_geometry_index(geometry: list[dict], point: dict) -> int:
    for index, item in enumerate(geometry):
        if item["lat"] == point["lat"] and item["lng"] == point["lng"]:
            return index

    return max(0, len(geometry) - 1)


def build_demo_route_geometry_and_steps(start: Location, destination: dict, distance_meter: int):
    start_point = route_point(start.lat, start.lng)
    destination_point = route_point(destination["lat"], destination["lng"])

    if distance_meter <= 60:
        geometry = densify_route_points([start_point, destination_point])
        end_index = max(0, len(geometry) - 1)
        return geometry, [
            {
                "id": "step-1",
                "type": "arrive",
                "instruction": f"{destination['name']} 주변입니다. 주변 안전을 확인하세요.",
                "distanceMeter": distance_meter,
                "direction": "straight",
                "startIndex": 0,
                "endIndex": end_index,
            }
        ]

    turn_point = route_point(start.lat, destination["lng"])
    first_leg_meter = haversine_meter(start_point["lat"], start_point["lng"], turn_point["lat"], turn_point["lng"])
    second_leg_meter = haversine_meter(turn_point["lat"], turn_point["lng"], destination_point["lat"], destination_point["lng"])

    if first_leg_meter < 20 or second_leg_meter < 20:
        geometry = densify_route_points([start_point, destination_point])
        end_index = max(0, len(geometry) - 1)
        return geometry, [
            {
                "id": "step-1",
                "type": "straight",
                "instruction": f"{destination['name']} 방향으로 이동하세요.",
                "distanceMeter": distance_meter,
                "direction": "straight",
                "startIndex": 0,
                "endIndex": end_index,
            }
        ]

    geometry = densify_route_points([start_point, turn_point, destination_point])
    turn_index = find_geometry_index(geometry, turn_point)
    end_index = max(0, len(geometry) - 1)
    turn_direction = direction_from_delta(start.lng, destination["lng"])
    turn_label = DIRECTION_LABELS[turn_direction]

    return geometry, [
        {
            "id": "step-1",
            "type": "straight",
            "instruction": f"{first_leg_meter}미터 직진하세요.",
            "distanceMeter": first_leg_meter,
            "direction": "straight",
            "startIndex": 0,
            "endIndex": turn_index,
        },
        {
            "id": "step-2",
            "type": f"turn_{turn_direction}",
            "instruction": f"{turn_label}으로 방향을 바꾸세요.",
            "distanceMeter": second_leg_meter,
            "direction": turn_direction,
            "startIndex": turn_index,
            "endIndex": end_index,
        },
        {
            "id": "step-3",
            "type": "arrive",
            "instruction": f"{destination['name']} 주변입니다. 주변 안전을 확인하세요.",
            "distanceMeter": 0,
            "direction": "straight",
            "startIndex": end_index,
            "endIndex": end_index,
        },
    ]


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

def tactile_hazard_log_path(area: str) -> Path:
    if area != "hwagok":
        raise HTTPException(status_code=404, detail="지원하지 않는 구역입니다.")

    return DATA_DIR / f"mobile_tactile_hazard_logs_{area}.jsonl"


def emergency_log_path(area: str) -> Path:
    if area != "hwagok":
        raise HTTPException(status_code=404, detail="지원하지 않는 구역입니다.")

    return DATA_DIR / f"mobile_emergency_logs_{area}.jsonl"


def support_log_path(area: str) -> Path:
    if area != "hwagok":
        raise HTTPException(status_code=404, detail="Unsupported area")

    return DATA_DIR / f"support_requests_{area}.jsonl"


def admin_log_path(area: str, log_type: str) -> Path:
    normalized_type = normalize_keyword(log_type)

    if normalized_type in {"detections", "detection", "mobile_detections"}:
        return detection_log_path(area)

    if normalized_type in {
        "tactile-hazards",
        "tactile_hazards",
        "tactile-hazard",
        "tactile_hazard",
        "hazards",
        "hazard",
    }:
        return tactile_hazard_log_path(area)

    if normalized_type in {"emergency", "emergencies", "mobile_emergency"}:
        return emergency_log_path(area)

    if normalized_type in {"support", "supports", "help", "support_requests"}:
        return support_log_path(area)

    raise HTTPException(status_code=400, detail="지원하지 않는 로그 타입입니다.")


def read_jsonl_tail(path: Path, limit: int = 50) -> list[dict]:
    if not path.exists():
        return []

    safe_limit = max(1, min(limit, 500))

    with path.open("r", encoding="utf-8") as log_file:
        lines = [line.strip() for line in log_file if line.strip()]

    entries = []

    for line in lines[-safe_limit:]:
        try:
            entries.append(json.loads(line))
        except json.JSONDecodeError:
            entries.append({"raw": line, "parseError": True})

    return entries


def count_jsonl(path: Path) -> int:
    if not path.exists():
        return 0

    with path.open("r", encoding="utf-8") as log_file:
        return sum(1 for line in log_file if line.strip())


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def write_jsonl(path: Path, entry: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as log_file:
        log_file.write(json.dumps(entry, ensure_ascii=False) + "\n")


def read_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        return []

    entries = []
    with path.open("r", encoding="utf-8") as log_file:
        for line in log_file:
            line = line.strip()
            if not line:
                continue
            try:
                entries.append(json.loads(line))
            except json.JSONDecodeError:
                entries.append({"raw": line, "parseError": True})
    return entries


def replace_jsonl(path: Path, entries: list[dict]):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as log_file:
        for entry in entries:
            log_file.write(json.dumps(entry, ensure_ascii=False) + "\n")


def enqueue_mobile_command(device_id: str, command_type: str, payload: dict | None = None) -> dict:
    command = {
        "id": f"{command_type}-{int(time.time() * 1000)}",
        "type": command_type,
        "payload": payload or {},
        "createdAt": utc_now(),
    }
    mobile_command_queues.setdefault(device_id, []).append(command)
    return command


def current_stream_snapshot() -> dict:
    return {**latest_stream_status, "pendingCommandCount": sum(len(items) for items in mobile_command_queues.values())}


def write_support_event(payload: SupportRequest, request_type: str) -> dict:
    if payload.area != "hwagok":
        raise HTTPException(status_code=404, detail="Unsupported area")

    normalized_type = "emergency" if request_type == "emergency" else "help"
    now = utc_now()
    entry = {
        "id": f"{normalized_type}-{int(time.time() * 1000)}",
        "timestamp": now,
        "updatedAt": now,
        "area": payload.area,
        "type": normalized_type,
        "status": "open",
        "priority": "critical" if normalized_type == "emergency" else "assist",
        "deviceId": payload.deviceId,
        "lat": payload.lat,
        "lng": payload.lng,
        "heading": payload.heading,
        "mode": payload.mode,
        "message": payload.message or ("Emergency request" if normalized_type == "emergency" else "Help request"),
        "sceneDescription": payload.sceneDescription,
        "detections": payload.detections[:8],
        "mapUrl": f"https://maps.google.com/?q={payload.lat},{payload.lng}",
    }
    write_jsonl(support_log_path(payload.area), entry)
    print(
        f"[support-{normalized_type}] {payload.deviceId} {payload.lat},{payload.lng} {entry['message']}",
        flush=True,
    )
    return entry


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
    return "".join(normalize_spoken_numbers(normalize_keyword(value)).split())


def normalize_spoken_numbers(value: str) -> str:
    text = value
    number_words = [
        ("열", "10"),
        ("아홉", "9"),
        ("구", "9"),
        ("여덟", "8"),
        ("팔", "8"),
        ("일곱", "7"),
        ("칠", "7"),
        ("여섯", "6"),
        ("육", "6"),
        ("다섯", "5"),
        ("오", "5"),
        ("넷", "4"),
        ("네", "4"),
        ("사", "4"),
        ("셋", "3"),
        ("세", "3"),
        ("삼", "3"),
        ("둘", "2"),
        ("두", "2"),
        ("이", "2"),
        ("하나", "1"),
        ("한", "1"),
        ("일", "1"),
    ]

    text = re.sub(r"(\d+)\s*번", r"\1번", text)
    text = re.sub(r"(\d+)\s*출구", r"\1번 출구", text)

    for word, digit in number_words:
        text = re.sub(fr"{word}\s*(번째|번)", f"{digit}번", text)

    return text


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
    return " ".join(normalize_spoken_numbers(text).split())


def strip_wake_word(value: str) -> str:
    text = value or ""
    wake_words = ["누니야", "누니", "눈이야", "눈이", "nuni"]

    for wake_word in wake_words:
        text = re.sub(wake_word, " ", text, flags=re.IGNORECASE)

    return " ".join(text.split())


def assistant_compact(value: str) -> str:
    return compact_keyword(strip_wake_word(value))


def assistant_contains_any(text: str, *keywords: str) -> bool:
    compact_text = assistant_compact(text)
    return any(compact_keyword(keyword) in compact_text for keyword in keywords)


def parse_assistant_place_type(text: str) -> str:
    if assistant_contains_any(text, "편의점", "마트", "가게", "상점", "매장"):
        return "store"
    if assistant_contains_any(text, "화장실", "공중화장실", "화장"):
        return "toilet"
    if assistant_contains_any(text, "약국", "약방", "약"):
        return "pharmacy"
    if assistant_contains_any(text, "병원", "의원", "의료"):
        return "hospital"
    if assistant_contains_any(text, "지하철", "역", "출구", "전철"):
        return "subway"
    if assistant_contains_any(text, "카페", "커피"):
        return "cafe"
    if assistant_contains_any(text, "주민센터", "관공서", "구청", "동사무소"):
        return "public_office"
    if assistant_contains_any(text, "식당", "음식점", "밥집"):
        return "restaurant"
    return ""


def looks_like_destination_command(text: str) -> bool:
    return assistant_contains_any(
        text,
        "안내",
        "길 안내",
        "경로",
        "찾아",
        "가줘",
        "가자",
        "데려다",
        "목적지",
        "출구",
        "정문",
        "후문",
    )


def summarize_nearby_places(area: str, current_location: Location | None, place_type: str = ""):
    location = current_location or Location(lat=37.54167, lng=126.84028)
    nearby_data = load_area_json(area, NEARBY_FILES, "주변시설 JSON")
    items = nearby_data.get("items", [])

    if place_type:
        items = [item for item in items if item.get("type") == place_type]

    sorted_items = sorted(
        [add_distance(item, location.lat, location.lng) for item in items],
        key=lambda item: item["distanceMeter"],
    )
    top_items = sorted_items[:3]

    if not top_items:
        return "현재 위치 주변에서 해당 시설을 찾지 못했습니다.", []

    phrases = []

    for index, item in enumerate(top_items, start=1):
        phrases.append(f"{index}번 {item['name']}, 약 {item['distanceMeter']}미터")

    return "가까운 주변시설입니다. " + ". ".join(phrases), top_items


def summarize_open_risks(area: str, current_location: Location | None):
    risk_data = load_area_json(area, RISK_FILES, "위험 지점 JSON")
    open_risks = [risk for risk in risk_data.get("items", []) if risk.get("status") == "open"]

    if current_location:
        open_risks = [
            add_distance(risk, current_location.lat, current_location.lng)
            for risk in open_risks
        ]
        open_risks = sorted(open_risks, key=lambda risk: risk["distanceMeter"])

    top_risks = open_risks[:3]

    if not top_risks:
        return "현재 열린 위험 지점은 없습니다.", []

    phrases = []

    for index, risk in enumerate(top_risks, start=1):
        distance = risk.get("distanceMeter")
        distance_text = f", 약 {distance}미터" if distance is not None else ""
        phrases.append(f"{index}번 {risk.get('title', '위험 지점')}{distance_text}, {risk.get('level', '주의')}")

    return "현재 확인된 위험 정보입니다. " + ". ".join(phrases), top_risks


def summarize_latest_detections(area: str):
    entries = read_jsonl_tail(detection_log_path(area), 1)

    if not entries:
        return "최근 감지된 장애물 기록은 없습니다.", []

    latest = entries[-1]
    detections = latest.get("detections", [])

    if not detections:
        return "최근 감지 기록은 있지만 장애물 목록은 비어 있습니다.", []

    primary = detections[0]
    label = KOREAN_OBJECT_LABELS.get(primary.get("label"), primary.get("label", "장애물"))
    direction = primary.get("direction", "front")
    distance_level = primary.get("distanceLevel", "unknown")
    confidence = round(float(primary.get("confidence", 0)) * 100)
    message = f"최근 감지된 장애물은 {label}입니다. 방향 {direction}, 거리 {distance_level}, 신뢰도 {confidence}퍼센트입니다."
    return message, entries


def assistant_llm_enabled() -> bool:
    value = os.getenv("BLINDCARE_ASSISTANT_USE_LLM", "1").lower()
    return value not in {"0", "false", "no", "off"}


def lmstudio_base_url() -> str:
    raw = (
        os.getenv("BLINDCARE_OPENAI_BASE_URL")
        or os.getenv("BLINDCARE_LMSTUDIO_BASE_URL")
        or "http://100.109.237.31:1234"
    ).strip()

    if not raw:
        return ""

    raw = raw.rstrip("/")
    if not raw.endswith("/v1"):
        raw = raw + "/v1"
    return raw


def lmstudio_headers() -> dict[str, str]:
    headers = {"Content-Type": "application/json"}
    api_key = os.getenv("BLINDCARE_OPENAI_API_KEY", "").strip()

    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"

    return headers


def get_lmstudio_model() -> str:
    global cached_lmstudio_model

    explicit_model = os.getenv("BLINDCARE_OPENAI_MODEL") or os.getenv("BLINDCARE_LMSTUDIO_MODEL")
    if explicit_model:
        return explicit_model.strip()

    if cached_lmstudio_model:
        return cached_lmstudio_model

    base_url = lmstudio_base_url()
    if not base_url:
        return "local-model"

    request = urllib.request.Request(
        f"{base_url}/models",
        headers=lmstudio_headers(),
        method="GET",
    )

    with urllib.request.urlopen(request, timeout=1.8) as response:
        data = json.loads(response.read().decode("utf-8"))

    models = data.get("data", [])
    if models:
        cached_lmstudio_model = str(models[0].get("id") or "local-model")
        return cached_lmstudio_model

    return "local-model"


def call_lmstudio_chat(messages: list[dict], timeout: float = 8.0, temperature: float = 0.2) -> str:
    global lmstudio_disabled_until

    if not assistant_llm_enabled():
        return ""

    if time.time() < lmstudio_disabled_until:
        return ""

    base_url = lmstudio_base_url()
    if not base_url:
        return ""

    try:
        model = get_lmstudio_model()
        body = json.dumps(
            {
                "model": model,
                "messages": messages,
                "temperature": temperature,
                "max_tokens": 360,
                "stream": False,
            },
            ensure_ascii=False,
        ).encode("utf-8")
        request = urllib.request.Request(
            f"{base_url}/chat/completions",
            data=body,
            headers=lmstudio_headers(),
            method="POST",
        )

        with urllib.request.urlopen(request, timeout=timeout) as response:
            data = json.loads(response.read().decode("utf-8"))

        return str(data.get("choices", [{}])[0].get("message", {}).get("content", "")).strip()
    except (OSError, urllib.error.URLError, json.JSONDecodeError, TimeoutError, KeyError, IndexError):
        lmstudio_disabled_until = time.time() + 20.0
        return ""


def extract_json_object(text: str) -> dict:
    if not text:
        return {}

    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass

    match = re.search(r"\{.*\}", text, flags=re.DOTALL)
    if not match:
        return {}

    try:
        return json.loads(match.group(0))
    except json.JSONDecodeError:
        return {}


def latest_detection_context(area: str) -> list[dict]:
    return read_jsonl_tail(detection_log_path(area), 3)


def build_llm_command_hint(payload: AssistantCommandRequest, command_text: str) -> dict:
    if not assistant_llm_enabled() or not command_text.strip():
        return {}

    nearby_data = load_area_json(payload.area, NEARBY_FILES, "주변시설 JSON")
    place_names = [
        {
            "name": item.get("name"),
            "type": item.get("type"),
            "aliases": item.get("tags", []),
        }
        for item in nearby_data.get("items", [])[:30]
    ]
    context = {
        "area": payload.area,
        "currentLocation": payload.currentLocation.dict() if payload.currentLocation else None,
        "places": place_names,
        "latestDetections": latest_detection_context(payload.area),
        "routeState": payload.routeState or {},
    }
    messages = [
        {
            "role": "system",
            "content": (
                "너는 시각장애인 보행 내비게이션 앱 '누니'의 한국어 음성 명령 해석기다. "
                "반드시 JSON 객체 하나만 출력한다. 설명, 마크다운, 코드블록은 출력하지 않는다. "
                "항상 intent, action, destinationKeyword, placeType, guidanceMode, tts 6개 키를 모두 포함한다. "
                "값이 없으면 빈 문자열 \"\"로 둔다. "

                "목적지로 가고 싶다는 말이면 intent=\"navigate\", action=\"start_navigation\"으로 둔다. "
                "목적지 안내일 때 destinationKeyword에는 장소명만 넣는다. "
                "예: '화곡 연세 의원 으로 안내 해줘'는 destinationKeyword=\"화곡연세의원\"이다. "

                "주변 시설을 묻는 말이면 intent=\"nearby\", action=\"speak\"로 둔다. "
                "주변 시설 종류가 있으면 placeType에 넣는다. "
                "화장실=toilet, 편의점/마트/가게=store, 약국=pharmacy, 병원/의원=hospital, "
                "지하철/역/출구=subway, 카페=cafe, 식당/밥집=restaurant, "
                "주민센터/구청/동사무소=public_office. "

                "'긴급' 한 단어만 있어도 emergency로 분류한다. "
                "긴급, 긴급상황, 도와줘, 살려줘, 보호자, 119, SOS, 다쳤어는 "
                "intent=\"emergency\", action=\"emergency\", tts=\"긴급 요청을 전달하겠습니다.\"로 둔다. "

                "안내 종료, 그만, 멈춰는 intent=\"stop_navigation\", action=\"stop_navigation\"으로 둔다. "
                "다시 말해줘, 반복, 한번 더는 intent=\"repeat_guidance\", action=\"repeat_guidance\"로 둔다. "
                "알 수 없으면 intent=\"unknown\", action=\"speak\", tts=\"다시 말씀해 주세요.\"로 둔다."
            ),
        },
        {
            "role": "user",
            "content": json.dumps(
                {
                    "utterance": command_text,
                    "context": context,
                    "outputSchema": {
                        "intent": "string",
                        "action": "string",
                        "destinationKeyword": "string",
                        "placeType": "string",
                        "guidanceMode": "string",
                        "tts": "string",
                    },
                },
                ensure_ascii=False,
            ),
        },
    ]
    raw = call_lmstudio_chat(messages, timeout=8.0, temperature=0.0)
    print("[llm-raw]", raw, flush=True)
    return extract_json_object(raw)


def maybe_polish_assistant_message(intent: str, message: str, context: dict) -> str:
    return message


def tokenize_search_text(value: str) -> list[str]:
    text = normalize_spoken_numbers(normalize_keyword(value))
    tokens = re.findall(r"\d+번|\d+|[가-힣a-zA-Z]+", text)
    return [
        token
        for token in tokens
        if len(token) >= 2 or token.endswith("번") or token.isdigit()
    ]


def place_searchable_text(place: dict) -> str:
    place_type = place.get("type", "")
    return " ".join(
        [
            place.get("name", ""),
            place_type,
            PLACE_TYPE_SEARCH_TEXT.get(place_type, ""),
            place.get("address", ""),
            place.get("description", ""),
            " ".join(place.get("tags", [])),
        ]
    ).lower()


def place_match_score(place: dict, keyword: str) -> int:
    if not keyword:
        return 1

    searchable = place_searchable_text(place)
    compact_searchable = compact_keyword(searchable)
    compact_value = compact_keyword(keyword)

    if keyword in searchable:
        return 120 + len(keyword)

    if compact_value and compact_value in compact_searchable:
        return 100 + len(compact_value)

    tokens = tokenize_search_text(keyword)

    if not tokens:
        return 0

    matched_tokens = [
        token
        for token in tokens
        if compact_keyword(token) in compact_searchable
    ]

    if len(matched_tokens) == len(tokens):
        return 80 + len(matched_tokens)

    if len(tokens) >= 3 and len(matched_tokens) >= len(tokens) - 1:
        return 45 + len(matched_tokens)

    return 0


def place_matches_keyword(place: dict, keyword: str) -> bool:
    return place_match_score(place, keyword) > 0


def find_place_candidates(area: str, keyword: str, current_location: Location | None = None):
    nearby_data = load_area_json(area, NEARBY_FILES, "주변시설 JSON")
    normalized_keyword = normalize_keyword(keyword)
    items = []

    for item in nearby_data.get("items", []):
        score = place_match_score(item, normalized_keyword)

        if score > 0:
            scored_item = dict(item)
            scored_item["_matchScore"] = score
            items.append(scored_item)

    if current_location:
        items = [add_distance(item, current_location.lat, current_location.lng) for item in items]

    items = sorted(
        items,
        key=lambda item: (
            -item.get("_matchScore", 0),
            item.get("distanceMeter", 0),
        ),
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
    geometry, steps = build_demo_route_geometry_and_steps(start, destination, int(distance_meter))

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
        "geometry": geometry,
        "steps": steps,
    }


def set_control_destination(
    area: str,
    destination: str,
    lat: float | None = None,
    lng: float | None = None,
    source: str = "app",
    mode: str = "navigation",
):
    global latest_control_destination

    destination_name = (destination or "").strip()
    resolved_lat = lat
    resolved_lng = lng
    place_id = ""
    place_type = ""

    if destination_name and (resolved_lat is None or resolved_lng is None):
        candidates = find_place_candidates(area, destination_name, None)
        if candidates:
            best = candidates[0]
            resolved_lat = best.get("lat")
            resolved_lng = best.get("lng")
            destination_name = best.get("name", destination_name)
            place_id = best.get("id", "")
            place_type = best.get("type", "")

    latest_control_destination = {
        "ok": True,
        "area": area,
        "destination": destination_name,
        "lat": resolved_lat,
        "lng": resolved_lng,
        "source": source,
        "mode": mode,
        "placeId": place_id,
        "placeType": place_type,
        "updatedAt": datetime.now(timezone.utc).isoformat(),
    }
    return latest_control_destination


def set_control_destination_from_route(area: str, route: dict, source: str = "app"):
    if not route.get("ok"):
        return

    destination = route.get("destination", {})
    set_control_destination(
        area,
        destination.get("name", route.get("keyword", "")),
        destination.get("lat"),
        destination.get("lng"),
        source=source,
    )


def build_assistant_command_response(payload: AssistantCommandRequest):
    raw_utterance = payload.utterance or ""
    command_text = strip_wake_word(raw_utterance)
    compact_text = assistant_compact(command_text)

    def response(intent: str, action: str, message: str, **extra):
        context = extra.pop("context", {})
        final_message = maybe_polish_assistant_message(intent, message, context)
        return {
            "ok": True,
            "area": payload.area,
            "deviceId": payload.deviceId,
            "utterance": raw_utterance,
            "commandText": command_text,
            "intent": intent,
            "action": action,
            "message": final_message,
            "tts": final_message,
            "context": context,
            **extra,
        }

    def navigation_response(destination_keyword: str, intent: str = "navigate"):
        route = build_mobile_route_response(payload.area, destination_keyword, payload.currentLocation)

        if not route.get("ok"):
            alternatives = route.get("alternatives", [])
            alt_text = ""

            if alternatives:
                names = ", ".join(item.get("name", "") for item in alternatives[:3])
                alt_text = f" 가까운 후보는 {names}입니다."

            return response(
                intent,
                "speak",
                route.get("reason", "목적지 후보를 찾지 못했습니다.") + alt_text,
                destinationKeyword=destination_keyword,
                route=route,
                context={"alternatives": alternatives},
            )

        set_control_destination_from_route(payload.area, route, source=payload.deviceId)
        destination = route.get("destination", {})
        summary = route.get("summary", {})
        message = (
            f"{destination.get('name', destination_keyword)} 안내를 시작합니다. "
            f"거리 {summary.get('distanceMeter', 0)}미터, 약 {summary.get('durationMinute', 1)}분입니다."
        )
        return response(
            intent,
            "start_navigation",
            message,
            destinationKeyword=destination_keyword,
            route=route,
            context={
                "destination": destination,
                "summary": summary,
                "steps": route.get("steps", []),
            },
        )

    if not compact_text:
        return response(
            "listen",
            "listen",
            "누니가 듣고 있습니다. 목적지 안내, 주변시설, 위험 정보, 긴급 연락처럼 말씀해 주세요.",
        )

    llm_hint = build_llm_command_hint(payload, command_text)
    llm_intent = str(llm_hint.get("intent", "")).strip()
    llm_action = str(llm_hint.get("action", "")).strip()

    if llm_intent in {"navigate", "safer_reroute"} or llm_action == "start_navigation":
        destination_keyword = str(llm_hint.get("destinationKeyword", "")).strip() or clean_destination_keyword(command_text)
        return navigation_response(destination_keyword, llm_intent or "navigate")

    if llm_action == "set_guidance_mode":
        guidance_mode = str(llm_hint.get("guidanceMode", "")).strip()
        if guidance_mode == "tactile":
            return response("tactile_mode", "set_guidance_mode", "점자 안내 모드로 전환하겠습니다.", guidanceMode="tactile")
        if guidance_mode == "general":
            return response("general_mode", "set_guidance_mode", "일반 안내 모드로 전환하겠습니다.", guidanceMode="general")

    if llm_action in {"start_streaming", "stop_streaming", "help", "emergency", "stop_navigation", "repeat_guidance"}:
        message = str(llm_hint.get("tts", "")).strip()
        default_messages = {
            "start_streaming": "카메라 스트리밍은 관제에서 요청하면 자동으로 시작됩니다.",
            "stop_streaming": "카메라 스트리밍은 관제에서 종료할 수 있습니다.",
            "help": "도움 요청을 관제에 전달하겠습니다.",
            "emergency": "긴급 위치를 서버에 기록하고 보호자 전화 화면을 열겠습니다.",
            "stop_navigation": "안내를 종료하겠습니다.",
            "repeat_guidance": "현재 안내를 다시 읽겠습니다.",
        }
        action = "speak" if llm_action in {"start_streaming", "stop_streaming"} else llm_action
        return response(llm_intent or llm_action, action, message or default_messages[llm_action])

    if llm_intent == "nearby":
        place_type = str(llm_hint.get("placeType", "")).strip() or parse_assistant_place_type(command_text)
        message, places = summarize_nearby_places(payload.area, payload.currentLocation, place_type)
        return response("nearby", "speak", message, placeType=place_type, context={"places": places})

    if llm_intent == "risk_info":
        message, risks = summarize_open_risks(payload.area, payload.currentLocation)
        return response("risk_info", "speak", message, context={"risks": risks})

    if llm_intent == "detection_info":
        message, detections = summarize_latest_detections(payload.area)
        return response("detection_info", "speak", message, context={"detections": detections})

    if llm_intent == "current_location":
        location = payload.currentLocation
        if location:
            message = f"현재 위치는 위도 {location.lat:.5f}, 경도 {location.lng:.5f}입니다."
        else:
            message = "현재 위치 정보가 아직 없습니다."
        return response("current_location", "speak", message)

    if assistant_contains_any(command_text, "도움", "도와줘", "도와주세요", "막혔어", "길막힘"):
        return response(
            "help",
            "help",
            "도움 요청을 관제에 전달하겠습니다. 현재 위치와 최근 감지 정보를 함께 보냅니다.",
        )

    if assistant_contains_any(command_text, "긴급", "보호자", "살려줘", "다쳤어", "119", "sos"):
        return response(
            "emergency",
            "emergency",
            "긴급 위치를 서버에 기록하고 보호자 전화 화면을 열겠습니다.",
        )

    if assistant_contains_any(
        command_text,
        "스트리밍 중지",
        "스트리밍 꺼",
        "스트리밍 모드 꺼",
        "카메라 중지",
        "영상 중지",
        "화면 전송 중지",
    ):
        return response("stop_streaming", "speak", "카메라 스트리밍은 관제에서 종료할 수 있습니다.")

    if assistant_contains_any(
        command_text,
        "스트리밍 시작",
        "스트리밍 켜",
        "스트리밍 모드 켜",
        "카메라 스트리밍",
        "영상 전송",
        "화면 전송",
        "관제 전송",
    ):
        return response("start_streaming", "speak", "카메라 스트리밍은 관제에서 스트림 보기를 누르면 자동으로 시작됩니다.")

    if assistant_contains_any(command_text, "일반 안내 모드", "일반 모드", "일반 안내"):
        return response("general_mode", "set_guidance_mode", "일반 안내 모드로 전환하겠습니다.", guidanceMode="general")

    if assistant_contains_any(command_text, "점자 안내 모드", "점자 모드", "점자 안내"):
        return response("tactile_mode", "set_guidance_mode", "점자 안내 모드로 전환하겠습니다.", guidanceMode="tactile")

    if assistant_contains_any(command_text, "안전한 길", "안전 경로", "우회", "재탐색"):
        destination_keyword = latest_control_destination.get("destination", "")
        if destination_keyword:
            return navigation_response(destination_keyword, "safer_reroute")
        return response("safer_reroute", "speak", "현재 목적지가 없어 안전 경로를 다시 계산할 수 없습니다. 목적지를 먼저 말씀해 주세요.")

    if assistant_contains_any(
        command_text,
        "안내해줘",
        "안내해 줘",
        "안내해 주세요",
        "길 안내",
        "경로 안내",
        "찾아줘",
        "찾아 줘",
        "가자",
        "가줘",
        "가 줘",
    ):
        destination_keyword = clean_destination_keyword(command_text)
        if destination_keyword:
            return navigation_response(destination_keyword, "navigate")
        return response("navigate", "listen", "목적지를 다시 말씀해 주세요.")

    if assistant_contains_any(command_text, "다시", "반복", "한번 더", "재생", "다시 읽어"):
        return response("repeat_guidance", "repeat_guidance", "현재 안내를 다시 읽겠습니다.")

    if assistant_contains_any(command_text, "다음", "미리", "다음 안내", "다음 길"):
        return response("next_guidance", "next_guidance", "다음 안내를 읽겠습니다.")

    if assistant_contains_any(command_text, "안내 종료", "길 안내 종료", "그만", "멈춰", "중지", "네비 종료", "내비 종료"):
        return response("stop_navigation", "stop_navigation", "안내를 종료하겠습니다.")

    if assistant_contains_any(command_text, "현재 위치", "내 위치", "여기 어디", "어디야", "위치 알려", "위치 말해"):
        location = payload.currentLocation
        if location:
            message = f"현재 위치는 위도 {location.lat:.5f}, 경도 {location.lng:.5f}입니다."
        else:
            message = "현재 위치 정보가 아직 없습니다."
        return response("current_location", "speak", message)

    if assistant_contains_any(command_text, "설정", "서버 주소", "보호자 번호", "tts"):
        return response("open_settings", "open_settings", "설정 화면을 열겠습니다.")

    if assistant_contains_any(command_text, "즐겨찾기 저장", "현재 목적지 저장", "목적지 저장", "저장해줘"):
        return response("save_favorite", "save_favorite", "현재 목적지를 즐겨찾기에 저장하겠습니다.")

    if assistant_contains_any(command_text, "최근 목적지", "최근 경로", "최근 장소", "방금 목적지"):
        return response("recent_destination", "recent_destination", "최근 목적지 안내를 시작하겠습니다.")

    if assistant_contains_any(command_text, "즐겨찾기", "저장한 곳", "저장 장소"):
        return response("favorite", "favorite", "즐겨찾기 목적지 안내를 시작하겠습니다.")

    if assistant_contains_any(command_text, "위험", "위험 정보", "위험 지점", "장애물 정보"):
        message, risks = summarize_open_risks(payload.area, payload.currentLocation)
        return response(
            "risk_info",
            "speak",
            message,
            context={"risks": risks},
        )

    if assistant_contains_any(command_text, "장애물", "감지된 것", "뭐 있어", "앞에 뭐"):
        message, detections = summarize_latest_detections(payload.area)
        return response(
            "detection_info",
            "speak",
            message,
            context={"detections": detections},
        )

    if assistant_contains_any(command_text, "주변", "근처", "가까운", "가까이", "어디 있어"):
        place_type = parse_assistant_place_type(command_text)
        message, places = summarize_nearby_places(payload.area, payload.currentLocation, place_type)
        return response(
            "nearby",
            "speak",
            message,
            placeType=place_type,
            context={"places": places},
        )

    if looks_like_destination_command(command_text):
        destination_keyword = clean_destination_keyword(command_text)
        return navigation_response(destination_keyword)

    return response(
        "unknown",
        "speak",
        "명령을 이해하지 못했습니다. 누니야, 화곡역 3번 출구로 안내해줘처럼 말씀해 주세요.",
    )


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
        "yoloModelPath": str(MODEL_PATH),
        "yoloReady": YOLO is not None and MODEL_PATH.exists(),
        "streamServerYolo": STREAM_SERVER_YOLO,
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
    route = build_mobile_route_response(
        payload.area,
        payload.destinationKeyword,
        payload.currentLocation,
    )
    set_control_destination_from_route(payload.area, route, source="mobile-route")
    return route


@app.post("/api/assistant/command")
def handle_assistant_command(payload: AssistantCommandRequest):
    return build_assistant_command_response(payload)


@app.get("/api/assistant/health")
def get_assistant_health():
    base_url = lmstudio_base_url()
    enabled = assistant_llm_enabled()

    if not enabled:
        return {
            "ok": True,
            "llmEnabled": False,
            "baseUrl": base_url,
            "available": False,
            "message": "LLM is disabled. Rule-based assistant fallback is active.",
        }

    try:
        model = get_lmstudio_model()
        return {
            "ok": True,
            "llmEnabled": True,
            "baseUrl": base_url,
            "available": True,
            "model": model,
            "message": "LM Studio/OpenAI-compatible assistant is reachable.",
        }
    except Exception as error:
        return {
            "ok": True,
            "llmEnabled": True,
            "baseUrl": base_url,
            "available": False,
            "model": None,
            "message": f"LLM is not reachable. Rule-based fallback will be used. {error}",
        }


@app.post("/api/set_destination")
def set_destination(payload: ControlDestinationRequest):
    if payload.area != "hwagok":
        raise HTTPException(status_code=404, detail="지원하지 않는 구역입니다.")

    if not payload.destination.strip():
        raise HTTPException(status_code=400, detail="목적지가 비어 있습니다.")

    return set_control_destination(
        payload.area,
        payload.destination,
        payload.lat,
        payload.lng,
        source=payload.source,
        mode=payload.mode,
    )


@app.get("/api/get_destination")
def get_destination():
    if not latest_control_destination:
        return {
            "ok": True,
            "destination": "",
            "lat": None,
            "lng": None,
            "updatedAt": None,
        }

    return latest_control_destination


@app.post("/api/control/clear_destination")
def clear_control_destination():
    global latest_control_destination, latest_control_route
    latest_control_destination = {}
    latest_control_route = {}
    return {"ok": True, "message": "관제 목적지와 경로 후보를 초기화했습니다."}


@app.post("/api/control/route-candidates")
def sync_route_candidates(payload: RouteCandidateSyncRequest):
    global latest_control_route

    latest_control_route = {
        "ok": True,
        "area": payload.area,
        "destination": payload.destination,
        "start": payload.start,
        "candidates": payload.candidates,
        "best": payload.candidates[0] if payload.candidates else None,
        "updatedAt": datetime.now(timezone.utc).isoformat(),
    }
    return {
        "ok": True,
        "candidateCount": len(payload.candidates),
        "best": latest_control_route["best"],
    }


@app.get("/api/control/current-route")
def get_current_route():
    if not latest_control_route:
        return {"ok": True, "candidates": [], "best": None, "updatedAt": None}

    return latest_control_route


@app.post("/api/mobile/scene-guidance")
def create_scene_guidance(payload: SceneGuidanceRequest):
    mode = (payload.mode or "general").lower()
    detections = sorted(payload.detections, key=lambda item: item.confidence, reverse=True)
    obstacles = [
        detection
        for detection in detections
        if detection.label != "braille_block" and detection.confidence >= 0.45
    ]
    has_braille_block = any(detection.label == "braille_block" and detection.confidence >= 0.45 for detection in detections)

    if mode == "tactile" and has_braille_block and obstacles:
        primary = obstacles[0]
        label = KOREAN_OBJECT_LABELS.get(primary.label, primary.label)
        message = f"점자블록 위에 {label}로 보이는 장애물이 있습니다. 주의하세요."
        return {
            "ok": True,
            "mode": mode,
            "shouldSpeak": True,
            "message": maybe_polish_assistant_message(
                "tactile_scene_guidance",
                message,
                {"detections": [item.dict() for item in detections[:5]]},
            ),
        }

    if not obstacles:
        return {
            "ok": True,
            "mode": mode,
            "shouldSpeak": True,
            "message": "전방에 뚜렷한 장애물은 보이지 않습니다.",
        }

    phrases = []
    for detection in obstacles[:3]:
        label = KOREAN_OBJECT_LABELS.get(detection.label, detection.label)
        confidence = round(detection.confidence * 100)
        phrases.append(f"{detection.direction} {detection.distanceLevel}에 {label}, 신뢰도 {confidence}퍼센트")

    message = "현재 카메라 화면 기준으로 " + ". ".join(phrases) + "가 보입니다."
    return {
        "ok": True,
        "mode": mode,
        "shouldSpeak": True,
        "message": maybe_polish_assistant_message(
            "general_scene_guidance",
            message,
            {
                "detections": [item.dict() for item in detections[:5]],
                "routeState": payload.routeState or {},
            },
        ),
    }


@app.get("/api/admin/summary")
def get_admin_summary(area: str = "hwagok"):
    tactile_data = load_area_json(area, TACTILE_FILES, "점자블록 GeoJSON")
    risk_data = load_area_json(area, RISK_FILES, "위험 지점 JSON")
    nearby_data = load_area_json(area, NEARBY_FILES, "주변시설 JSON")
    log_path = detection_log_path(area)
    emergency_path = emergency_log_path(area)
    support_path = support_log_path(area)

    tactile_features = tactile_data.get("features", [])
    risks = risk_data.get("items", [])
    places = nearby_data.get("items", [])
    open_risks = [risk for risk in risks if risk.get("status") == "open"]
    resolved_risks = [risk for risk in risks if risk.get("status") == "resolved"]
    danger_risks = [risk for risk in risks if risk.get("level") == "danger"]
    detection_log_count = count_jsonl(log_path)
    emergency_log_count = count_jsonl(emergency_path)
    support_entries = read_jsonl(support_path)
    open_support_entries = [entry for entry in support_entries if entry.get("status") == "open"]
    help_support_entries = [entry for entry in support_entries if entry.get("type") == "help"]
    emergency_support_entries = [entry for entry in support_entries if entry.get("type") == "emergency"]

    return {
        "area": area,
        "tactileBlockCount": len(tactile_features),
        "riskCount": len(risks),
        "openRiskCount": len(open_risks),
        "resolvedRiskCount": len(resolved_risks),
        "dangerRiskCount": len(danger_risks),
        "nearbyPlaceCount": len(places),
        "mobileDetectionLogCount": detection_log_count,
        "mobileEmergencyLogCount": emergency_log_count,
        "supportRequestCount": len(support_entries),
        "openSupportRequestCount": len(open_support_entries),
        "supportHelpCount": len(help_support_entries),
        "supportEmergencyCount": len(emergency_support_entries),
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
            "matchScore": item.get("_matchScore"),
            "address": item.get("address", ""),
            "description": item.get("description", ""),
        }
        for item in items[:8]
    ]

    return {"area": payload.area, "keyword": payload.keyword, "candidates": candidates}


@app.post("/api/support/help")
def create_support_help(payload: SupportRequest):
    entry = write_support_event(payload, "help")
    return {
        "ok": True,
        "message": "도움 요청을 관제에 전달했습니다.",
        "entry": entry,
    }


@app.post("/api/support/emergency")
def create_support_emergency(payload: SupportRequest):
    entry = write_support_event(payload, "emergency")
    emergency_entry = {
        "timestamp": entry["timestamp"],
        "area": payload.area,
        "deviceId": payload.deviceId,
        "lat": payload.lat,
        "lng": payload.lng,
        "heading": payload.heading,
        "message": payload.message or "관제 긴급 요청",
        "mapUrl": entry["mapUrl"],
        "supportRequestId": entry["id"],
    }
    write_jsonl(emergency_log_path(payload.area), emergency_entry)
    return {
        "ok": True,
        "message": "긴급 요청을 관제에 전달했습니다.",
        "entry": entry,
    }


@app.get("/api/admin/support-requests")
def get_admin_support_requests(area: str = "hwagok", type: str = "all", status: str = "all", limit: int = 50):
    entries = list(reversed(read_jsonl(support_log_path(area))))
    normalized_type = normalize_keyword(type)
    normalized_status = normalize_keyword(status)

    if normalized_type != "all":
        entries = [entry for entry in entries if entry.get("type") == normalized_type]

    if normalized_status != "all":
        entries = [entry for entry in entries if entry.get("status") == normalized_status]

    safe_limit = max(1, min(limit, 200))
    return {
        "area": area,
        "count": len(entries),
        "items": entries[:safe_limit],
    }

@app.get("/api/admin/tactile-hazards")
def get_admin_tactile_hazards(area: str = "hwagok", limit: int = 50, status: str = "all"):
    path = tactile_hazard_log_path(area)
    safe_limit = max(1, min(limit, 200))
    entries = list(reversed(read_jsonl_tail(path, safe_limit)))

    normalized_status = normalize_keyword(status)

    if normalized_status != "all":
        entries = [
            entry
            for entry in entries
            if entry.get("status") == normalized_status
        ]

    return {
        "area": area,
        "count": len(entries),
        "items": entries,
    }


@app.post("/api/admin/support-requests/{request_id}/resolve")
def resolve_admin_support_request(request_id: str, area: str = "hwagok", memo: str = ""):
    path = support_log_path(area)
    entries = read_jsonl(path)
    found = None
    now = utc_now()

    for entry in entries:
        if entry.get("id") == request_id:
            entry["status"] = "resolved"
            entry["resolvedAt"] = now
            entry["adminMemo"] = memo
            found = entry
            break

    if found is None:
        raise HTTPException(status_code=404, detail="Support request not found")

    replace_jsonl(path, entries)
    return {"ok": True, "entry": found}


@app.post("/api/control/stream/start")
def request_control_stream_start(payload: StreamControlRequest):
    latest_stream_status.update(
        {
            "area": payload.area,
            "deviceId": payload.deviceId,
            "requested": True,
            "requestedBy": payload.requestedBy,
            "updatedAt": utc_now(),
            "message": payload.message or "Control web requested camera stream.",
        }
    )
    command = enqueue_mobile_command(
        payload.deviceId,
        "start_stream",
        {
            "area": payload.area,
            "message": "관제에서 카메라 스트리밍을 요청했습니다.",
        },
    )
    return {"ok": True, "stream": current_stream_snapshot(), "command": command}


@app.post("/api/control/stream/stop")
def request_control_stream_stop(payload: StreamControlRequest):
    global latest_processed_frame
    latest_stream_status.update(
        {
            "area": payload.area,
            "deviceId": payload.deviceId,
            "requested": False,
            "active": False,
            "requestedBy": payload.requestedBy,
            "updatedAt": utc_now(),
            "message": payload.message or "Control web stopped camera stream.",
        }
    )
    latest_processed_frame = None
    command = enqueue_mobile_command(
        payload.deviceId,
        "stop_stream",
        {
            "area": payload.area,
            "message": "관제에서 카메라 스트리밍을 종료했습니다.",
        },
    )
    return {"ok": True, "stream": current_stream_snapshot(), "command": command}


@app.get("/api/control/stream/status")
def get_control_stream_status():
    return {"ok": True, "stream": current_stream_snapshot()}


@app.get("/api/mobile/commands")
def get_mobile_commands(deviceId: str = "demo-phone-01", area: str = "hwagok"):
    commands = mobile_command_queues.pop(deviceId, [])
    return {
        "ok": True,
        "area": area,
        "deviceId": deviceId,
        "commands": commands,
        "stream": current_stream_snapshot(),
    }


@app.post("/api/mobile/emergency")
def create_mobile_emergency(payload: EmergencyRequest):
    if payload.area != "hwagok":
        raise HTTPException(status_code=404, detail="지원하지 않는 구역입니다.")

    log_path = emergency_log_path(payload.area)
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

    support_entry = write_support_event(
        SupportRequest(
            area=payload.area,
            deviceId=payload.deviceId,
            lat=payload.lat,
            lng=payload.lng,
            heading=payload.heading,
            message=payload.message or "Mobile emergency request",
            mode="emergency",
            sceneDescription="",
            detections=[],
        ),
        "emergency",
    )
    entry["supportRequestId"] = support_entry["id"]

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

@app.post("/api/mobile/location")
def update_mobile_location(payload: MobileLocationRequest):
    if payload.area != "hwagok":
        raise HTTPException(status_code=404, detail="지원하지 않는 구역입니다.")

    entry = {
        "ok": True,
        "area": payload.area,
        "deviceId": payload.deviceId,
        "lat": payload.lat,
        "lng": payload.lng,
        "heading": payload.heading,
        "updatedAt": utc_now(),
        "mapUrl": f"https://maps.google.com/?q={payload.lat},{payload.lng}",
    }

    latest_mobile_locations[payload.deviceId] = entry

    print(
        "[mobile-location] "
        f"{payload.deviceId} {payload.lat},{payload.lng} heading={payload.heading}",
        flush=True,
    )

    return entry


@app.get("/api/mobile/location")
def get_mobile_location(deviceId: str = "demo-phone-01", area: str = "hwagok"):
    if area != "hwagok":
        raise HTTPException(status_code=404, detail="지원하지 않는 구역입니다.")

    location = latest_mobile_locations.get(deviceId)

    if not location:
        return {
            "ok": True,
            "area": area,
            "deviceId": deviceId,
            "lat": None,
            "lng": None,
            "heading": None,
            "updatedAt": None,
            "message": "아직 수신된 위치 정보가 없습니다.",
        }

    return location


@app.get("/api/mobile/locations")
def get_mobile_locations(area: str = "hwagok"):
    if area != "hwagok":
        raise HTTPException(status_code=404, detail="지원하지 않는 구역입니다.")

    return {
        "ok": True,
        "area": area,
        "items": list(latest_mobile_locations.values()),
    }

@app.post("/api/mobile/tactile-hazard")
def create_mobile_tactile_hazard(payload: TactileHazardRequest):
    if payload.area != "hwagok":
        raise HTTPException(status_code=404, detail="지원하지 않는 구역입니다.")

    hazard_detections = [
        detection
        for detection in payload.detections
        if detection.label != "person"
        and detection.label != "braille_block"
        and detection.isStaticObstacle
        and detection.confidence >= 0.45
    ]

    if not hazard_detections:
        return {
            "ok": True,
            "stored": False,
            "reason": "점자블록 위험 장애물 조건에 해당하는 감지가 없습니다.",
        }

    primary = hazard_detections[0]
    marker_distance = distance_level_to_meter(primary.distanceLevel)
    marker = estimate_marker_location(
        payload.lat,
        payload.lng,
        payload.heading,
        marker_distance,
    )

    now = utc_now()
    log_entry = {
        "id": f"tactile-hazard-{int(time.time() * 1000)}",
        "timestamp": now,
        "updatedAt": now,
        "area": payload.area,
        "deviceId": payload.deviceId,
        "type": "tactile_hazard",
        "status": "open",
        "level": "danger",
        "lat": payload.lat,
        "lng": payload.lng,
        "heading": payload.heading,
        "markerLat": marker["lat"],
        "markerLng": marker["lng"],
        "markerEstimated": marker["estimated"],
        "message": payload.message or "점자블록 위 장애물이 감지되었습니다.",
        "detections": [
            {
                "label": detection.label,
                "confidence": round(detection.confidence, 4),
                "direction": detection.direction,
                "distanceLevel": detection.distanceLevel,
                "isStaticObstacle": detection.isStaticObstacle,
            }
            for detection in hazard_detections[:3]
        ],
        "mapUrl": f"https://maps.google.com/?q={marker['lat']},{marker['lng']}",
    }

    write_jsonl(tactile_hazard_log_path(payload.area), log_entry)

    print(
        "[tactile-hazard] "
        f"{payload.deviceId} {primary.label} {primary.confidence:.2f} "
        f"{primary.direction} {primary.distanceLevel} "
        f"marker={log_entry['markerLat']},{log_entry['markerLng']}",
        flush=True,
    )

    return {
        "ok": True,
        "stored": True,
        "entry": log_entry,
        "markerLat": log_entry["markerLat"],
        "markerLng": log_entry["markerLng"],
        "markerEstimated": log_entry["markerEstimated"],
    }

@app.get("/api/admin/detections")
def get_admin_detections(area: str = "hwagok", limit: int = 50):
    log_path = detection_log_path(area)
    safe_limit = max(1, min(limit, 200))
    return {"area": area, "items": list(reversed(read_jsonl_tail(log_path, safe_limit)))}


@app.get("/api/admin/logs")
def get_admin_logs(area: str = "hwagok", type: str = "detections", limit: int = 50):
    log_path = admin_log_path(area, type)
    safe_limit = max(1, min(limit, 500))
    return {
        "area": area,
        "type": type,
        "count": count_jsonl(log_path),
        "items": list(reversed(read_jsonl_tail(log_path, safe_limit))),
    }


@app.post("/api/admin/logs/clear")
def clear_admin_logs(area: str = "hwagok", type: str = "detections"):
    log_path = admin_log_path(area, type)
    previous_count = count_jsonl(log_path)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_path.write_text("", encoding="utf-8")
    return {
        "ok": True,
        "area": area,
        "type": type,
        "clearedCount": previous_count,
    }


@app.get("/video_feed")
async def video_feed():
    return StreamingResponse(
        generate_frames(),
        media_type="multipart/x-mixed-replace; boundary=frame",
    )


@app.websocket("/ws/stream")
async def websocket_endpoint(websocket: WebSocket):
    global latest_processed_frame, last_tts_time
    await websocket.accept()
    latest_stream_status.update(
        {
            "active": True,
            "requested": True,
            "updatedAt": utc_now(),
            "message": "Smartphone stream connected.",
        }
    )

    model = None

    if STREAM_SERVER_YOLO:
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

            if not STREAM_SERVER_YOLO:
                latest_processed_frame = data
                continue

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
        latest_stream_status.update(
            {
                "active": False,
                "updatedAt": utc_now(),
                "message": "Smartphone stream disconnected.",
            }
        )
    except Exception as error:
        print(f"[mobile-stream] processing error: {error}")
        latest_stream_status.update(
            {
                "active": False,
                "updatedAt": utc_now(),
                "message": f"Smartphone stream error: {error}",
            }
        )


@app.get("/")
def root():
    return {
        "service": "BlindCareNav backend",
        "health": "/api/health",
        "adminSummary": "/api/admin/summary?area=hwagok",
        "supportRequests": "/api/admin/support-requests?area=hwagok",
        "streamStatus": "/api/control/stream/status",
    }


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8000)
