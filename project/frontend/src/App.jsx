import { useCallback, useEffect, useRef, useState } from "react";
import { buildRouteCandidates } from "./routeEngine";
import warningIcon from './assets/warning.png';

// ── 환경 변수 ──────────────────────────────────────────────
const API_BASE       = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");
const KAKAO_JS_KEY   = import.meta.env.VITE_KAKAO_JS_KEY  || "";
const KAKAO_REST_KEY = import.meta.env.VITE_KAKAO_REST_KEY || "";
const DEFAULT_POSITION = { lat: 37.5421, lng: 126.841306 };

// ── 컴포넌트 밖으로 뺀 상수/순수 유틸 ────────────────────
// 렌더마다 재생성되지 않도록 모듈 스코프에 선언
const OVERPASS_SERVERS = [
  "https://overpass-api.de/api/interpreter",
  "https://lz4.overpass-api.de/api/interpreter",
  "https://z.overpass-api.de/api/interpreter",
];

// [FIX] safeJson을 컴포넌트 밖으로 이동 — 렌더마다 재생성 방지
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

// ── pulse 애니메이션 style 주입 (최초 1회) ────────────────
function ensurePulseStyle() {
  if (document.getElementById("nuni-pulse-style")) return;
  const s = document.createElement("style");
  s.id = "nuni-pulse-style";
  s.textContent = `
    @keyframes nuni-pulse {
      0%   { transform:scale(1);   opacity:0.55; }
      70%  { transform:scale(2.8); opacity:0;    }
      100% { transform:scale(2.8); opacity:0;    }
    }
    .nuni-ring {
      width:26px; height:26px;
      border-radius:50%;
      background:rgba(37,99,235,0.5);
      animation:nuni-pulse 2s ease-out infinite;
      pointer-events:none;
    }
  `;
  document.head.appendChild(s);
}

// ── 파란 원 마커 이미지 (SVG → Data URL → MarkerImage) ────
function buildDotMarkerImage() {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="22" height="22" viewBox="0 0 22 22">
    <circle cx="11" cy="11" r="10" fill="white"/>
    <circle cx="11" cy="11" r="7"  fill="#2563EB"/>
  </svg>`;
  const url  = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svg);
  const size = new window.kakao.maps.Size(22, 22);
  const opt  = new window.kakao.maps.Point(11, 11);
  return new window.kakao.maps.MarkerImage(url, size, { offset: opt });
}

// ── pulse 링 CustomOverlay DOM ─────────────────────────────
function buildPulseOverlayDom() {
  ensurePulseStyle();
  const wrap = document.createElement("div");
  wrap.style.cssText = "position:absolute;transform:translate(-50%,-50%);pointer-events:none;";
  const ring = document.createElement("div");
  ring.className = "nuni-ring";
  wrap.appendChild(ring);
  return wrap;
}

// ── 부채꼴 CustomOverlay DOM ───────────────────────────────
function buildFanOverlayDom() {
  const NS  = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(NS, "svg");
  svg.setAttribute("width", "90"); svg.setAttribute("height", "90");
  svg.setAttribute("viewBox", "0 0 90 90");
  svg.style.cssText = "position:absolute;transform:translate(-50%,-50%);overflow:visible;pointer-events:none;";

  const path = document.createElementNS(NS, "path");
  path.setAttribute("fill",         "rgba(37,99,235,0.2)");
  path.setAttribute("stroke",       "rgba(37,99,235,0.5)");
  path.setAttribute("stroke-width", "1.5");
  svg.appendChild(path);
  return { svg, path };
}

// ── 부채꼴 path 계산 ───────────────────────────────────────
function calcFanPath(heading) {
  const r = 38, cx = 45, cy = 45, sweep = 60;
  const rad  = (d) => (d * Math.PI) / 180;
  const base = heading - 90;
  const x1 = cx + r * Math.cos(rad(base - sweep / 2));
  const y1 = cy + r * Math.sin(rad(base - sweep / 2));
  const x2 = cx + r * Math.cos(rad(base + sweep / 2));
  const y2 = cy + r * Math.sin(rad(base + sweep / 2));
  return `M${cx},${cy} L${x1},${y1} A${r},${r} 0 0,1 ${x2},${y2} Z`;
}

// ── 컴포넌트 ───────────────────────────────────────────────
export default function App() {
  const mapDivRef            = useRef(null);
  const mapRef               = useRef(null);
  const mapReadyRef          = useRef(false);
  const dotMarkerRef         = useRef(null);
  const pulseOverlayRef      = useRef(null);
  const fanOverlayRef        = useRef(null);
  const fanPathElemRef       = useRef(null);
  const destinationMarkerRef = useRef(null);
  const routeLinesRef        = useRef([]);
  const tactileLinesRef      = useRef([]);
  const riskMarkersRef       = useRef([]);
  const detectionMarkersRef  = useRef([]);
  const poiOverlaysRef       = useRef([]);
  const destinationNameRef   = useRef("");
  const backendAliveRef      = useRef(true);
  const backendRetryTimerRef = useRef(null);
  const headingRef           = useRef(null);
  const gpsWatchIdRef        = useRef(null);
  const gpsCenteredRef       = useRef(false);
  const gpsErrLoggedRef      = useRef(false);
  const currentPosRef        = useRef(DEFAULT_POSITION);
  // [FIX] orientationHandler를 ref로 보관 — cleanup 시 정확히 같은 참조로 제거
  const orientationHandlerRef = useRef(null);

  const [status,          setStatus         ] = useState("관제 웹을 준비하고 있습니다.");
  const [currentPosition, setCurrentPosition] = useState(DEFAULT_POSITION);
  const [destination,     setDestinationState] = useState(null);
  const [routeCandidates, setRouteCandidates ] = useState([]);
  const [summary,         setSummary         ] = useState(null);
  const [risks,           setRisks           ] = useState([]);
  const [detections,      setDetections      ] = useState([]);
  const [tactileGeoJson,  setTactileGeoJson  ] = useState(null);
  const [poiData,         setPoiData         ] = useState({ crosswalks: [], trafficLights: [] });
  const [streamVisible,   setStreamVisible   ] = useState(true);
  const [dangerAlert,     setDangerAlert     ] = useState(false);
  const dangerTimerRef = useRef(null);

  useEffect(() => { currentPosRef.current = currentPosition; }, [currentPosition]);

  // ── 현위치 마커/오버레이 갱신 ───────────────────────────
  const updateLocationOverlay = useCallback((point, heading) => {
    if (!mapRef.current) return;
    const latlng = toLatLng(point);

    if (!dotMarkerRef.current) {
      dotMarkerRef.current = new window.kakao.maps.Marker({
        map:      mapRef.current,
        position: latlng,
        image:    buildDotMarkerImage(),
        zIndex:   10,
      });
    } else {
      dotMarkerRef.current.setPosition(latlng);
    }

    if (!pulseOverlayRef.current) {
      pulseOverlayRef.current = new window.kakao.maps.CustomOverlay({
        map:      mapRef.current,
        position: latlng,
        content:  buildPulseOverlayDom(),
        zIndex:   9,
      });
    } else {
      pulseOverlayRef.current.setPosition(latlng);
    }

    if (heading !== null && heading !== undefined) {
      if (!fanOverlayRef.current) {
        const { svg, path } = buildFanOverlayDom();
        fanPathElemRef.current = path;
        fanOverlayRef.current  = new window.kakao.maps.CustomOverlay({
          map:      mapRef.current,
          position: latlng,
          content:  svg,
          zIndex:   8,
        });
      } else {
        fanOverlayRef.current.setPosition(latlng);
      }
      fanPathElemRef.current.setAttribute("d", calcFanPath(heading));
    } else if (fanOverlayRef.current) {
      fanOverlayRef.current.setMap(null);
      fanOverlayRef.current  = null;
      fanPathElemRef.current = null;
    }
  }, []);

  const updateOverlayRef = useRef(updateLocationOverlay);
  useEffect(() => { updateOverlayRef.current = updateLocationOverlay; }, [updateLocationOverlay]);

  // ── 헬퍼 ──────────────────────────────────────────────────
  // [FIX] useCallback으로 감싸서 의존성 배열에서 안전하게 참조 가능하도록
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
      tactileLinesRef.current.push(new window.kakao.maps.Polyline({
        map: mapRef.current, path, strokeWeight: 6, strokeColor: "#FFD700", strokeOpacity: 0.78,
      }));
    });
  }, [clearMapObjects]);

  const drawRiskMarkers = useCallback((items) => {
    if (!mapRef.current) return;
    clearMapObjects(riskMarkersRef);
    const img = new window.kakao.maps.MarkerImage(warningIcon, new window.kakao.maps.Size(32, 32));
    items.forEach((risk) => {
      const m = new window.kakao.maps.Marker({
        map: mapRef.current, position: new window.kakao.maps.LatLng(risk.lat, risk.lng), image: img,
      });
      const iw = new window.kakao.maps.InfoWindow({
        content: `<div style="padding:8px;font-size:12px;min-width:150px"><b>${risk.title}</b><br/>${risk.level} · ${risk.status}</div>`,
      });
      window.kakao.maps.event.addListener(m, "click", () => iw.open(mapRef.current, m));
      riskMarkersRef.current.push(m);
    });
  }, [clearMapObjects]);

  const drawDetections = useCallback((items) => {
    if (!mapRef.current) return;
    clearMapObjects(detectionMarkersRef);
    const img = new window.kakao.maps.MarkerImage(
      "https://t1.daumcdn.net/localimg/localimages/07/mapapidoc/markerStar.png",
      new window.kakao.maps.Size(24, 35)
    );
    items.slice(0, 40).forEach((entry) => {
      const det = entry.detections?.[0];
      if (!det) return;
      const m = new window.kakao.maps.Marker({
        map: mapRef.current,
        position: new window.kakao.maps.LatLng(entry.markerLat || entry.lat, entry.markerLng || entry.lng),
        title: det.label, image: img,
      });
      const iw = new window.kakao.maps.InfoWindow({
        content: `<div style="padding:8px;font-size:12px;min-width:180px"><b>AI 감지</b><br/>${det.label} ${Math.round(det.confidence * 100)}%<br/>${det.direction} · ${det.distanceLevel}</div>`,
      });
      window.kakao.maps.event.addListener(m, "click", () => iw.open(mapRef.current, m));
      detectionMarkersRef.current.push(m);
    });
  }, [clearMapObjects]);

  const drawCrosswalks = useCallback((crosswalks) => {
    if (!mapRef.current) return;
    clearMapObjects(poiOverlaysRef);
    crosswalks.forEach((p) => {
      poiOverlaysRef.current.push(new window.kakao.maps.Circle({
        center: new window.kakao.maps.LatLng(p.lat, p.lng),
        radius: 8, strokeColor: "#00E676", strokeOpacity: 0.8,
        strokeWeight: 2, fillColor: "#00E676", fillOpacity: 0.36,
        map: mapRef.current,
      }));
    });
  }, [clearMapObjects]);

  const drawRouteCandidates = useCallback((candidates) => {
    if (!mapRef.current) return;
    clearMapObjects(routeLinesRef);
    candidates.slice(1).forEach((c) => {
      const path = c.coords.map(([lng, lat]) => new window.kakao.maps.LatLng(lat, lng));
      routeLinesRef.current.push(new window.kakao.maps.Polyline({
        map: mapRef.current, path, strokeWeight: 5, strokeColor: "#8A8A8A", strokeOpacity: 0.5,
      }));
    });
    const best = candidates[0];
    if (!best) return;
    const path = best.coords.map(([lng, lat]) => new window.kakao.maps.LatLng(lat, lng));
    routeLinesRef.current.push(new window.kakao.maps.Polyline({
      map: mapRef.current, path, strokeWeight: 8, strokeColor: "#2563EB", strokeOpacity: 0.95,
    }));
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
        const crosswalks = data.elements
          .map((el) => ({ lat: el.lat || el.center?.lat, lng: el.lon || el.center?.lon }))
          .filter((p) => p.lat && p.lng);
        setPoiData({ crosswalks, trafficLights: [] });
        drawCrosswalks(crosswalks);
        return;
      } catch { /* 다음 서버 */ }
    }
    console.info("[Overpass] 모든 서버 응답 없음");
  }, [drawCrosswalks]);

  const setDestinationMarker = useCallback((point, name) => {
    if (!mapRef.current) return;
    if (destinationMarkerRef.current) destinationMarkerRef.current.setMap(null);
    const img = new window.kakao.maps.MarkerImage(
      "https://t1.daumcdn.net/localimg/localimages/07/mapapidoc/marker_red.png",
      new window.kakao.maps.Size(31, 35)
    );
    destinationMarkerRef.current = new window.kakao.maps.Marker({
      map: mapRef.current, position: toLatLng(point), title: name, image: img,
    });
  }, []);

  const geocodeByKeyword = useCallback(async (keyword) => {
    if (!KAKAO_REST_KEY) throw new Error("VITE_KAKAO_REST_KEY가 없습니다.");
    // [FIX] currentPosition state 대신 currentPosRef.current 사용 — stale closure 방지
    const pos = currentPosRef.current;
    const q = new URLSearchParams({
      query: keyword, x: String(pos.lng),
      y: String(pos.lat), radius: "2000", sort: "distance",
    });
    const res = await fetch(`https://dapi.kakao.com/v2/local/search/keyword.json?${q}`, {
      headers: { Authorization: `KakaoAK ${KAKAO_REST_KEY}` },
    });
    const data = await res.json();
    const first = data.documents?.[0];
    if (!first) throw new Error(`목적지 후보를 찾지 못했습니다: ${keyword}`);
    return { name: first.place_name || keyword, lat: Number(first.y), lng: Number(first.x) };
  // [FIX] currentPosition 의존성 제거 — ref로 교체했으므로 불필요
  }, []);

  const calculateAndDrawRoute = useCallback(async (point, name) => {
    if (!tactileGeoJson) { setStatus("점자블록 데이터 준비 중입니다."); return; }
    setStatus(`${name} 경로를 계산하고 있습니다.`);
    setDestinationState({ ...point, name });
    setDestinationMarker(point, name);
    // [FIX] currentPosition state 대신 currentPosRef.current 사용 — stale closure 방지
    const pos = currentPosRef.current;
    const candidates = await buildRouteCandidates(pos, point, tactileGeoJson, poiData);
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
  // [FIX] currentPosition 의존성 제거
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

  // ── 위험 감지: 3초간 빨간 화면 깜빡임 ──────────────────
  const triggerDangerAlert = useCallback(() => {
    setDangerAlert(true);
    if (dangerTimerRef.current) clearTimeout(dangerTimerRef.current);
    dangerTimerRef.current = setTimeout(() => {
      setDangerAlert(false);
      dangerTimerRef.current = null;
    }, 3000);
  }, []);

  // [FIX] dangerTimerRef cleanup 추가 — 언마운트 시 타이머 누수 방지
  useEffect(() => {
    return () => {
      if (dangerTimerRef.current) clearTimeout(dangerTimerRef.current);
    };
  }, []);

  // ── 관제 웹 → 앱 스트리밍 시작 명령 ─────────────────────
  const startStreamingFromWeb = useCallback(async () => {
    try {
      const res = await fetch(apiUrl("/api/control/streaming"), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ command: "start", area: "hwagok" }),
      });
      if (res.ok) {
        setStatus("스트리밍 시작 명령을 전송했습니다.");
      } else {
        setStatus("스트리밍 명령 전송 실패 (백엔드 확인 필요)");
      }
    } catch {
      setStatus("백엔드 서버에 연결할 수 없습니다.");
    }
  }, []);

  const refreshBackendData = useCallback(async () => {
    try {
      const [rR, dR, sR] = await Promise.all([
        fetch(apiUrl("/api/risks?area=hwagok")),
        fetch(apiUrl("/api/admin/detections?area=hwagok&limit=50")),
        fetch(apiUrl("/api/admin/summary?area=hwagok")),
      ]);

      const anyOk = [rR, dR, sR].some((r) => r.ok);
      if (!anyOk) {
        if (backendAliveRef.current) {
          backendAliveRef.current = false;
          setStatus("백엔드 서버에 연결할 수 없습니다. 서버를 실행해 주세요.");
        }
        return;
      }

      if (!backendAliveRef.current) {
        backendAliveRef.current = true;
        setStatus("백엔드 서버에 재연결되었습니다.");
      }

      const [rd, dd, sd] = await Promise.all([safeJson(rR), safeJson(dR), safeJson(sR)]);
      if (rd) { setRisks(rd.items || []);       drawRiskMarkers(rd.items || []); }
      if (dd) { setDetections(dd.items || []);  drawDetections(dd.items || []); }
      if (sd) {
        setSummary(sd);
        if (sd.dangerAlert) triggerDangerAlert();
      }
    } catch {
      if (backendAliveRef.current) {
        backendAliveRef.current = false;
        setStatus("백엔드 서버에 연결할 수 없습니다. 서버를 실행해 주세요.");
      }
    }
  // [FIX] triggerDangerAlert 의존성 추가
  }, [drawDetections, drawRiskMarkers, triggerDangerAlert]);

  // ── 카카오맵 초기화 ───────────────────────────────────────
  useEffect(() => {
    if (!KAKAO_JS_KEY) { setStatus("VITE_KAKAO_JS_KEY가 없습니다."); return; }
    if (mapReadyRef.current) return;
    mapReadyRef.current = true;

    const script = document.createElement("script");
    script.src   = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${KAKAO_JS_KEY}&autoload=false&libraries=services`;
    script.async = true;

    script.onload = () => {
      window.kakao.maps.load(async () => {
        const map = new window.kakao.maps.Map(mapDivRef.current, {
          center: toLatLng(DEFAULT_POSITION), level: 3,
        });
        mapRef.current = map;

        updateOverlayRef.current(DEFAULT_POSITION, null);

        // GPS
        if (navigator.geolocation) {
          gpsWatchIdRef.current = navigator.geolocation.watchPosition(
            (pos) => {
              const point = { lat: pos.coords.latitude, lng: pos.coords.longitude };
              currentPosRef.current = point;
              setCurrentPosition(point);
              updateOverlayRef.current(point, headingRef.current);
              if (!gpsCenteredRef.current) {
                gpsCenteredRef.current = true;
                map.setCenter(toLatLng(point));
              }
            },
            (err) => {
              if (err.code === 1) setStatus("위치 권한이 없습니다. 주소창 🔒 → 위치 허용 후 새로고침하세요.");
              if (!gpsErrLoggedRef.current) {
                gpsErrLoggedRef.current = true;
                console.info("[GPS]", err.message, `(code ${err.code})`);
              }
            },
            { enableHighAccuracy: true, maximumAge: 2000, timeout: 12000 }
          );
        } else {
          setStatus("이 브라우저는 위치 서비스를 지원하지 않습니다.");
        }

        // ── 나침반 ──────────────────────────────────────────
        const applyHeading = (h) => {
          headingRef.current = h;
          if (dotMarkerRef.current) {
            const pos = dotMarkerRef.current.getPosition();
            updateOverlayRef.current({ lat: pos.getLat(), lng: pos.getLng() }, h);
          }
        };

        const handleOrientation = (e) => {
          let h = null;
          if (e.webkitCompassHeading != null) {
            h = e.webkitCompassHeading;
          } else if (e.absolute && e.alpha != null) {
            h = 360 - e.alpha;
          } else if (e.alpha != null) {
            h = 360 - e.alpha;
          }
          applyHeading(h);
        };

        // [FIX] ref에 저장해서 cleanup 시 동일 참조로 removeEventListener 가능하게
        orientationHandlerRef.current = handleOrientation;

        if (typeof DeviceOrientationEvent?.requestPermission === "function") {
          DeviceOrientationEvent.requestPermission()
            .then((s) => {
              if (s === "granted") {
                window.addEventListener("deviceorientation", handleOrientation, true);
              }
            })
            .catch(() => {});
        } else {
          if ("ondeviceorientationabsolute" in window) {
            window.addEventListener("deviceorientationabsolute", handleOrientation, true);
          } else {
            window.addEventListener("deviceorientation", handleOrientation, true);
          }
        }

        // 점자블록
        try {
          const res = await fetch(apiUrl("/api/tactile-blocks?area=hwagok"));
          const geoJson = await res.json();
          setTactileGeoJson(geoJson);
          drawTactileBlocks(geoJson);
          setStatus("관제 지도를 준비했습니다.");
        } catch {
          setStatus("점자블록 데이터를 불러오지 못했습니다.");
        }

        window.kakao.maps.event.addListener(map, "idle", fetchCrosswalks);
        fetchCrosswalks();
      });
    };

    document.head.appendChild(script);

    return () => {
      // GPS 해제
      if (gpsWatchIdRef.current != null)
        navigator.geolocation?.clearWatch(gpsWatchIdRef.current);

      // [FIX] orientation 이벤트 리스너 해제 — 원본에서 누락됐던 cleanup
      const handler = orientationHandlerRef.current;
      if (handler) {
        if ("ondeviceorientationabsolute" in window) {
          window.removeEventListener("deviceorientationabsolute", handler, true);
        }
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

  // ── JSX ───────────────────────────────────────────────────
  return (
    <div className="control-shell"
         style={{ position:"relative", width:"100vw", height:"100vh", overflow:"hidden" }}>

      <div ref={mapDivRef} className="map"
           style={{ width:"100%", height:"100%", position:"absolute", top:0, left:0, zIndex:1 }} />

      <aside className="panel"
             style={{ position:"absolute", top:"20px", right:"20px", zIndex:10,
                      maxHeight:"calc(100vh - 40px)", overflowY:"auto" }}>
        <div>
          <p className="eyebrow">누니 관제 웹</p>
          <h1>화곡 시범구역</h1>
          <p className="status">{status}</p>
        </div>
        <div className="metrics">
          <div><span>점자블록</span><strong>{summary?.tactileBlockCount ?? "-"}</strong></div>
          <div><span>위험 지점</span><strong>{summary?.riskCount ?? "-"}</strong></div>
          <div><span>앱 감지</span><strong>{summary?.mobileDetectionLogCount ?? "-"}</strong></div>
        </div>
        <section>
          <div className="section-title"><h2>현재 목적지</h2></div>
          <p className="plain">
            {destination
              ? `${destination.name} (${destination.lat.toFixed(5)}, ${destination.lng.toFixed(5)})`
              : "앱에서 목적지를 설정하면 표시됩니다."}
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
            <h2>위험 지점</h2>
            <span>{risks.filter((r) => r.status === "open").length} open</span>
          </div>
          <div className="list compact">
            {risks.map((risk) => (
              <article className="item" key={risk.id}>
                <b>{risk.title}</b><p>{risk.level} · {risk.status}</p>
              </article>
            ))}
          </div>
        </section>
      </aside>

      {/* ── 위험 감지 빨간 오버레이 ── */}
      {dangerAlert && (
        <div style={{
          position:"fixed", inset:0, zIndex:9999, pointerEvents:"none",
          animation:"nuni-danger-flash 0.4s ease-in-out infinite",
        }}>
          <style>{`
            @keyframes nuni-danger-flash {
              0%,100% { background:rgba(220,38,38,0); }
              50%     { background:rgba(220,38,38,0.45); }
            }
          `}</style>
          <div style={{
            position:"absolute", top:"50%", left:"50%",
            transform:"translate(-50%,-50%)",
            color:"white", fontSize:"2.5rem", fontWeight:"900",
            textShadow:"0 2px 12px rgba(0,0,0,0.8)",
            letterSpacing:"0.1em", userSelect:"none",
          }}>⚠️ 위험 감지</div>
        </div>
      )}

      {/* ── 좌측 하단: 카메라 스트림 패널 ── */}
      <div className={`stream ${streamVisible ? "" : "collapsed"}`}
           style={{ position:"absolute", bottom:"20px", left:"20px", zIndex:10,
                    width:"16vw", background:"rgba(20,20,20,0.85)", padding:"10px",
                    borderRadius:"12px", boxShadow:"0 8px 32px rgba(0,0,0,0.37)",
                    color:"#fff", fontSize:"11px" }}>

        <button type="button" onClick={() => setStreamVisible((v) => !v)}
                style={{ width:"100%", padding:"6px", marginBottom:"6px",
                         borderRadius:"6px", border:"none", backgroundColor:"#2563EB",
                         color:"white", fontWeight:"bold", cursor:"pointer" }}>
          {streamVisible ? "스트림 숨기기" : "스트림 보기"}
        </button>

        <button type="button" onClick={startStreamingFromWeb}
                style={{ width:"100%", padding:"6px", marginBottom: streamVisible ? "8px" : "0",
                         borderRadius:"6px", border:"1.5px solid #FBBF24",
                         backgroundColor:"rgba(251,191,36,0.15)", color:"#FBBF24",
                         fontWeight:"bold", cursor:"pointer", fontSize:"11px" }}>
          📡 스트리밍 시작 (앱 자동 실행)
        </button>

        {streamVisible && (
          <>
            <div style={{ fontWeight:"bold", marginBottom:"6px", fontSize:"12px", color:"#FBBF24" }}>
              현장 스마트폰 시점
            </div>
            <img src={apiUrl("/video_feed")} alt="YOLO stream"
                 style={{ width:"100%", height:"auto", borderRadius:"6px", display:"block", marginBottom:"6px" }} />
            <p style={{ margin:0, opacity:0.7, lineHeight:"1.4" }}>
              또는 앱에서 "누니야 스트리밍 모드 켜줘"
            </p>
          </>
        )}
      </div>
    </div>
  );
}