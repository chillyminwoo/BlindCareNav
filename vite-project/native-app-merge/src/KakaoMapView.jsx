import { useEffect, useRef, useState } from "react";
import * as turf from "@turf/turf";
import { fetchTmapRoute } from "./engine/routeEngine"; 
import {
  API_BASE_URL,
  STREAM_BASE_URL,
  getAdminDetections,
  getAdminSummary,
  getRisks,
} from "./services/api";

const AREA = "hwagok";
const KAKAO_JS_KEY = import.meta.env.VITE_KAKAO_JS_KEY;
const KAKAO_REST_KEY = import.meta.env.VITE_KAKAO_REST_KEY;

export default function KakaoMapView() {
  const mapRef = useRef(null);
  const mapDivRef = useRef(null);

  const myMarkerRef = useRef(null);
  const destMarkerRef = useRef(null);
  const routePolylineRefs = useRef([]);
  const tactilePolylineRefs = useRef([]);
  const poiRefs = useRef([]);
  const riskMarkerRefs = useRef([]);
  const detectionMarkerRefs = useRef([]);
  const markerInfoWindowRef = useRef(null);

  const myPosRef = useRef(null);
  const destPosRef = useRef(null);
  const tactileGeoJsonRef = useRef(null);
  
  // 💡 신호등(trafficLights)은 사용하지 않으므로 배열을 비워둡니다.
  const currentPoiDataRef = useRef({ trafficLights: [], crosswalks: [] });

  const [startPoint, setStartPoint] = useState(null);
  const [endPoint, setEndPoint] = useState(null);
  const [routeCandidates, setRouteCandidates] = useState([]);
  const [guideMsg, setGuideMsg] = useState("지도 및 인프라 데이터를 로드 중입니다...");
  const [listening, setListening] = useState(false);
  const [errorMsg, setErrorMsg] = useState("");
  const [mapReady, setMapReady] = useState(false);
  const [backendStatus, setBackendStatus] = useState("백엔드 데이터를 대기 중입니다.");
  const [riskItems, setRiskItems] = useState([]);
  const [mobileDetections, setMobileDetections] = useState([]);
  const [adminSummary, setAdminSummary] = useState(null);

  const speak = (text) => {
    const msg = new SpeechSynthesisUtterance(text);
    msg.lang = "ko-KR";
    window.speechSynthesis.cancel();
    window.speechSynthesis.speak(msg);
  };

  const escapeHtml = (value = "") => {
    return String(value)
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;")
      .replaceAll("'", "&#039;");
  };

  const formatTimestamp = (value) => {
    if (!value) return "-";

    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
      return value;
    }

    return date.toLocaleString("ko-KR", {
      hour12: false,
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
  };

  const createMarkerImage = (fillColor, label) => {
    const svg = encodeURIComponent(`
      <svg xmlns="http://www.w3.org/2000/svg" width="42" height="42" viewBox="0 0 42 42">
        <path d="M21 2C12.2 2 5 9.1 5 17.8c0 10.7 16 22.2 16 22.2s16-11.5 16-22.2C37 9.1 29.8 2 21 2z" fill="${fillColor}" stroke="#111" stroke-width="2"/>
        <circle cx="21" cy="18" r="10" fill="#fff" opacity=".92"/>
        <text x="21" y="22" text-anchor="middle" font-family="Arial, sans-serif" font-size="10" font-weight="800" fill="#111">${label}</text>
      </svg>
    `);

    return new window.kakao.maps.MarkerImage(
      `data:image/svg+xml;charset=UTF-8,${svg}`,
      new window.kakao.maps.Size(42, 42),
      { offset: new window.kakao.maps.Point(21, 42) }
    );
  };

  const clearMapObjects = (ref) => {
    ref.current.forEach((item) => item.setMap(null));
    ref.current = [];
  };

  const openMarkerInfo = (marker, content) => {
    if (markerInfoWindowRef.current) {
      markerInfoWindowRef.current.close();
    }

    markerInfoWindowRef.current = new window.kakao.maps.InfoWindow({ content });
    markerInfoWindowRef.current.open(mapRef.current, marker);
  };

  const drawRiskMarkers = (items = riskItems) => {
    if (!mapRef.current || !window.kakao?.maps) return;

    clearMapObjects(riskMarkerRefs);
    const image = createMarkerImage("#ff1744", "!");

    items.forEach((risk) => {
      if (!risk.lat || !risk.lng) return;

      const marker = new window.kakao.maps.Marker({
        map: mapRef.current,
        position: new window.kakao.maps.LatLng(risk.lat, risk.lng),
        title: risk.title,
        image,
      });

      const content = `
        <div style="min-width:220px;padding:10px;font-size:13px;line-height:1.45">
          <strong style="display:block;margin-bottom:4px;color:#b00020">${escapeHtml(risk.title)}</strong>
          <div>${escapeHtml(risk.description || "")}</div>
          <div style="margin-top:6px;color:#555">상태: ${escapeHtml(risk.status || "-")} / 등급: ${escapeHtml(risk.level || "-")}</div>
        </div>
      `;

      window.kakao.maps.event.addListener(marker, "click", () => openMarkerInfo(marker, content));
      riskMarkerRefs.current.push(marker);
    });
  };

  const drawDetectionMarkers = (items = mobileDetections) => {
    if (!mapRef.current || !window.kakao?.maps) return;

    clearMapObjects(detectionMarkerRefs);
    const image = createMarkerImage("#ffd400", "AI");

    items.forEach((item) => {
      const lat = item.markerLat ?? item.lat;
      const lng = item.markerLng ?? item.lng;

      if (!lat || !lng) return;

      const primary = item.detections?.[0];
      const label = primary?.label || "unknown";
      const confidence =
        primary?.confidence !== undefined ? `${Math.round(primary.confidence * 100)}%` : "-";
      const marker = new window.kakao.maps.Marker({
        map: mapRef.current,
        position: new window.kakao.maps.LatLng(lat, lng),
        title: `앱 감지: ${label}`,
        image,
      });

      const content = `
        <div style="min-width:240px;padding:10px;font-size:13px;line-height:1.45">
          <strong style="display:block;margin-bottom:4px;color:#111">최근 앱 감지 장애물</strong>
          <div>${escapeHtml(label)} ${confidence}</div>
          <div>${escapeHtml(primary?.direction || "-")} / ${escapeHtml(primary?.distanceLevel || "-")}</div>
          <div style="margin-top:6px;color:#555">${escapeHtml(item.message || "")}</div>
          <div style="margin-top:6px;color:#777">${formatTimestamp(item.timestamp)}</div>
          <div style="color:#777">기기: ${escapeHtml(item.deviceId || "-")}</div>
        </div>
      `;

      window.kakao.maps.event.addListener(marker, "click", () => openMarkerInfo(marker, content));
      detectionMarkerRefs.current.push(marker);
    });
  };

  const fetchBackendLayers = async () => {
    try {
      setBackendStatus("백엔드 데이터를 불러오는 중입니다.");

      const [summaryResult, risksResult, detectionsResult] = await Promise.all([
        getAdminSummary(AREA),
        getRisks(AREA),
        getAdminDetections(AREA, 50),
      ]);

      const nextRisks = risksResult.items || [];
      const nextDetections = detectionsResult.items || [];
      setAdminSummary(summaryResult);
      setRiskItems(nextRisks);
      setMobileDetections(nextDetections);
      setBackendStatus(`위험 ${nextRisks.length}건, 최근 앱 감지 ${nextDetections.length}건`);
      drawRiskMarkers(nextRisks);
      drawDetectionMarkers(nextDetections);
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error);
      setBackendStatus(`백엔드 데이터 로드 실패: ${reason}`);
    }
  };

  const startVoice = () => {
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SpeechRecognition) {
      alert("이 브라우저에서는 음성 인식을 지원하지 않습니다.");
      return;
    }

    const rec = new SpeechRecognition();
    rec.lang = "ko-KR";
    rec.interimResults = false;
    rec.continuous = false;

    setListening(true);
    setGuideMsg("🎤 목적지를 말씀해 주세요...");

    rec.onresult = (e) => {
      const text = e.results[0][0].transcript;
      setListening(false);
      setGuideMsg("📍 검색어: " + text);
      speak("목적지 " + text + " 검색을 시작합니다.");
      geocode(text);
    };

    rec.onerror = (e) => {
      console.error("음성 인식 오류 에미터:", e.error);
      setListening(false);
      
      if (e.error === "network") {
        setGuideMsg("⚠️ 네트워크 연결이 불안정합니다. 마이크를 다시 눌러주세요.");
      } else if (e.error === "no-speech") {
        setGuideMsg("⚠️ 음성이 감지되지 않았습니다. 다시 말씀해 주세요.");
      } else {
        setGuideMsg(`음성 인식 실패: ${e.error}`);
      }
    };

    rec.onend = () => setListening(false);
    try { rec.start(); } catch (err) { console.error(err); }
  };

  const geocode = async (query) => {
    if (!KAKAO_REST_KEY) {
      setGuideMsg("Kakao REST 키가 없어 장소 검색을 사용할 수 없습니다.");
      setErrorMsg("VITE_KAKAO_REST_KEY를 .env.local에 설정하세요.");
      return;
    }

    try {
      let res = await fetch(
        `https://dapi.kakao.com/v2/local/search/keyword.json?query=${encodeURIComponent(query)}`,
        { headers: { Authorization: `KakaoAK ${KAKAO_REST_KEY}` } }
      );
      let data = await res.json();
      
      if (!data.documents || data.documents.length === 0) {
        console.log("🗣️ 키워드 검색 결과 없음 -> 주소 검색 Fallback 진입");
        res = await fetch(
          `https://dapi.kakao.com/v2/local/search/address.json?query=${encodeURIComponent(query)}`,
          { headers: { Authorization: `KakaoAK ${KAKAO_REST_KEY}` } }
        );
        data = await res.json();
      }

      if (!data.documents || data.documents.length === 0) {
        setGuideMsg(`'${query}'에 해당하는 장소를 찾을 수 없습니다.`);
        speak("해당 장소를 찾을 수 없습니다. 다시 말씀해 주세요.");
        return;
      }

      const d = data.documents[0];
      setDestination({ lat: parseFloat(d.y), lng: parseFloat(d.x) });
    } catch (err) {
      console.error(err);
      setErrorMsg("장소 검색 연동 중 내부 네트워크 오류 발생");
    }
  };

  const setDestination = async (point) => {
    if (!myPosRef.current) {
      alert("현재 GPS 위치 정보를 획득하는 중입니다. 잠시만 대기 후 다시 시도해 주세요.");
      return;
    }

    destPosRef.current = point;
    setEndPoint(point);

    if (destMarkerRef.current) destMarkerRef.current.setMap(null);

    destMarkerRef.current = new window.kakao.maps.Marker({
      map: mapRef.current,
      position: new window.kakao.maps.LatLng(point.lat, point.lng),
      image: new window.kakao.maps.MarkerImage(
        "https://t1.daumcdn.net/localimg/localimages/07/mapapidoc/marker_red.png",
        new window.kakao.maps.Size(36, 36)
      ),
    });

    speak("목적지가 인지되었습니다. 보행 요소 최적화 연산을 시작합니다.");
    await requestCandidates(myPosRef.current, point);
  };

  const setMyPosition = (lat, lng) => {
    myPosRef.current = { lat, lng };
    setStartPoint({ lat, lng });

    if (myMarkerRef.current) myMarkerRef.current.setMap(null);

    myMarkerRef.current = new window.kakao.maps.Marker({
      map: mapRef.current,
      position: new window.kakao.maps.LatLng(lat, lng),
      image: new window.kakao.maps.MarkerImage(
        "https://t1.daumcdn.net/localimg/localimages/07/mapapidoc/markerStar.png",
        new window.kakao.maps.Size(36, 36)
      ),
    });
  };

  const getTactileWaypoints = (start, end, tactileGeoJson) => {
    const startCoord = [start.lng, start.lat];
    const endCoord = [end.lng, end.lat];

    return tactileGeoJson.features
      .filter((feature) => feature.geometry.type === "LineString")
      .map((feature) => {
        const line = turf.lineString(feature.geometry.coordinates);
        const lineLength = turf.length(line, { units: "kilometers" });
        const midpoint = turf.along(line, lineLength / 2, { units: "kilometers" });

        const [lng, lat] = midpoint.geometry.coordinates;
        const startDist = turf.distance(turf.point(startCoord), midpoint, { units: "kilometers" });
        const endDist = turf.distance(midpoint, turf.point(endCoord), { units: "kilometers" });

        return {
          lat,
          lng,
          memo: feature.properties?.memo || "점자블록 지정 구간",
          estimatedDist: startDist + endDist,
          startDist,
          endDist,
        };
      })
      .filter((wp) => wp.startDist > 0.02 && wp.endDist > 0.02)
      .sort((a, b) => a.estimatedDist - b.estimatedDist)
      .slice(0, 3);
  };

  // ==========================================
  // 🧮 복합 가산 스코어링 엔진 (신호등 로직 스킵 제거 완료)
  // ==========================================
  const calculateAdvancedScore = (coords, totalLength, directLength, tactileGeoJson, poiData) => {
    const routeLine = turf.lineString(coords);

    let tactileMatchMeter = 0;
    tactileGeoJson.features.forEach((feat) => {
      if (feat.geometry.type !== "LineString") return;
      const blockLine = turf.lineString(feat.geometry.coordinates);
      
      const buffer = turf.buffer(routeLine, 0.015, { units: "kilometers" });
      const intersection = turf.lineIntersect(blockLine, buffer);
      
      if (intersection.features.length > 0) {
        tactileMatchMeter += Math.round(turf.length(blockLine, { units: "kilometers" }) * 1000 * 0.4);
      }
    });
    if (tactileMatchMeter > totalLength) tactileMatchMeter = totalLength;
    const tactileRatio = totalLength > 0 ? (tactileMatchMeter / totalLength) * 100 : 0;
    const tactileScore = (tactileRatio / 100) * 45; // 비중 소폭 재조정 (40 -> 45)

    const distanceEfficiency = directLength > 0 ? (directLength / totalLength) * 100 : 100;
    const distanceScore = (distanceEfficiency / 100) * 35; // 비중 소폭 재조정 (30 -> 35)

    let crosswalkCount = 0;
    const routeBuffer = turf.buffer(routeLine, 0.015, { units: "kilometers" });

    poiData.crosswalks.forEach((cw) => {
      const pt = turf.point([cw.lng, cw.lat]);
      if (turf.booleanPointInPolygon(pt, routeBuffer)) crosswalkCount++;
    });

    const crosswalkScore = Math.min(crosswalkCount * 5, 20); 

    // ⚖️ 신호등 페널티 연산 제거
    const finalScore = Math.max(10, Math.round(tactileScore + distanceScore + crosswalkScore));

    return {
      finalScore,
      tactileRatio: Math.round(tactileRatio),
      tactileLength: Math.round(tactileMatchMeter),
      distanceEfficiency: Math.round(distanceEfficiency),
      crosswalkCount,
      trafficLightCount: 0 // 항상 0으로 고정
    };
  };

  const makeRouteCandidates = async (start, end) => {
    const tactileGeoJson = tactileGeoJsonRef.current;
    if (!tactileGeoJson) throw new Error("안전 인프라 Map 정보가 로드되지 않았습니다.");

    const candidates = [];
    const poiData = currentPoiDataRef.current; 

    const directCoords = await fetchTmapRoute(start, end);
    const directLine = turf.lineString(directCoords);
    const directLength = Math.round(turf.length(directLine, { units: "kilometers" }) * 1000);

    const directMetrics = calculateAdvancedScore(directCoords, directLength, directLength, tactileGeoJson, poiData);

    candidates.push({
      id: "direct",
      name: "후보 1: Tmap 기본 최단 경로", 
      reason: `최단 보행 동선입니다. 안전 점수 ${directMetrics.finalScore}점, 횡단보도 ${directMetrics.crosswalkCount}개 인접.`,
      coords: directCoords,
      routeLengthMeter: directLength,
      tactileRatioPercent: directMetrics.tactileRatio,
      tactileLengthMeter: directMetrics.tactileLength,
      distanceEfficiencyPercent: directMetrics.distanceEfficiency,
      crosswalkCount: directMetrics.crosswalkCount,
      trafficLightCount: 0,
      detourRatio: 1.0,
      finalScore: directMetrics.finalScore,
    });

    const waypoints = getTactileWaypoints(start, end, tactileGeoJson);

    for (let i = 0; i < waypoints.length; i++) {
      const wp = waypoints[i];
      try {
        const firstCoords = await fetchTmapRoute(start, wp);
        const secondCoords = await fetchTmapRoute(wp, end);
        const mergedCoords = [...firstCoords, ...secondCoords.slice(1)];

        const mergedLine = turf.lineString(mergedCoords);
        const candidateLength = Math.round(turf.length(mergedLine, { units: "kilometers" }) * 1000);

        if (candidateLength > directLength * 1.6) continue; 

        const metrics = calculateAdvancedScore(mergedCoords, candidateLength, directLength, tactileGeoJson, poiData);
        const detourRatio = parseFloat((candidateLength / directLength).toFixed(2));

        candidates.push({
          id: `via-${i + 1}`,
          name: `후보 ${i + 2}: 인프라 연계 우회 경로`,
          reason: `${wp.memo} 구역의 점자블록과 횡단보도를 연산한 대안 동선입니다. (점수: ${metrics.finalScore}점)`,
          waypoint: wp,
          coords: mergedCoords,
          routeLengthMeter: candidateLength,
          tactileRatioPercent: metrics.tactileRatio,
          tactileLengthMeter: metrics.tactileLength,
          distanceEfficiencyPercent: metrics.distanceEfficiency,
          crosswalkCount: metrics.crosswalkCount,
          trafficLightCount: 0,
          detourRatio: detourRatio,
          finalScore: metrics.finalScore,
        });
      } catch (err) {
        console.warn("경유지 연산 스킵:", wp.memo);
      }
    }

    return candidates.sort((a, b) => b.finalScore - a.finalScore);
  };

  const drawRouteCandidates = (candidates) => {
    routePolylineRefs.current.forEach((line) => line.setMap(null));
    routePolylineRefs.current = [];

    const otherRoutes = candidates.slice(1);
    const bestRoute = candidates[0];

    otherRoutes.forEach((candidate) => {
      const path = candidate.coords.map(([lng, lat]) => new window.kakao.maps.LatLng(lat, lng));
      const polyline = new window.kakao.maps.Polyline({
        map: mapRef.current,
        path: path,
        strokeWeight: 5,
        strokeColor: "#A6A6A6",
        strokeOpacity: 0.6,
      });
      routePolylineRefs.current.push(polyline);
    });

    if (bestRoute) {
      const path = bestRoute.coords.map(([lng, lat]) => new window.kakao.maps.LatLng(lat, lng));
      const bestPolyline = new window.kakao.maps.Polyline({
        map: mapRef.current,
        path: path,
        strokeWeight: 8,
        strokeColor: "#0055FF",
        strokeOpacity: 0.95,
      });
      routePolylineRefs.current.push(bestPolyline);

      const bounds = new window.kakao.maps.LatLngBounds();
      path.forEach((p) => bounds.extend(p));
      mapRef.current.setBounds(bounds);
    }
  };

  const requestCandidates = async (start, end) => {
    try {
      setErrorMsg("");
      setGuideMsg("🤖 점자블록, 횡단보도 인프라 밀집도를 실시간 연산 중...");

      const candidates = await makeRouteCandidates(start, end);
      if (candidates.length === 0) throw new Error("추천 경로 풀이 비어있습니다.");

      drawRouteCandidates(candidates);
      setRouteCandidates(candidates);
      setGuideMsg("추천 경로 갱신 완료! 안전성 가중치가 부여되었습니다.");
    } catch (err) {
      console.error(err);
      setErrorMsg(err.message);
      setGuideMsg("경로 갱신 실패");
    }
  };

  const drawTactileBlocksOnKakao = (tactileGeoJson) => {
    tactilePolylineRefs.current.forEach((line) => line.setMap(null));
    tactilePolylineRefs.current = [];

    tactileGeoJson.features.forEach((feature) => {
      if (feature.geometry.type !== "LineString") return;
      const path = feature.geometry.coordinates.map(([lng, lat]) => new window.kakao.maps.LatLng(lat, lng));
      const polyline = new window.kakao.maps.Polyline({
        map: mapRef.current,
        path: path,
        strokeWeight: 6,
        strokeColor: "#FFD400",
        strokeOpacity: 0.75,
      });
      tactilePolylineRefs.current.push(polyline);
    });
  };

  const clearPOI = () => {
    poiRefs.current.forEach((m) => m.setMap(null));
    poiRefs.current = [];
  };

  const drawCrosswalk = (p) => {
    const c = new window.kakao.maps.Circle({
      center: new window.kakao.maps.LatLng(p.lat, p.lng),
      radius: 8,
      strokeColor: "#00E676",
      strokeOpacity: 0.8,
      strokeWeight: 2,
      fillColor: "#00E676",
      fillOpacity: 0.4,
      map: mapRef.current,
    });
    poiRefs.current.push(c);
  };

  // 🛠️ 최적화: Overpass API에서 신호등 쿼리를 제거하여 횡단보도 검색만 신속하게 수행하도록 변경
  const fetchPOI = async () => {
    if (!mapRef.current) return;

    const b = mapRef.current.getBounds();
    const sw = b.getSouthWest();
    const ne = b.getNorthEast();
    const bbox = `${sw.getLat()},${sw.getLng()},${ne.getLat()},${ne.getLng()}`;

    const query = `
      [out:json][timeout:15];
      (
        node["highway"="crossing"](${bbox});
      );
      out body;
    `;

    try {
      const res = await fetch("https://overpass-api.de/api/interpreter", {
        method: "POST",
        body: query,
      });
      const data = await res.json();

      clearPOI();
      const crosswalks = [];

      if (data.elements) {
        data.elements.forEach((el) => {
          const p = { lat: el.lat, lng: el.lon };
          if (el.tags?.highway === "crossing" || el.tags?.footway === "crossing") {
            drawCrosswalk(p);
            crosswalks.push(p);
          }
        });
      }

      currentPoiDataRef.current = { trafficLights: [], crosswalks };
      console.log(`📡 [A-eye POI] 캐싱 동기화 -> 횡단보도: ${crosswalks.length}개 가동 중`);
    } catch (err) {
      console.warn("POI 수집 엔진 응답 지연", err);
    }
  };

  const handleClick = (e) => {
    setDestination({
      lat: e.latLng.getLat(),
      lng: e.latLng.getLng(),
    });
  };

  const resetSelection = () => {
    if (destMarkerRef.current) destMarkerRef.current.setMap(null);
    routePolylineRefs.current.forEach((line) => line.setMap(null));
    routePolylineRefs.current = [];
    setEndPoint(null);
    setRouteCandidates([]);
    setErrorMsg("");
    setGuideMsg("목적지를 입력해 주세요.");
  };

  useEffect(() => {
    if (!KAKAO_JS_KEY) {
      setErrorMsg("VITE_KAKAO_JS_KEY를 .env.local에 설정하세요.");
      setGuideMsg("Kakao Map 키가 없어 지도를 불러올 수 없습니다.");
      return;
    }

    const script = document.createElement("script");
    script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${KAKAO_JS_KEY}&autoload=false&libraries=services`;
    script.async = true;

    script.onload = () => {
      window.kakao.maps.load(() => {
        navigator.geolocation.getCurrentPosition(
          async (pos) => {
            const lat = pos.coords.latitude;
            const lng = pos.coords.longitude;

            const map = new window.kakao.maps.Map(mapDivRef.current, {
              center: new window.kakao.maps.LatLng(lat, lng),
              level: 3,
            });

            mapRef.current = map;
            setMapReady(true);
            setMyPosition(lat, lng);

            window.kakao.maps.event.addListener(map, "click", handleClick);
            window.kakao.maps.event.addListener(map, "idle", fetchPOI);

            try {
              const res = await fetch(`${API_BASE_URL}/api/tactile-blocks?area=${AREA}`);
              const geojson = await res.json();
              tactileGeoJsonRef.current = geojson;
              drawTactileBlocksOnKakao(geojson);
              setGuideMsg("모든 안전 분석 레이어가 준비되었습니다.");
            } catch (e) {
              console.error(e);
              setErrorMsg("점자블록 데이터 맵 바인딩 실패");
            }
            fetchPOI();
          },
          async () => {
            const lat = 37.542100;
            const lng = 126.841306;
            const map = new window.kakao.maps.Map(mapDivRef.current, {
              center: new window.kakao.maps.LatLng(lat, lng),
              level: 3,
            });
            mapRef.current = map;
            setMapReady(true);
            setMyPosition(lat, lng);
            window.kakao.maps.event.addListener(map, "click", handleClick);
            window.kakao.maps.event.addListener(map, "idle", fetchPOI);
            try {
              const res = await fetch(`${API_BASE_URL}/api/tactile-blocks?area=${AREA}`);
              const geojson = await res.json();
              tactileGeoJsonRef.current = geojson;
              drawTactileBlocksOnKakao(geojson);
              setGuideMsg("기본 위치로 안전 분석 레이어가 준비되었습니다.");
            } catch (e) {
              console.error(e);
              setErrorMsg("점자블록 데이터 맵 바인딩 실패");
            }
            fetchPOI();
          },
          { enableHighAccuracy: true, timeout: 10000 }
        );
      });
    };
    document.head.appendChild(script);
  }, []);

  useEffect(() => {
    if (!mapReady) return;

    fetchBackendLayers();
    const timerId = window.setInterval(fetchBackendLayers, 10000);

    return () => window.clearInterval(timerId);
  }, [mapReady]);

  return (
    <div style={{ width: "100vw", height: "100vh", position: "relative", display: "flex", fontFamily: "sans-serif" }}>
      {/* 1. 왼쪽 사이드바 관제 패널 */}
      <div
        style={{
          position: "absolute",
          top: 12,
          left: 12,
          zIndex: 10,
          width: 380,
          maxHeight: "94vh",
          overflowY: "auto",
          background: "rgba(255, 255, 255, 0.95)",
          padding: 16,
          borderRadius: 12,
          boxShadow: "0 4px 15px rgba(0,0,0,0.2)",
          fontSize: 14,
          lineHeight: 1.6,
        }}
      >
        <b style={{ fontSize: 16, color: "#111" }}>🦮 A-eye 복합 안전 내비게이션</b>
        <p style={{ margin: "8px 0", color: "#555", fontWeight: "500" }}>{guideMsg}</p>

        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 8, marginBottom: 12 }}>
          <div style={{ background: "#fff8d8", border: "1px solid #f0d15a", borderRadius: 8, padding: 10 }}>
            <div style={{ fontSize: 11, color: "#555", fontWeight: 700 }}>앱 감지 로그</div>
            <strong style={{ fontSize: 22 }}>{mobileDetections.length}</strong>
            <div style={{ fontSize: 11, color: "#555" }}>
              전체 {adminSummary?.mobileDetectionLogCount ?? "-"}건
            </div>
          </div>
          <div style={{ background: "#fff0f0", border: "1px solid #efb2b2", borderRadius: 8, padding: 10 }}>
            <div style={{ fontSize: 11, color: "#555", fontWeight: 700 }}>위험 지점</div>
            <strong style={{ fontSize: 22 }}>{riskItems.length}</strong>
            <div style={{ fontSize: 11, color: "#555" }}>
              미해결 {adminSummary?.openRiskCount ?? "-"}건
            </div>
          </div>
        </div>

        <div style={{ display: "flex", gap: 8, alignItems: "center", marginBottom: 12 }}>
          <div style={{ flex: 1, fontSize: 12, color: "#555" }}>{backendStatus}</div>
          <button
            onClick={fetchBackendLayers}
            style={{ padding: "7px 10px", background: "#111", color: "#fff", border: "none", borderRadius: 6, fontWeight: "bold", cursor: "pointer" }}
          >
            마커 새로고침
          </button>
        </div>

        <div style={{ display: "flex", gap: 6, marginBottom: 12 }}>
          <button onClick={startVoice} style={{ flex: 2, padding: "8px", background: "#0055FF", color: "white", border: "none", borderRadius: 6, fontWeight: "bold", cursor: "pointer" }}>
            {listening ? "🎤 음성 수신 중..." : "🎤 음성 목적지"}
          </button>
          <button onClick={resetSelection} style={{ flex: 1, padding: "8px", background: "#eee", border: "1px solid #ccc", borderRadius: 6, cursor: "pointer" }}>
            초기화
          </button>
        </div>

        {startPoint && (
          <div style={{ fontSize: 12, background: "#f8f9fa", padding: "6px 10px", borderRadius: 6, marginBottom: 4 }}>
            📍 <b>출발지:</b> {startPoint.lat.toFixed(6)}, {startPoint.lng.toFixed(6)}
          </div>
        )}
        {endPoint && (
          <div style={{ fontSize: 12, background: "#fff0f0", padding: "6px 10px", borderRadius: 6, marginBottom: 8 }}>
            🎯 <b>도착지:</b> {endPoint.lat.toFixed(6)}, {endPoint.lng.toFixed(6)}
          </div>
        )}

        {errorMsg && <div style={{ color: "red", fontSize: 12, background: "#fdf2f2", padding: 8, borderRadius: 6 }}>⚠️ {errorMsg}</div>}

        {routeCandidates.length > 0 && (
          <>
            <hr style={{ border: "0", height: "1px", background: "#ddd", margin: "12px 0" }} />
            <div style={{ background: "#e8f0fe", padding: 10, borderRadius: 8, marginBottom: 12 }}>
              <h4 style={{ margin: "0 0 4px 0", color: "#1a73e8" }}>👑 AI 최적 추천: {routeCandidates[0].name}</h4>
              <p style={{ margin: 0, fontSize: 13 }}>
                종합 위험도 관리 점수: <b style={{ fontSize: 17, color: "#0055FF" }}>{routeCandidates[0].finalScore}점</b>
              </p>
              <p style={{ margin: "4px 0 0 0", fontSize: 12, color: "#444" }}><b>분석 사유:</b> {routeCandidates[0].reason}</p>
            </div>
            
            <b style={{ fontSize: 13, color: "#666" }}>인프라 융합 경로 리스트</b>

            {routeCandidates.map((candidate, index) => (
              <div key={candidate.id} style={{ padding: "12px 0", borderBottom: "1px solid #eee", opacity: index === 0 ? 1 : 0.85 }}>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                  <span style={{ fontWeight: "bold", color: index === 0 ? "#0055FF" : "#333" }}>{candidate.name}</span>
                  <span style={{ background: index === 0 ? "#0055FF" : "#777", color: "white", padding: "2px 6px", borderRadius: 4, fontSize: 11 }}>
                    {candidate.finalScore}점
                  </span>
                </div>

                <div style={{ fontSize: 12, color: "#666", marginTop: 4, display: "grid", gridTemplateColumns: "1fr 1fr", gap: "2px 8px" }}>
                  <div>• 블록 포함률: <b>{candidate.tactileRatioPercent}%</b></div>
                  <div>• 거리 효율성: <b>{candidate.distanceEfficiencyPercent}%</b></div>
                  <div>• 인접 횡단보도: <b>{candidate.crosswalkCount}개</b></div>
                  <div>• 전체 총거리: <b>{candidate.routeLengthMeter}m</b></div>
                  <div>• 우회 페널티: <b>{candidate.detourRatio}배</b></div>
                </div>
              </div>
            ))}
          </>
        )}
      </div>

      {/* 2. 우측 전체 지도 영역 */}
      <div ref={mapDivRef} style={{ width: "100%", height: "100%" }} />

      {/* 3. 우측 하단 스마트폰 Live AI 추론 PIP 관제창 */}
      <div
        style={{
          position: "absolute",
          bottom: 24,
          right: 24,
          zIndex: 20,
          width: "360px",
          height: "290px",
          background: "#111",
          borderRadius: "12px",
          boxShadow: "0 8px 24px rgba(0,0,0,0.4)",
          overflow: "hidden",
          border: "2px solid #0055FF",
          display: "flex",
          flexDirection: "column"
        }}
      >
        <div style={{ background: "#0055FF", color: "#fff", padding: "8px 12px", fontSize: "12px", fontWeight: "bold", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <span>📹 현장 스마트폰 시점</span>
          <span style={{ color: "#00FF66", fontSize: "11px", display: "flex", alignItems: "center", gap: "4px" }}>
            <span style={{ width: 6, height: 6, background: "#00FF66", borderRadius: "50%", display: "inline-block" }}></span>
            LIVE
          </span>
        </div>

        <div style={{ flex: 1, position: "relative", background: "#000" }}>
          <img 
            src={`${STREAM_BASE_URL}/video_feed`}
            style={{ width: "100%", height: "100%", objectFit: "cover" }} 
            alt="현장 영상 대기 중..."
            onError={(e) => {
              e.target.style.display = "none";
              e.target.nextSibling.style.display = "flex";
            }}
            onLoad={(e) => {
              e.target.style.display = "block";
              e.target.nextSibling.style.display = "none";
            }}
          />
          <div style={{ position: "absolute", top: 0, left: 0, width: "100%", height: "100%", display: "flex", justifyContent: "center", alignItems: "center", color: "#aaa", fontSize: "13px", zIndex: 1 }}>
            📡 스마트폰 카메라 신호를 대기 중입니다.
          </div>
        </div>
      </div>

    </div>
  );
}
