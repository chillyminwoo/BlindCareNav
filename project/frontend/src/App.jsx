import { useCallback, useEffect, useRef, useState } from "react";
import { buildRouteCandidates } from "./routeEngine";
import warningIcon from './assets/warning.png';

const API_BASE       = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");
const KAKAO_JS_KEY   = import.meta.env.VITE_KAKAO_JS_KEY  || "";
const KAKAO_REST_KEY = import.meta.env.VITE_KAKAO_REST_KEY || "";
const DEFAULT_POSITION = { lat: 37.5421, lng: 126.841306 };

const OVERPASS_SERVERS = [
  "https://overpass-api.de/api/interpreter",
  "https://lz4.overpass-api.de/api/interpreter",
  "https://z.overpass-api.de/api/interpreter",
];

const KOREAN_OBJECT_LABELS = {
  person: "사람",
  braille_block: "점자블록",
  car: "차량",
  truck: "차량",
  bus: "버스",
  bicycle: "자전거",
  motorcycle: "오토바이",
  kickboard: "킥보드",
  green_light: "초록 신호",
  red_light: "빨간 신호",
  bench: "벤치",
  chair: "의자",
  backpack: "가방",
  handbag: "가방",
  suitcase: "캐리어",
  umbrella: "우산",
  bottle: "병",
  cup: "컵",
};

const safeJson = async (res) => {
  if (!res.ok) return null;
  try { return await res.json(); } catch { return null; }
};

function apiUrl(path) {
  if (API_BASE === "/api" && path.startsWith("/api")) return path;
  return `${API_BASE}${path}`;
}

function toLatLng(p) {
  return new window.kakao.maps.LatLng(p.lat, p.lng);
}

function ensureGlobalStyles() {
  if (document.getElementById("nuni-global-styles")) return;
  const s = document.createElement("style");
  s.id = "nuni-global-styles";
  s.textContent = `
    @keyframes nuni-pulse {
      0%   { transform:scale(1);   opacity:0.55; }
      70%  { transform:scale(2.8); opacity:0;    }
      100% { transform:scale(2.8); opacity:0;    }
    }
    .nuni-ring { width:26px; height:26px; border-radius:50%; background:rgba(37,99,235,0.5); animation:nuni-pulse 2s ease-out infinite; pointer-events:none; }
    @keyframes nuni-danger-flash { 0%,100% { background:rgba(220,38,38,0); } 50% { background:rgba(220,38,38,0.45); } }
  `;
  document.head.appendChild(s);
}

function buildDotMarkerImage() {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="22" height="22" viewBox="0 0 22 22"><circle cx="11" cy="11" r="10" fill="white"/><circle cx="11" cy="11" r="7" fill="#2563EB"/></svg>`;
  const url  = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svg);
  return new window.kakao.maps.MarkerImage(url, new window.kakao.maps.Size(22, 22), { offset: new window.kakao.maps.Point(11, 11) });
}

function createPinMarkerImage(color) {
  const svg = `<svg width="34" height="42" viewBox="0 0 34 42" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M17 41C17 41 31 26.8 31 15.5C31 7.49 24.73 1 17 1C9.27 1 3 7.49 3 15.5C3 26.8 17 41 17 41Z" fill="${color}" stroke="#111827" stroke-width="2"/><circle cx="17" cy="15.5" r="5.5" fill="white"/></svg>`;
  return new window.kakao.maps.MarkerImage(
    `data:image/svg+xml;charset=UTF-8,${encodeURIComponent(svg)}`,
    new window.kakao.maps.Size(34, 42),
    { offset: new window.kakao.maps.Point(17, 42) }
  );
}

function buildPulseOverlayDom() {
  ensureGlobalStyles();
  const wrap = document.createElement("div");
  wrap.style.cssText = "position:absolute;transform:translate(-50%,-50%);pointer-events:none;";
  const ring = document.createElement("div");
  ring.className = "nuni-ring";
  wrap.appendChild(ring);
  return wrap;
}

function buildFanOverlayDom() {
  const NS  = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(NS, "svg");
  svg.setAttribute("width", "90"); svg.setAttribute("height", "90"); svg.setAttribute("viewBox", "0 0 90 90");
  svg.style.cssText = "position:absolute;transform:translate(-50%,-50%);overflow:visible;pointer-events:none;";
  const path = document.createElementNS(NS, "path");
  path.setAttribute("fill", "rgba(37,99,235,0.2)");
  path.setAttribute("stroke", "rgba(37,99,235,0.5)");
  path.setAttribute("stroke-width", "1.5");
  svg.appendChild(path);
  return { svg, path };
}

function calcFanPath(heading) {
  const r = 38, cx = 45, cy = 45, sweep = 60;
  const rad = (d) => (d * Math.PI) / 180;
  const base = heading - 90;
  const x1 = cx + r * Math.cos(rad(base - sweep / 2));
  const y1 = cy + r * Math.sin(rad(base - sweep / 2));
  const x2 = cx + r * Math.cos(rad(base + sweep / 2));
  const y2 = cy + r * Math.sin(rad(base + sweep / 2));
  return `M${cx},${cy} L${x1},${y1} A${r},${r} 0 0,1 ${x2},${y2} Z`;
}

export default function App() {
  const mapDivRef             = useRef(null);
  const mapRef                = useRef(null);
  const mapReadyRef           = useRef(false);
  const dotMarkerRef          = useRef(null);
  const pulseOverlayRef       = useRef(null);
  const fanOverlayRef         = useRef(null);
  const fanPathElemRef        = useRef(null);
  const destinationMarkerRef  = useRef(null);
  const routeLinesRef         = useRef([]);
  const tactileLinesRef       = useRef([]);
  const riskMarkersRef        = useRef([]);
  // ← detectionMarkersRef 제거, 아래로 교체
  const tactileHazardMarkersRef = useRef([]);
  const supportMarkersRef     = useRef([]);
  const poiOverlaysRef        = useRef([]);
  const destinationNameRef    = useRef("");
  const backendAliveRef       = useRef(true);
  const headingRef            = useRef(null);
  const gpsWatchIdRef         = useRef(null);
  const gpsCenteredRef        = useRef(false);
  const gpsErrLoggedRef       = useRef(false);
  const currentPosRef         = useRef(DEFAULT_POSITION);
  const orientationHandlerRef = useRef(null);
  const dangerTimerRef        = useRef(null);
  const prevEmergencyCountRef = useRef(null);
  const prevHazardCountRef    = useRef(null);
  const calculateAndDrawRouteRef = useRef(null);
  const tactileGeoJsonRef     = useRef(null);
  const poiDataRef            = useRef({ crosswalks: [], trafficLights: [] });

  const mobilePosRef          = useRef(null);
  const mobileLocModeRef      = useRef("mobile");

  const streamInfoRef         = useRef({ requested: false, active: false });
  const streamActiveRef       = useRef(false);
  const canvasRef             = useRef(null);
  const mjpegAbortRef         = useRef(null);
  const streamLogRef          = useRef("대기 중");

  const [status,          setStatus         ] = useState("관제 웹을 준비하고 있습니다.");
  const [currentPosition, setCurrentPosition] = useState(DEFAULT_POSITION);
  const [destination,     setDestinationState] = useState(null);
  const [routeCandidates, setRouteCandidates ] = useState([]);
  const [summary,         setSummary         ] = useState(null);
  const [risks,           setRisks           ] = useState([]);
  // ← detections state 제거
  const [supportRequests, setSupportRequests ] = useState([]);
  const [tactileGeoJson,  setTactileGeoJson  ] = useState(null);
  const [poiData,         setPoiData         ] = useState({ crosswalks: [], trafficLights: [] });
  const [dangerAlert,     setDangerAlert     ] = useState(false);
  const [streamVisible,   setStreamVisible   ] = useState(true);
  const [locMode,         setLocMode         ] = useState("mobile");
  const [streamUiInfo,    setStreamUiInfo    ] = useState({ requested: false, active: false });
  const [streamLog,       setStreamLog       ] = useState("대기 중");

  useEffect(() => { currentPosRef.current = currentPosition; }, [currentPosition]);
  useEffect(() => { mobileLocModeRef.current = locMode; }, [locMode]);
  useEffect(() => { tactileGeoJsonRef.current = tactileGeoJson; }, [tactileGeoJson]);
  useEffect(() => { poiDataRef.current = poiData; }, [poiData]);
  useEffect(() => {
    ensureGlobalStyles();
    return () => {
      if (dangerTimerRef.current) clearTimeout(dangerTimerRef.current);
      stopMjpeg();
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ── MJPEG canvas 렌더러 ──────────────────────────────────
  const stopMjpeg = useCallback(() => {
    if (mjpegAbortRef.current) {
      mjpegAbortRef.current.abort();
      mjpegAbortRef.current = null;
    }
  }, []);

  const startMjpeg = useCallback(() => {
    stopMjpeg();
    const ctrl = new AbortController();
    mjpegAbortRef.current = ctrl;

    const url = `/video_feed?t=${Date.now()}`;
    setStreamLog("스트림 연결 중...");

    (async () => {
      try {
        const res = await fetch(url, { signal: ctrl.signal });
        if (!res.ok || !res.body) {
          setStreamLog(`스트림 응답 오류: ${res.status}`);
          return;
        }

        const contentType = res.headers.get("content-type") || "";
        const boundaryMatch = contentType.match(/boundary=([^\s;]+)/);
        const boundary = boundaryMatch ? `--${boundaryMatch[1]}` : "--frame";
        const boundaryBytes = new TextEncoder().encode(boundary);

        const reader = res.body.getReader();
        let buf = new Uint8Array(0);
        let frameCount = 0;

        const appendBuf = (chunk) => {
          const next = new Uint8Array(buf.length + chunk.length);
          next.set(buf); next.set(chunk, buf.length);
          buf = next;
        };

        const indexOfSeq = (haystack, needle, from = 0) => {
          outer: for (let i = from; i <= haystack.length - needle.length; i++) {
            for (let j = 0; j < needle.length; j++) {
              if (haystack[i + j] !== needle[j]) continue outer;
            }
            return i;
          }
          return -1;
        };

        const CRLF2 = new Uint8Array([13, 10, 13, 10]);
        setStreamLog("스트림 수신 중...");

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          appendBuf(value);

          while (true) {
            const boundaryIdx = indexOfSeq(buf, boundaryBytes);
            if (boundaryIdx === -1) break;
            const headerEnd = indexOfSeq(buf, CRLF2, boundaryIdx);
            if (headerEnd === -1) break;
            const nextBoundary = indexOfSeq(buf, boundaryBytes, boundaryIdx + boundaryBytes.length);
            if (nextBoundary === -1) break;

            const jpegStart = headerEnd + 4;
            const jpegEnd = nextBoundary - 2;
            if (jpegEnd <= jpegStart) { buf = buf.slice(nextBoundary); break; }

            const jpegBytes = buf.slice(jpegStart, jpegEnd);
            buf = buf.slice(nextBoundary);

            const blob = new Blob([jpegBytes], { type: "image/jpeg" });
            const imgUrl = URL.createObjectURL(blob);
            const img = new Image();
            img.onload = () => {
              const canvas = canvasRef.current;
              if (!canvas) { URL.revokeObjectURL(imgUrl); return; }
              canvas.width  = img.width;
              canvas.height = img.height;
              const ctx = canvas.getContext("2d");
              ctx.drawImage(img, 0, 0);
              URL.revokeObjectURL(imgUrl);
            };
            img.src = imgUrl;
            frameCount++;
            if (frameCount % 30 === 0) setStreamLog(`수신 중 (${frameCount}프레임)`);
          }
        }
        setStreamLog("스트림 종료");
      } catch (e) {
        if (e.name !== "AbortError") setStreamLog(`스트림 오류: ${e.message}`);
      }
    })();
  }, [stopMjpeg]);

// 변경 후
const updateLocationOverlay = useCallback((point) => {
  if (!mapRef.current) return;
  const latlng = toLatLng(point);
  if (!dotMarkerRef.current) {
    dotMarkerRef.current = new window.kakao.maps.Marker({ map: mapRef.current, position: latlng, image: buildDotMarkerImage(), zIndex: 10 });
  } else { dotMarkerRef.current.setPosition(latlng); }
  if (!pulseOverlayRef.current) {
    pulseOverlayRef.current = new window.kakao.maps.CustomOverlay({ map: mapRef.current, position: latlng, content: buildPulseOverlayDom(), zIndex: 9 });
  } else { pulseOverlayRef.current.setPosition(latlng); }
}, []);

  const updateOverlayRef = useRef(updateLocationOverlay);
  useEffect(() => { updateOverlayRef.current = updateLocationOverlay; }, [updateLocationOverlay]);

  const clearMapObjects = useCallback((refs) => {
    refs.current.forEach((o) => o.setMap(null));
    refs.current = [];
  }, []);

  const drawTactileBlocks = useCallback((geoJson) => {
    if (!mapRef.current || !geoJson?.features) return;
    clearMapObjects(tactileLinesRef);
    geoJson.features.forEach((f) => {
      if (f.geometry?.type !== "LineString") return;
      const path = f.geometry.coordinates.map(([lng, lat]) => new window.kakao.maps.LatLng(lat, lng));
      tactileLinesRef.current.push(new window.kakao.maps.Polyline({ map: mapRef.current, path, strokeWeight: 6, strokeColor: "#FFD700", strokeOpacity: 0.78 }));
    });
  }, [clearMapObjects]);

  const drawRiskMarkers = useCallback((items) => {
    if (!mapRef.current) return;
    clearMapObjects(riskMarkersRef);
    const img = new window.kakao.maps.MarkerImage(warningIcon, new window.kakao.maps.Size(32, 32));
    items.forEach((risk) => {
      const m = new window.kakao.maps.Marker({ map: mapRef.current, position: new window.kakao.maps.LatLng(risk.lat, risk.lng), image: img });
      const iw = new window.kakao.maps.InfoWindow({ content: `<div style="padding:8px;font-size:12px;min-width:150px"><b>${risk.title}</b><br/>${risk.level} · ${risk.status}</div>` });
      window.kakao.maps.event.addListener(m, "click", () => iw.open(mapRef.current, m));
      riskMarkersRef.current.push(m);
    });
  }, [clearMapObjects]);

  // ── 점자블록 장애물 마커 ──────────────────────────────────
  // 서버 필드: markerLat/Lng, message, detections[0].label, level, status, timestamp
  const drawTactileHazards = useCallback((items) => {
    if (!mapRef.current) return;
    clearMapObjects(tactileHazardMarkersRef);

    const svg = `<svg width="30" height="30" viewBox="0 0 30 30" xmlns="http://www.w3.org/2000/svg">
      <polygon points="15,3 27,27 3,27" fill="#F97316" stroke="#7C2D12" stroke-width="2"/>
      <text x="15" y="24" text-anchor="middle" font-size="14" font-weight="bold" fill="white">!</text>
    </svg>`;
    const markerImg = new window.kakao.maps.MarkerImage(
      `data:image/svg+xml;charset=UTF-8,${encodeURIComponent(svg)}`,
      new window.kakao.maps.Size(30, 30),
      { offset: new window.kakao.maps.Point(15, 15) }
    );

    items
      .filter((entry) => entry.status === "open")   // 방어: 혹시 섞여와도 open만
      .slice(0, 50)
      .forEach((entry) => {
        const lat = entry.markerLat || entry.lat;
        const lng = entry.markerLng || entry.lng;
        if (!lat || !lng) return;

        // 서버 새 필드 구조에 맞게 읽기
        const primaryLabel = entry.detections?.[0]?.label;
        const label        = KOREAN_OBJECT_LABELS[primaryLabel] || primaryLabel || "장애물";
        const displayName  = entry.message || label;
        const level        = entry.level || "";
        const timestamp    = entry.timestamp
          ? new Date(entry.timestamp).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" })
          : "";

        const m = new window.kakao.maps.Marker({
          map: mapRef.current,
          position: new window.kakao.maps.LatLng(lat, lng),
          title: displayName,
          image: markerImg,
        });
        const iw = new window.kakao.maps.InfoWindow({
          content: `<div style="padding:8px;font-size:12px;min-width:200px">
            <b style="color:#ea580c">🔶 점자블록 장애물</b><br/>
            <span style="font-size:14px;font-weight:bold">${displayName}</span><br/>
            ${label !== displayName ? `<span style="font-size:11px;color:#555">분류: ${label}</span><br/>` : ""}
            ${level     ? `<span style="font-size:11px">위험도: ${level}</span><br/>`   : ""}
            ${timestamp ? `<span style="font-size:11px;color:#888">${timestamp}</span>` : ""}
            ${entry.deviceId ? `<br/><span style="font-size:11px;color:#aaa">${entry.deviceId}</span>` : ""}
          </div>`,
        });
        window.kakao.maps.event.addListener(m, "click", () => iw.open(mapRef.current, m));
        tactileHazardMarkersRef.current.push(m);
      });
  }, [clearMapObjects]);

  const drawSupportRequests = useCallback((items) => {
    if (!mapRef.current) return;
    clearMapObjects(supportMarkersRef);
    items.filter((e) => e.status === "open").slice(0, 20).forEach((entry) => {
      const isEmergency = entry.type === "emergency";
      const marker = new window.kakao.maps.Marker({ map: mapRef.current, position: new window.kakao.maps.LatLng(entry.lat, entry.lng), image: createPinMarkerImage(isEmergency ? "#DC2626" : "#F59E0B") });
      const iw = new window.kakao.maps.InfoWindow({ content: `<div style="padding:8px;font-size:12px;min-width:190px"><b>${isEmergency ? "긴급" : "도움"}</b><br/>${entry.deviceId}<br/>${entry.message || ""}</div>` });
      window.kakao.maps.event.addListener(marker, "click", () => iw.open(mapRef.current, marker));
      supportMarkersRef.current.push(marker);
    });
  }, [clearMapObjects]);

  const drawCrosswalks = useCallback((crosswalks) => {
    if (!mapRef.current) return;
    clearMapObjects(poiOverlaysRef);
    crosswalks.forEach((p) => {
      poiOverlaysRef.current.push(new window.kakao.maps.Circle({ center: new window.kakao.maps.LatLng(p.lat, p.lng), radius: 8, strokeColor: "#00E676", strokeOpacity: 0.8, strokeWeight: 2, fillColor: "#00E676", fillOpacity: 0.36, map: mapRef.current }));
    });
  }, [clearMapObjects]);

  const drawRouteCandidates = useCallback((candidates) => {
    if (!mapRef.current) return;
    clearMapObjects(routeLinesRef);
    candidates.slice(1).forEach((c) => {
      const path = c.coords.map(([lng, lat]) => new window.kakao.maps.LatLng(lat, lng));
      routeLinesRef.current.push(new window.kakao.maps.Polyline({ map: mapRef.current, path, strokeWeight: 5, strokeColor: "#8A8A8A", strokeOpacity: 0.5 }));
    });
    const best = candidates[0];
    if (!best) return;
    const path = best.coords.map(([lng, lat]) => new window.kakao.maps.LatLng(lat, lng));
    routeLinesRef.current.push(new window.kakao.maps.Polyline({ map: mapRef.current, path, strokeWeight: 8, strokeColor: "#2563EB", strokeOpacity: 0.95 }));
    const bounds = new window.kakao.maps.LatLngBounds();
    path.forEach((p) => bounds.extend(p));
    mapRef.current.setBounds(bounds);
  }, [clearMapObjects]);

  const fetchCrosswalks = useCallback(async () => {
    if (!mapRef.current) return;
    const b = mapRef.current.getBounds();
    const sw = b.getSouthWest(), ne = b.getNorthEast();
    const q = `[out:json][timeout:20];(node["highway"="crossing"](${sw.getLat()},${sw.getLng()},${ne.getLat()},${ne.getLng()});way["highway"="footway"]["footway"="crossing"](${sw.getLat()},${sw.getLng()},${ne.getLat()},${ne.getLng()}););out center;`;
    for (const server of OVERPASS_SERVERS) {
      try {
        const ctrl = new AbortController();
        const t = setTimeout(() => ctrl.abort(), 15000);
        const res = await fetch(server, { method: "POST", body: q, signal: ctrl.signal });
        clearTimeout(t);
        if (!res.ok) continue;
        const data = await res.json();
        const crosswalks = data.elements.map((el) => ({ lat: el.lat || el.center?.lat, lng: el.lon || el.center?.lon })).filter((p) => p.lat && p.lng);
        setPoiData({ crosswalks, trafficLights: [] });
        drawCrosswalks(crosswalks);
        return;
      } catch { /* 다음 서버 */ }
    }
  }, [drawCrosswalks]);

  const setDestinationMarker = useCallback((point, name) => {
    if (!mapRef.current) return;
    if (destinationMarkerRef.current) destinationMarkerRef.current.setMap(null);
    const img = new window.kakao.maps.MarkerImage("https://t1.daumcdn.net/localimg/localimages/07/mapapidoc/marker_red.png", new window.kakao.maps.Size(31, 35));
    destinationMarkerRef.current = new window.kakao.maps.Marker({ map: mapRef.current, position: toLatLng(point), title: name, image: img });
  }, []);

  const geocodeByKeyword = useCallback(async (keyword) => {
    if (!KAKAO_REST_KEY) throw new Error("VITE_KAKAO_REST_KEY가 없습니다.");
    const pos = currentPosRef.current;
    const q = new URLSearchParams({ query: keyword, x: String(pos.lng), y: String(pos.lat), radius: "2000", sort: "distance" });
    const res = await fetch(`https://dapi.kakao.com/v2/local/search/keyword.json?${q}`, { headers: { Authorization: `KakaoAK ${KAKAO_REST_KEY}` } });
    const data = await res.json();
    const first = data.documents?.[0];
    if (!first) throw new Error(`목적지 후보 없음: ${keyword}`);
    return { name: first.place_name || keyword, lat: Number(first.y), lng: Number(first.x) };
  }, []);

  const calculateAndDrawRoute = useCallback(async (point, name) => {
    const geoJson = tactileGeoJsonRef.current;
    if (!geoJson) { setStatus("점자블록 데이터 준비 중입니다."); return; }
    setStatus(`${name} 경로를 계산하고 있습니다.`);
    setDestinationState({ ...point, name });
    setDestinationMarker(point, name);
    const pos = currentPosRef.current;
    const candidates = await buildRouteCandidates(pos, point, geoJson, poiDataRef.current);
    setRouteCandidates(candidates);
    drawRouteCandidates(candidates);
    const best = candidates[0];
    if (best) {
      setStatus(`최적 경로 계산 완료: ${best.finalScore}점, ${best.routeLengthMeter}m`);
      await fetch(apiUrl("/api/control/route-candidates"), {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ area: "hwagok", destination: { name, ...point }, start: pos, candidates }),
      });
    }
  }, [drawRouteCandidates, setDestinationMarker]);
  useEffect(() => { calculateAndDrawRouteRef.current = calculateAndDrawRoute; }, [calculateAndDrawRoute]);

  const pollControlDestination = useCallback(async () => {
    if (!backendAliveRef.current) return;
    try {
      const res = await fetch(apiUrl("/api/get_destination"));
      if (!res.ok) { backendAliveRef.current = false; return; }
      backendAliveRef.current = true;
      let data; try { data = await res.json(); } catch { return; }
      const name = data.destination || "";
      if (!name || name === destinationNameRef.current) return;
      destinationNameRef.current = name;
      if (data.lat && data.lng) {
        await calculateAndDrawRoute({ lat: Number(data.lat), lng: Number(data.lng) }, name);
      } else {
        const point = await geocodeByKeyword(name);
        await calculateAndDrawRoute({ lat: point.lat, lng: point.lng }, point.name);
      }
    } catch { backendAliveRef.current = false; }
  }, [calculateAndDrawRoute, geocodeByKeyword]);

  const resolveSupportRequest = useCallback(async (requestId) => {
    try {
      await fetch(apiUrl(`/api/admin/support-requests/${encodeURIComponent(requestId)}/resolve?area=hwagok`), { method: "POST" });
      setSupportRequests((items) => items.map((item) => item.id === requestId ? { ...item, status: "resolved" } : item));
    } catch (err) { console.warn("지원 요청 처리 실패", err); }
  }, []);

  const triggerDangerAlert = useCallback(() => {
    setDangerAlert(true);
    if (dangerTimerRef.current) clearTimeout(dangerTimerRef.current);
    dangerTimerRef.current = setTimeout(() => { setDangerAlert(false); dangerTimerRef.current = null; }, 3000);
  }, []);

  // ── AI 감지 + 긴급 마커 전체 초기화 ───────────────────
  const [isClearing, setIsClearing] = useState(false);

  const clearAllMarkers = useCallback(async () => {
    if (!window.confirm("점자블록 장애물 기록과 긴급/도움 요청 기록을 모두 초기화할까요?\n(지도 마커 + 서버 로그 전부 삭제)")) return;
    setIsClearing(true);
    try {
      clearMapObjects(tactileHazardMarkersRef);
      clearMapObjects(supportMarkersRef);
      setSupportRequests([]);
      prevHazardCountRef.current = 0;

      // tactile-hazards clear: 백엔드 admin_log_path에 타입 추가 필요
      // 현재 백엔드에 없으면 404로 조용히 실패 (Promise.allSettled)
      await Promise.allSettled([
        fetch(apiUrl("/api/admin/logs/clear?area=hwagok&type=tactile-hazards"), { method: "POST" }),
        fetch(apiUrl("/api/admin/logs/clear?area=hwagok&type=emergency"), { method: "POST" }),
        fetch(apiUrl("/api/admin/logs/clear?area=hwagok&type=support"),   { method: "POST" }),
      ]);

      setStatus("점자블록 장애물 및 긴급 마커가 초기화되었습니다.");
    } catch (err) {
      console.warn("초기화 실패", err);
      setStatus("초기화 중 오류가 발생했습니다.");
    } finally {
      setIsClearing(false);
    }
  }, [clearMapObjects]);

  // ── 스트리밍 시작/중지 ─────────────────────────────────
  const handleStreamRequest = useCallback(async (start) => {
    const path = start ? "/api/control/stream/start" : "/api/control/stream/stop";
    setStreamLog(start ? "방송 요청 전송 중..." : "중지 요청 중...");
    try {
      const res = await fetch(apiUrl(path), {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ area: "hwagok", deviceId: "demo-phone-01", requestedBy: "control-web" }),
      });
      if (res.ok) {
        const data = await res.json();
        const info = data.stream || {};
        streamInfoRef.current = info;
        setStreamUiInfo({ ...info });
        if (start) {
          setStatus("방송 요청을 앱으로 전송했습니다. 앱이 카메라를 켜면 자동으로 표시됩니다.");
          setStreamLog(`명령 전송 완료. 앱 pending: ${info.pendingCommandCount ?? "?"}개`);
          const checkActive = setInterval(async () => {
            try {
              const sr = await fetch(apiUrl("/api/control/stream/status"));
              const sd = await safeJson(sr);
              if (sd?.stream?.active) {
                clearInterval(checkActive);
                streamInfoRef.current = sd.stream;
                streamActiveRef.current = true;
                setStreamUiInfo({ ...sd.stream });
                setStreamLog("앱 연결됨. 스트림 시작.");
                startMjpeg();
              }
            } catch { /* 무시 */ }
          }, 2000);
          setTimeout(() => clearInterval(checkActive), 30000);
        } else {
          streamActiveRef.current = false;
          stopMjpeg();
          const canvas = canvasRef.current;
          if (canvas) { const ctx = canvas.getContext("2d"); ctx.clearRect(0, 0, canvas.width, canvas.height); }
          setStreamLog("중지됨");
          setStatus("스트리밍을 중지했습니다.");
        }
      } else {
        setStreamLog(`요청 실패: HTTP ${res.status}`);
      }
    } catch (err) {
      setStreamLog(`네트워크 오류: ${err.message}`);
    }
  }, [startMjpeg, stopMjpeg]);

  // ── 백엔드 폴링 ─────────────────────────────────────────
  const refreshBackendData = useCallback(async () => {
    try {
      const [rR, hzR, sR, supR, stmR, locR] = await Promise.all([
        fetch(apiUrl("/api/risks?area=hwagok")),
        fetch(apiUrl("/api/admin/tactile-hazards?area=hwagok&limit=50&status=open")),
        fetch(apiUrl("/api/admin/summary?area=hwagok")),
        fetch(apiUrl("/api/admin/support-requests?area=hwagok&limit=20")),
        fetch(apiUrl("/api/control/stream/status")),
        fetch(apiUrl("/api/mobile/location")),
      ]);

      const anyOk = [rR, hzR, sR].some((r) => r.ok);
      if (!anyOk) {
        if (backendAliveRef.current) { backendAliveRef.current = false; setStatus("백엔드 연결 실패. 서버를 확인해 주세요."); }
        return;
      }
      if (!backendAliveRef.current) { backendAliveRef.current = true; setStatus("백엔드에 재연결되었습니다."); }

      const [rd, hzd, sd, supd, stmd, locd] = await Promise.all([
        safeJson(rR), safeJson(hzR), safeJson(sR),
        safeJson(supR), safeJson(stmR), safeJson(locR),
      ]);

      if (rd)   { setRisks(rd.items || []);             drawRiskMarkers(rd.items || []); }
// 변경 후
if (hzd)  {
  const hazardItems = hzd.items || [];
  drawTactileHazards(hazardItems);
  prevHazardCountRef.current = hzd.count ?? hazardItems.length;
}
      if (supd) { setSupportRequests(supd.items || []); drawSupportRequests(supd.items || []); }

      if (stmd?.stream) {
        const prev = streamInfoRef.current;
        const next = stmd.stream;
        streamInfoRef.current = next;
        setStreamUiInfo({ ...next });
        setStreamLog(`백엔드: requested=${next.requested} active=${next.active}`);

        if (next.active && !prev.active && !streamActiveRef.current) {
          streamActiveRef.current = true;
          setStreamLog("앱 연결 감지. 스트림 시작.");
          startMjpeg();
        }
        if (!next.active && prev.active) {
          streamActiveRef.current = false;
          stopMjpeg();
          setStreamLog("앱 연결 끊김.");
        }
      }

      if (locd?.ok && locd.lat != null && mobileLocModeRef.current === "mobile") {
        const pt = { lat: locd.lat, lng: locd.lng };
        mobilePosRef.current = pt;
        const h = locd.heading ?? headingRef.current;
        updateOverlayRef.current(pt, h);
        if (!gpsCenteredRef.current) {
          gpsCenteredRef.current = true;
          if (mapRef.current) mapRef.current.setCenter(toLatLng(pt));
        }
      }

      if (sd) {
        setSummary(sd);
        const newCount = sd.mobileEmergencyLogCount ?? 0;
        if (prevEmergencyCountRef.current !== null && newCount > prevEmergencyCountRef.current) triggerDangerAlert();
        prevEmergencyCountRef.current = newCount;
      }
    } catch {
      if (backendAliveRef.current) { backendAliveRef.current = false; setStatus("백엔드 연결 실패"); }
    }
  }, [drawRiskMarkers, drawTactileHazards, drawSupportRequests, triggerDangerAlert, startMjpeg, stopMjpeg]);

  // ── 카카오맵 초기화 ───────────────────────────────────────
  useEffect(() => {
    if (!KAKAO_JS_KEY) { setStatus("VITE_KAKAO_JS_KEY가 없습니다."); return; }
    if (mapReadyRef.current) return;
    mapReadyRef.current = true;

    const script = document.createElement("script");
    script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${KAKAO_JS_KEY}&autoload=false&libraries=services`;
    script.async = true;

    script.onload = () => {
      window.kakao.maps.load(async () => {
        const map = new window.kakao.maps.Map(mapDivRef.current, { center: toLatLng(DEFAULT_POSITION), level: 3 });
        mapRef.current = map;
        updateOverlayRef.current(DEFAULT_POSITION, null);

        if (navigator.geolocation) {
          gpsWatchIdRef.current = navigator.geolocation.watchPosition(
            (pos) => {
              const point = { lat: pos.coords.latitude, lng: pos.coords.longitude };
              currentPosRef.current = point;
              setCurrentPosition(point);
              if (mobileLocModeRef.current === "browser") {
                updateOverlayRef.current(point, headingRef.current);
                if (!gpsCenteredRef.current) { gpsCenteredRef.current = true; map.setCenter(toLatLng(point)); }
              }
            },
            (err) => { if (!gpsErrLoggedRef.current) { gpsErrLoggedRef.current = true; console.info("[GPS]", err.message); } },
            { enableHighAccuracy: true, maximumAge: 2000, timeout: 12000 }
          );
        }

        const applyHeading = (h) => {
          headingRef.current = h;
          if (dotMarkerRef.current) {
            const pos = dotMarkerRef.current.getPosition();
            updateOverlayRef.current({ lat: pos.getLat(), lng: pos.getLng() }, h);
          }
        };
        const handleOrientation = (e) => {
          let h = null;
          if (e.webkitCompassHeading != null)    h = e.webkitCompassHeading;
          else if (e.absolute && e.alpha != null) h = 360 - e.alpha;
          else if (e.alpha != null)               h = 360 - e.alpha;
          applyHeading(h);
        };
        orientationHandlerRef.current = handleOrientation;
        if (typeof DeviceOrientationEvent?.requestPermission === "function") {
          DeviceOrientationEvent.requestPermission().then((s) => { if (s === "granted") window.addEventListener("deviceorientation", handleOrientation, true); }).catch(() => {});
        } else {
          if ("ondeviceorientationabsolute" in window) window.addEventListener("deviceorientationabsolute", handleOrientation, true);
          else window.addEventListener("deviceorientation", handleOrientation, true);
        }

        try {
          const res = await fetch(apiUrl("/api/tactile-blocks?area=hwagok"));
          const geoJson = await res.json();
          setTactileGeoJson(geoJson);
          drawTactileBlocks(geoJson);
          setStatus("관제 지도를 준비했습니다.");
        } catch { setStatus("점자블록 데이터를 불러오지 못했습니다."); }

        window.kakao.maps.event.addListener(map, "idle", fetchCrosswalks);
        fetchCrosswalks();

        const geocoder = new window.kakao.maps.services.Geocoder();
        window.kakao.maps.event.addListener(map, "click", (mouseEvent) => {
          const latlng = mouseEvent.latLng;
          const lat = latlng.getLat(), lng = latlng.getLng();
          geocoder.coord2Address(lng, lat, (result, status) => {
            let name = `${lat.toFixed(5)}, ${lng.toFixed(5)}`;
            if (status === window.kakao.maps.services.Status.OK && result[0]) {
              const addr = result[0].road_address?.address_name || result[0].address?.address_name || name;
              const building = result[0].road_address?.building_name || "";
              name = building ? `${building} (${addr})` : addr;
            }
            fetch(apiUrl("/api/set_destination"), {
              method: "POST", headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ area: "hwagok", destination: name, lat, lng, source: "control-web", mode: "navigation" }),
            }).catch(() => {});
            setDestinationState({ lat, lng, name });
            destinationNameRef.current = name;
            setStatus(`목적지 설정: ${name}`);
            if (destinationMarkerRef.current) destinationMarkerRef.current.setMap(null);
            const img = new window.kakao.maps.MarkerImage("https://t1.daumcdn.net/localimg/localimages/07/mapapidoc/marker_red.png", new window.kakao.maps.Size(31, 35));
            destinationMarkerRef.current = new window.kakao.maps.Marker({ map, position: latlng, title: name, image: img });
            if (calculateAndDrawRouteRef.current) calculateAndDrawRouteRef.current({ lat, lng }, name);
          });
        });
      });
    };
    document.head.appendChild(script);
    return () => {
      if (gpsWatchIdRef.current != null) navigator.geolocation?.clearWatch(gpsWatchIdRef.current);
      const handler = orientationHandlerRef.current;
      if (handler) {
        if ("ondeviceorientationabsolute" in window) window.removeEventListener("deviceorientationabsolute", handler, true);
        window.removeEventListener("deviceorientation", handler, true);
      }
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const id = window.setInterval(pollControlDestination, 2000);
    return () => window.clearInterval(id);
  }, [pollControlDestination]);

  useEffect(() => {
    refreshBackendData();
    const id = window.setInterval(refreshBackendData, 5000);
    return () => window.clearInterval(id);
  }, [refreshBackendData]);

  const isStreamActive  = streamUiInfo.active;
  const isStreamPending = streamUiInfo.requested && !streamUiInfo.active;

  return (
    <div className="control-shell" style={{ position:"relative", width:"100vw", height:"100vh", overflow:"hidden" }}>
      <div ref={mapDivRef} className="map" style={{ width:"100%", height:"100%", position:"absolute", top:0, left:0, zIndex:1 }} />

      <aside className="panel" style={{ position:"absolute", top:"20px", right:"20px", zIndex:10, maxHeight:"calc(100vh - 40px)", overflowY:"auto" }}>
        <div>
          <p className="eyebrow">누니 관제 웹</p>
          <h1>화곡 시범구역</h1>
          <p className="status">{status}</p>
        </div>
        <div className="metrics">
          <div><span>점자블록</span><strong>{summary?.tactileBlockCount ?? "-"}</strong></div>
          <div><span>위험 지점</span><strong>{summary?.riskCount ?? "-"}</strong></div>
          <div><span>점자 장애물</span><strong>{summary?.tactileHazardCount ?? "-"}</strong></div>
          <div><span>긴급 호출</span><strong>{summary?.mobileEmergencyLogCount ?? "-"}</strong></div>
        </div>

        {/* ── 마커 초기화 버튼 ── */}
        <section style={{ paddingBottom: 0 }}>
          <button
            type="button"
            onClick={clearAllMarkers}
            disabled={isClearing}
            style={{
              width: "100%", padding: "10px", borderRadius: "8px",
              border: "1.5px solid #ef4444",
              background: isClearing ? "#1f2937" : "rgba(239,68,68,0.1)",
              color: isClearing ? "#6b7280" : "#ef4444",
              fontWeight: "bold", cursor: isClearing ? "not-allowed" : "pointer",
              fontSize: "13px", display: "flex", alignItems: "center",
              justifyContent: "center", gap: "6px",
            }}
          >
            {isClearing ? "⏳ 초기화 중..." : "🗑 장애물 · 긴급 마커 초기화"}
          </button>
        </section>

        <section>
          <div className="section-title"><h2>현재 위치</h2></div>
          <div style={{ display:"flex", gap:"6px", marginBottom:"8px" }}>
            <button
              type="button"
              onClick={() => setLocMode("mobile")}
              style={{
                flex:1, padding:"6px 8px", borderRadius:"6px", fontSize:"11px",
                fontWeight:"bold", cursor:"pointer", border:"none",
                background: locMode === "mobile" ? "#2563EB" : "#1f2937",
                color: locMode === "mobile" ? "white" : "#9ca3af",
              }}
            >
              📱 앱 GPS
            </button>
            <button
              type="button"
              onClick={() => {
                setLocMode("browser");
                updateOverlayRef.current(currentPosRef.current, headingRef.current);
              }}
              style={{
                flex:1, padding:"6px 8px", borderRadius:"6px", fontSize:"11px",
                fontWeight:"bold", cursor:"pointer", border:"none",
                background: locMode === "browser" ? "#2563EB" : "#1f2937",
                color: locMode === "browser" ? "white" : "#9ca3af",
              }}
            >
              💻 노트북 GPS
            </button>
          </div>
          <p className="plain" style={{ fontSize:"11px", opacity:0.7 }}>
            {locMode === "mobile"
              ? mobilePosRef.current
                ? `앱 위치: ${mobilePosRef.current.lat.toFixed(5)}, ${mobilePosRef.current.lng.toFixed(5)}`
                : "앱 위치 수신 대기 중... (앱 실행 후 표시)"
              : `노트북: ${currentPosition.lat.toFixed(5)}, ${currentPosition.lng.toFixed(5)}`}
          </p>
        </section>

        <section>
          <div className="section-title"><h2>현재 목적지</h2></div>
          <p className="plain">
            {destination
              ? (<><span style={{ fontWeight:"bold", fontSize:"14px" }}>{destination.name}</span><br/><span style={{ fontSize:"11px", opacity:0.7 }}>{`${destination.lat.toFixed(5)}, ${destination.lng.toFixed(5)}`}</span></>)
              : "앱에서 목적지를 설정하거나 지도를 클릭하세요."}
          </p>
        </section>

        <section>
          <div className="section-title"><h2>추천 경로</h2><span>{routeCandidates.length}개</span></div>
          <div className="list">
            {routeCandidates.length === 0 && <p className="empty">아직 계산된 경로가 없습니다.</p>}
            {routeCandidates.map((c) => (
              <article className="item" key={c.id}>
                <b>{c.name}</b><p>{c.reason}</p>
                <div className="chips">
                  <span>{c.finalScore}점</span><span>{c.routeLengthMeter}m</span>
                  <span>점자 {c.tactileRatioPercent}%</span><span>횡단 {c.crosswalkCount}</span>
                </div>
              </article>
            ))}
          </div>
        </section>

        <section>
          <div className="section-title"><h2>지원 요청</h2><span>{supportRequests.filter((r) => r.status === "open").length} 활성</span></div>
          <div className="list compact">
            {supportRequests.length === 0 && <p className="empty">접수된 요청이 없습니다.</p>}
            {supportRequests.map((req) => (
              <article className="item" key={req.id} style={{ borderLeft:`4px solid ${req.type === "emergency" ? "#ef4444" : "#f59e0b"}` }}>
                <div style={{ display:"flex", justifyContent:"space-between", alignItems:"center", marginBottom:"4px" }}>
                  <span style={{ fontWeight:800, fontSize:"12px", color: req.type === "emergency" ? "#dc2626" : "#d97706" }}>{req.type === "emergency" ? "🔴 응급" : "🟡 도움"}</span>
                  <b style={{ fontSize:"13px" }}>{req.deviceId}</b>
                </div>
                <p style={{ margin:"4px 0", fontSize:"14px", fontWeight:"bold" }}>{req.message || "도움이 필요합니다."}</p>
                {req.status === "open" && (
                  <button onClick={() => resolveSupportRequest(req.id)} style={{ width:"100%", padding:"8px", marginTop:"8px", background:"#1f2937", color:"white", border:"none", borderRadius:"6px", cursor:"pointer", fontWeight:"bold" }}>
                    {req.type === "emergency" ? "119 출동 접수 완료" : "센터 지원 연결 완료"}
                  </button>
                )}
              </article>
            ))}
          </div>
        </section>

        <section>
          <div className="section-title"><h2>위험 지점</h2><span>{risks.filter((r) => r.status === "open").length} open</span></div>
          <div className="list compact">
            {risks.map((risk) => (<article className="item" key={risk.id}><b>{risk.title}</b><p>{risk.level} · {risk.status}</p></article>))}
          </div>
        </section>
      </aside>

      {dangerAlert && (
        <div style={{ position:"fixed", inset:0, zIndex:9999, pointerEvents:"none", animation:"nuni-danger-flash 0.4s ease-in-out infinite" }}>
          <div style={{ position:"absolute", top:"50%", left:"50%", transform:"translate(-50%,-50%)", color:"white", fontSize:"2.5rem", fontWeight:"900", textShadow:"0 2px 12px rgba(0,0,0,0.8)", userSelect:"none" }}>⚠️ 위험 감지</div>
        </div>
      )}

      {/* ── 스트림 패널 ── */}
      <div style={{ position:"absolute", bottom:"20px", left:"20px", zIndex:10, width:"24vw", background:"rgba(15,15,15,0.92)", padding:"12px", borderRadius:"12px", boxShadow:"0 8px 32px rgba(0,0,0,0.5)", color:"#fff", fontSize:"12px", display:"flex", flexDirection:"column", gap:"8px" }}>

        <div style={{ display:"flex", gap:"8px" }}>
          <button type="button" onClick={() => handleStreamRequest(true)}
                  style={{ flex:1, padding:"10px", borderRadius:"6px", border:"none",
                           background: isStreamActive ? "#16a34a" : isStreamPending ? "#ca8a04" : "#dc2626",
                           color:"white", fontWeight:"bold", cursor:"pointer", fontSize:"13px" }}>
            {isStreamActive ? "🟢 LIVE 중" : isStreamPending ? "⏳ 연결 대기" : "🎥 방송 요청"}
          </button>
          <button type="button" onClick={() => handleStreamRequest(false)}
                  style={{ padding:"10px 14px", borderRadius:"6px", border:"1px solid #555", background:"#333", color:"white", fontWeight:"bold", cursor:"pointer" }}>⏹</button>
          <button type="button" onClick={() => setStreamVisible(v => !v)}
                  style={{ padding:"10px 14px", borderRadius:"6px", border:"1px solid #555", background:"#333", color:"white", cursor:"pointer" }}>
            {streamVisible ? "🙈" : "👁"}
          </button>
        </div>

        <div style={{ fontSize:"10px", color:"#9ca3af", lineHeight:"1.4", padding:"4px 6px", background:"rgba(0,0,0,0.3)", borderRadius:"4px" }}>{streamLog}</div>

        {streamVisible && (
          <>
            <button type="button" onClick={startMjpeg}
                    style={{ padding:"6px", borderRadius:"4px", border:"1px solid #444", background:"#222", color:"#ccc", cursor:"pointer", fontSize:"11px" }}>
              🔄 화면 재연결 (스트림이 멈춘 경우)
            </button>
            <div style={{ width:"100%", aspectRatio:"4/3", background:"#000", borderRadius:"6px", overflow:"hidden", display:"flex", alignItems:"center", justifyContent:"center", position:"relative" }}>
              <canvas ref={canvasRef} style={{ width:"100%", height:"100%", objectFit:"cover", display: isStreamActive ? "block" : "none" }} />
              {!isStreamActive && (
                <span style={{ color:"#555", fontSize:"12px", textAlign:"center", padding:"16px", position:"absolute" }}>
                  '방송 요청'을 눌러 앱 카메라를 시작하세요
                </span>
              )}
            </div>
            <p style={{ margin:0, opacity:0.5, lineHeight:"1.4", fontSize:"10px" }}>
              앱이 /api/mobile/commands를 폴링해 명령을 수신합니다.
            </p>
          </>
        )}
      </div>
    </div>
  );
}