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

const safeJson = async (res) => {
  if (!res.ok) return null;
  try { return await res.json(); } catch { return null; }
};

function apiUrl(path) {
  if (API_BASE === "/api" && path.startsWith("/api")) return path;
  return `${API_BASE}${path}`;
}

// /video_feed 는 vite.config.js 프록시를 통해야 ngrok 헤더가 붙음
// VITE_API_BASE_URL=/api 환경이면 /video_feed 로 프록시 경유
// 외부 URL 환경이면 직접 붙임 (ngrok 경우 헤더 문제 발생 가능)
function videoFeedUrl(token) {  // [FIX] 항상 /video_feed 직접 사용 — apiUrl 경유 시 /api/video_feed 404 발생
  
  return `/video_feed?t=${token}`;
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
  const NS = "http://www.w3.org/2000/svg";
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
  const detectionMarkersRef   = useRef([]);
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

  const [status,          setStatus         ] = useState("관제 웹을 준비하고 있습니다.");
  const [currentPosition, setCurrentPosition] = useState(DEFAULT_POSITION);
  const [destination,     setDestinationState] = useState(null);
  const [routeCandidates, setRouteCandidates ] = useState([]);
  const [summary,         setSummary         ] = useState(null);
  const [risks,           setRisks           ] = useState([]);
  const [detections,      setDetections      ] = useState([]);
  const [supportRequests, setSupportRequests ] = useState([]);
  const [tactileGeoJson,  setTactileGeoJson  ] = useState(null);
  const [poiData,         setPoiData         ] = useState({ crosswalks: [], trafficLights: [] });
  const [dangerAlert,     setDangerAlert     ] = useState(false);
  const [streamVisible,   setStreamVisible   ] = useState(true);
  // streamInfo: { requested, active, pendingCommandCount, ... }
  const [streamInfo,      setStreamInfo      ] = useState({ requested: false, active: false });
  // streamToken: 바뀔 때마다 <img> src가 재로드되어 최신 프레임을 가져옴
  const [streamToken,     setStreamToken     ] = useState(Date.now());
  // streamLog: 디버깅용 — 스트림 관련 이벤트 기록
  const [streamLog,       setStreamLog       ] = useState("대기 중");

  useEffect(() => { currentPosRef.current = currentPosition; }, [currentPosition]);
  useEffect(() => {
    ensureGlobalStyles();
    return () => { if (dangerTimerRef.current) clearTimeout(dangerTimerRef.current); };
  }, []);

  const updateLocationOverlay = useCallback((point, heading) => {
    if (!mapRef.current) return;
    const latlng = toLatLng(point);
    if (!dotMarkerRef.current) {
      dotMarkerRef.current = new window.kakao.maps.Marker({ map: mapRef.current, position: latlng, image: buildDotMarkerImage(), zIndex: 10 });
    } else { dotMarkerRef.current.setPosition(latlng); }
    if (!pulseOverlayRef.current) {
      pulseOverlayRef.current = new window.kakao.maps.CustomOverlay({ map: mapRef.current, position: latlng, content: buildPulseOverlayDom(), zIndex: 9 });
    } else { pulseOverlayRef.current.setPosition(latlng); }
    if (heading !== null && heading !== undefined) {
      if (!fanOverlayRef.current) {
        const { svg, path } = buildFanOverlayDom();
        fanPathElemRef.current = path;
        fanOverlayRef.current = new window.kakao.maps.CustomOverlay({ map: mapRef.current, position: latlng, content: svg, zIndex: 8 });
      } else { fanOverlayRef.current.setPosition(latlng); }
      fanPathElemRef.current.setAttribute("d", calcFanPath(heading));
    } else if (fanOverlayRef.current) {
      fanOverlayRef.current.setMap(null);
      fanOverlayRef.current = null;
      fanPathElemRef.current = null;
    }
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

  const drawDetections = useCallback((items) => {
    if (!mapRef.current) return;
    clearMapObjects(detectionMarkersRef);
    const img = new window.kakao.maps.MarkerImage("https://t1.daumcdn.net/localimg/localimages/07/mapapidoc/markerStar.png", new window.kakao.maps.Size(24, 35));
    items.slice(0, 40).forEach((entry) => {
      const det = entry.detections?.[0];
      if (!det) return;
      const m = new window.kakao.maps.Marker({ map: mapRef.current, position: new window.kakao.maps.LatLng(entry.markerLat || entry.lat, entry.markerLng || entry.lng), title: det.label, image: img });
      const iw = new window.kakao.maps.InfoWindow({ content: `<div style="padding:8px;font-size:12px;min-width:180px"><b>AI 감지</b><br/>${det.label} ${Math.round(det.confidence * 100)}%<br/>${det.direction} · ${det.distanceLevel}</div>` });
      window.kakao.maps.event.addListener(m, "click", () => iw.open(mapRef.current, m));
      detectionMarkersRef.current.push(m);
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
    if (!tactileGeoJson) { setStatus("점자블록 데이터 준비 중입니다."); return; }
    setStatus(`${name} 경로를 계산하고 있습니다.`);
    setDestinationState({ ...point, name });
    setDestinationMarker(point, name);
    const pos = currentPosRef.current;
    const candidates = await buildRouteCandidates(pos, point, tactileGeoJson, poiData);
    setRouteCandidates(candidates);
    drawRouteCandidates(candidates);
    const best = candidates[0]; // [FIX] 오타 '최고' → 'best'
    if (best) {
      setStatus(`최적 경로 계산 완료: ${best.finalScore}점, ${best.routeLengthMeter}m`);
      await fetch(apiUrl("/api/control/route-candidates"), {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ area: "hwagok", destination: { name, ...point }, start: pos, candidates }),
      });
    }
  }, [drawRouteCandidates, poiData, setDestinationMarker, tactileGeoJson]);

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

  // ── 스트리밍 시작/중지 핵심 함수 ────────────────────────
  // 백엔드 /api/control/stream/start 또는 /stop 호출
  // → 앱이 /api/mobile/commands 폴링으로 명령 수신 → WebSocket /ws/stream 연결
  // → 백엔드가 latest_processed_frame 저장 → /video_feed 로 브라우저에 전송
  const handleStreamRequest = useCallback(async (start) => {
    const path = start ? "/api/control/stream/start" : "/api/control/stream/stop";
    setStreamLog(start ? "방송 요청 전송 중..." : "중지 요청 중...");
    try {
      const res = await fetch(apiUrl(path), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ area: "hwagok", deviceId: "demo-phone-01", requestedBy: "control-web" }),
      });
      if (res.ok) {
        const data = await res.json();
        const info = data.stream || {};
        setStreamInfo(info);
        if (start) {
          // 명령이 전달된 후 2초 뒤 img src를 갱신 (앱 반응 시간 고려)
          setTimeout(() => setStreamToken(Date.now()), 2000);
          setStreamLog(`명령 전송 완료. 앱 pending: ${info.pendingCommandCount ?? "?"}개`);
          setStatus("방송 요청을 앱으로 전송했습니다. 앱이 카메라를 켜면 자동으로 표시됩니다.");
        } else {
          setStreamToken(0); // src 초기화
          setStreamLog("중지됨");
          setStatus("스트리밍을 중지했습니다.");
        }
      } else {
        setStreamLog(`요청 실패: HTTP ${res.status}`);
        setStatus("스트림 제어 실패 — 백엔드를 확인하세요.");
      }
    } catch (err) {
      setStreamLog(`네트워크 오류: ${err.message}`);
      setStatus("백엔드에 연결할 수 없습니다.");
    }
  }, []);

  const refreshBackendData = useCallback(async () => {
    try {
      const [rR, dR, sR, supR, stmR] = await Promise.all([
        fetch(apiUrl("/api/risks?area=hwagok")),
        fetch(apiUrl("/api/admin/detections?area=hwagok&limit=50")),
        fetch(apiUrl("/api/admin/summary?area=hwagok")),
        fetch(apiUrl("/api/admin/support-requests?area=hwagok&limit=20")),
        fetch(apiUrl("/api/control/stream/status")),
      ]);

      const anyOk = [rR, dR, sR].some((r) => r.ok);
      if (!anyOk) {
        if (backendAliveRef.current) { backendAliveRef.current = false; setStatus("백엔드 연결 실패. 서버를 확인해 주세요."); }
        return;
      }
      if (!backendAliveRef.current) { backendAliveRef.current = true; setStatus("백엔드에 재연결되었습니다."); }

      const [rd, dd, sd, supd, stmd] = await Promise.all([safeJson(rR), safeJson(dR), safeJson(sR), safeJson(supR), safeJson(stmR)]);

      if (rd) { setRisks(rd.items || []); drawRiskMarkers(rd.items || []); }
      if (dd) { setDetections(dd.items || []); drawDetections(dd.items || []); }
      if (supd) { setSupportRequests(supd.items || []); drawSupportRequests(supd.items || []); }

      if (stmd?.stream) {
        const prev = streamInfo; // 이전 상태 참조
        const next = stmd.stream;
        setStreamInfo(next);
        setStreamLog(`백엔드: requested=${next.requested} active=${next.active} pending=${next.pendingCommandCount ?? 0}`);
        // 앱이 방금 연결됐을 때(active가 false→true) 자동으로 이미지 갱신
        if (next.active && !prev.active) {
          setStreamToken(Date.now());
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
  }, [drawDetections, drawRiskMarkers, drawSupportRequests, triggerDangerAlert, streamInfo]);

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
              updateOverlayRef.current(point, headingRef.current);
              if (!gpsCenteredRef.current) { gpsCenteredRef.current = true; map.setCenter(toLatLng(point)); }
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
          if (e.webkitCompassHeading != null)     h = e.webkitCompassHeading;
          else if (e.absolute && e.alpha != null)  h = 360 - e.alpha;
          else if (e.alpha != null)                h = 360 - e.alpha;
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

        // ── 지도 클릭 → 목적지 설정 ──────────────────────────
        // 클릭한 위치의 위경도를 카카오 역지오코딩으로 주소 변환 후 목적지로 설정
        const geocoder = new window.kakao.maps.services.Geocoder();
        window.kakao.maps.event.addListener(map, "click", (mouseEvent) => {
          const latlng = mouseEvent.latLng;
          const lat = latlng.getLat();
          const lng = latlng.getLng();

          // 역지오코딩으로 주소 가져오기
          geocoder.coord2Address(lng, lat, (result, status) => {
            let name = `${lat.toFixed(5)}, ${lng.toFixed(5)}`;
            if (status === window.kakao.maps.services.Status.OK && result[0]) {
              const addr = result[0].road_address?.address_name
                        || result[0].address?.address_name
                        || name;
              name = addr;
            }

            // 백엔드에 목적지 저장 (앱이 폴링으로 수신)
            fetch(apiUrl("/api/set_destination"), {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({
                area: "hwagok",
                destination: name,
                lat,
                lng,
                source: "control-web",
                mode: "navigation",
              }),
            }).catch(() => {});

            // 관제 웹 자체 UI 즉시 반영
            //updateOverlayRef.current({ lat, lng }, headingRef.current);
            setDestinationState({ lat, lng, name });
            destinationNameRef.current = name;
            setStatus(`목적지 설정: ${name}`);

            // 마커 표시
            if (destinationMarkerRef.current) destinationMarkerRef.current.setMap(null);
            const img = new window.kakao.maps.MarkerImage(
              "https://t1.daumcdn.net/localimg/localimages/07/mapapidoc/marker_red.png",
              new window.kakao.maps.Size(31, 35)
            );
            destinationMarkerRef.current = new window.kakao.maps.Marker({
              map, position: latlng, title: name, image: img,
            });
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
    const id = window.setInterval(refreshBackendData, 3000);
    return () => window.clearInterval(id);
  }, [refreshBackendData]);

  const isStreamActive   = streamInfo.active;
  const isStreamPending  = streamInfo.requested && !streamInfo.active;

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
          <div><span>앱 감지</span><strong>{summary?.mobileDetectionLogCount ?? "-"}</strong></div>
          <div><span>긴급 호출</span><strong>{summary?.mobileEmergencyLogCount ?? "-"}</strong></div>
        </div>

        <section>
          <div className="section-title"><h2>현재 목적지</h2></div>
          <p className="plain">
            {destination ? `${destination.name} (${destination.lat.toFixed(5)}, ${destination.lng.toFixed(5)})` : "앱에서 목적지를 설정하면 표시됩니다."}
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
          <div className="section-title">
            <h2>지원 요청</h2>
            <span>{supportRequests.filter((r) => r.status === "open").length} 활성</span>
          </div>
          <div className="list compact">
            {supportRequests.length === 0 && <p className="empty">접수된 요청이 없습니다.</p>}
            {supportRequests.map((req) => (
              <article className="item" key={req.id} style={{ borderLeft: `4px solid ${req.type === "emergency" ? "#ef4444" : "#f59e0b"}` }}>
                <div style={{ display:"flex", justifyContent:"space-between", alignItems:"center", marginBottom:"4px" }}>
                  <span style={{ fontWeight:800, fontSize:"12px", color: req.type === "emergency" ? "#dc2626" : "#d97706" }}>
                    {req.type === "emergency" ? "🔴 응급" : "🟡 도움"}
                  </span>
                  <b style={{ fontSize:"13px" }}>{req.deviceId}</b>
                </div>
                <p style={{ margin:"4px 0", fontSize:"14px", fontWeight:"bold" }}>{req.message || "도움이 필요합니다."}</p>
                {req.status === "open" && (
                  <button onClick={() => resolveSupportRequest(req.id)}
                          style={{ width:"100%", padding:"8px", marginTop:"8px", background:"#1f2937", color:"white", border:"none", borderRadius:"6px", cursor:"pointer", fontWeight:"bold" }}>
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
            {risks.map((risk) => (
              <article className="item" key={risk.id}><b>{risk.title}</b><p>{risk.level} · {risk.status}</p></article>
            ))}
          </div>
        </section>
      </aside>

      {dangerAlert && (
        <div style={{ position:"fixed", inset:0, zIndex:9999, pointerEvents:"none", animation:"nuni-danger-flash 0.4s ease-in-out infinite" }}>
          <div style={{ position:"absolute", top:"50%", left:"50%", transform:"translate(-50%,-50%)", color:"white", fontSize:"2.5rem", fontWeight:"900", textShadow:"0 2px 12px rgba(0,0,0,0.8)", userSelect:"none" }}>
            ⚠️ 위험 감지
          </div>
        </div>
      )}

      {/* ── 스트림 패널 ── */}
      <div style={{ position:"absolute", bottom:"20px", left:"20px", zIndex:10, width:"24vw", background:"rgba(15,15,15,0.9)", padding:"12px", borderRadius:"12px", boxShadow:"0 8px 32px rgba(0,0,0,0.5)", color:"#fff", fontSize:"12px", display:"flex", flexDirection:"column", gap:"8px" }}>

        {/* 버튼 행 */}
        <div style={{ display:"flex", gap:"8px" }}>
          <button type="button" onClick={() => handleStreamRequest(true)}
                  style={{ flex:1, padding:"10px", borderRadius:"6px", border:"none",
                           background: isStreamActive ? "#16a34a" : isStreamPending ? "#ca8a04" : "#dc2626",
                           color:"white", fontWeight:"bold", cursor:"pointer", fontSize:"13px" }}>
            {isStreamActive ? "🟢 LIVE 중" : isStreamPending ? "⏳ 연결 대기" : "🎥 방송 요청"}
          </button>
          <button type="button" onClick={() => handleStreamRequest(false)}
                  style={{ padding:"10px 14px", borderRadius:"6px", border:"1px solid #555", background:"#333", color:"white", fontWeight:"bold", cursor:"pointer" }}>
            ⏹
          </button>
          <button type="button" onClick={() => setStreamVisible(v => !v)}
                  style={{ padding:"10px 14px", borderRadius:"6px", border:"1px solid #555", background:"#333", color:"white", cursor:"pointer" }}>
            {streamVisible ? "🙈" : "👁"}
          </button>
        </div>

        {/* 디버그 로그 — 스트림 상태를 실시간으로 확인 */}
        <div style={{ fontSize:"10px", color:"#9ca3af", lineHeight:"1.4", padding:"4px 6px", background:"rgba(0,0,0,0.3)", borderRadius:"4px" }}>
          {streamLog}
        </div>

        {streamVisible && (
          <>
            {/* 화면 새로고침 버튼 */}
            <button type="button" onClick={() => setStreamToken(Date.now())}
                    style={{ padding:"6px", borderRadius:"4px", border:"1px solid #444", background:"#222", color:"#ccc", cursor:"pointer", fontSize:"11px" }}>
              🔄 화면 새로고침 (스트림이 멈춘 경우)
            </button>

            <div style={{ width:"100%", aspectRatio:"4/3", background:"#000", borderRadius:"6px", overflow:"hidden", display:"flex", alignItems:"center", justifyContent:"center" }}>
              {streamToken > 0 ? (
                // [핵심] vite.config.js의 /video_feed 프록시를 경유해야 ngrok 헤더가 자동으로 붙음
                // ?t= 파라미터로 브라우저 캐시 무력화
                <img
                  key={streamToken}  // key 변경 시 DOM 재생성 → 완전히 새 요청
                  src={videoFeedUrl(streamToken)}
                  alt="스마트폰 카메라 스트림"
                  style={{ width:"100%", height:"100%", objectFit:"cover" }}
                />
              ) : (
                <span style={{ color:"#555", fontSize:"12px", textAlign:"center", padding:"16px" }}>
                  '방송 요청'을 눌러 앱 카메라를 시작하세요
                </span>
              )}
            </div>

            <p style={{ margin:0, opacity:0.55, lineHeight:"1.4", fontSize:"10px" }}>
              앱이 /api/mobile/commands를 폴링하여 명령을 수신합니다.<br/>
              앱 연결 후 화면이 멈추면 🔄 새로고침을 누르세요.
            </p>
          </>
        )}
      </div>
    </div>
  );
}