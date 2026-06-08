import { useCallback, useEffect, useRef, useState } from "react";
import { buildRouteCandidates } from "./routeEngine";

const API_BASE = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");
const KAKAO_JS_KEY = import.meta.env.VITE_KAKAO_JS_KEY || "";
const KAKAO_REST_KEY = import.meta.env.VITE_KAKAO_REST_KEY || "";
const DEFAULT_POSITION = { lat: 37.5421, lng: 126.841306 };

function apiUrl(path) {
  return `${API_BASE}${path}`;
}

function toLatLng(point) {
  return new window.kakao.maps.LatLng(point.lat, point.lng);
}

export default function App() {
  const mapDivRef = useRef(null);
  const mapRef = useRef(null);
  const currentMarkerRef = useRef(null);
  const destinationMarkerRef = useRef(null);
  const routeLinesRef = useRef([]);
  const tactileLinesRef = useRef([]);
  const riskMarkersRef = useRef([]);
  const detectionMarkersRef = useRef([]);
  const poiOverlaysRef = useRef([]);
  const destinationNameRef = useRef("");

  const [status, setStatus] = useState("관제 웹을 준비하고 있습니다.");
  const [currentPosition, setCurrentPosition] = useState(DEFAULT_POSITION);
  const [destination, setDestinationState] = useState(null);
  const [routeCandidates, setRouteCandidates] = useState([]);
  const [summary, setSummary] = useState(null);
  const [risks, setRisks] = useState([]);
  const [detections, setDetections] = useState([]);
  const [tactileGeoJson, setTactileGeoJson] = useState(null);
  const [poiData, setPoiData] = useState({ crosswalks: [], trafficLights: [] });
  const [streamVisible, setStreamVisible] = useState(true);

  const clearMapObjects = (refs) => {
    refs.current.forEach((item) => item.setMap(null));
    refs.current = [];
  };

  const drawTactileBlocks = useCallback((geoJson) => {
    if (!mapRef.current || !geoJson?.features) {
      return;
    }

    clearMapObjects(tactileLinesRef);

    geoJson.features.forEach((feature) => {
      if (feature.geometry?.type !== "LineString") {
        return;
      }

      const path = feature.geometry.coordinates.map(([lng, lat]) => new window.kakao.maps.LatLng(lat, lng));
      const polyline = new window.kakao.maps.Polyline({
        map: mapRef.current,
        path,
        strokeWeight: 6,
        strokeColor: "#FFD700",
        strokeOpacity: 0.78,
      });
      tactileLinesRef.current.push(polyline);
    });
  }, []);

  const drawRiskMarkers = useCallback((items) => {
    if (!mapRef.current) {
      return;
    }

    clearMapObjects(riskMarkersRef);

    items.forEach((risk) => {
      const marker = new window.kakao.maps.Marker({
        map: mapRef.current,
        position: new window.kakao.maps.LatLng(risk.lat, risk.lng),
        title: risk.title,
      });
      const overlay = new window.kakao.maps.InfoWindow({
        content: `<div style="padding:8px;font-size:12px;min-width:150px"><b>${risk.title}</b><br/>${risk.level} · ${risk.status}</div>`,
      });
      window.kakao.maps.event.addListener(marker, "click", () => overlay.open(mapRef.current, marker));
      riskMarkersRef.current.push(marker);
    });
  }, []);

  const drawDetections = useCallback((items) => {
    if (!mapRef.current) {
      return;
    }

    clearMapObjects(detectionMarkersRef);

    items.slice(0, 40).forEach((entry) => {
      const detection = entry.detections?.[0];

      if (!detection) {
        return;
      }

      const marker = new window.kakao.maps.Marker({
        map: mapRef.current,
        position: new window.kakao.maps.LatLng(entry.markerLat || entry.lat, entry.markerLng || entry.lng),
        title: detection.label,
      });
      const overlay = new window.kakao.maps.InfoWindow({
        content: `<div style="padding:8px;font-size:12px;min-width:180px"><b>AI 감지</b><br/>${detection.label} ${Math.round(detection.confidence * 100)}%<br/>${detection.direction} · ${detection.distanceLevel}</div>`,
      });
      window.kakao.maps.event.addListener(marker, "click", () => overlay.open(mapRef.current, marker));
      detectionMarkersRef.current.push(marker);
    });
  }, []);

  const drawCrosswalks = useCallback((crosswalks) => {
    if (!mapRef.current) {
      return;
    }

    clearMapObjects(poiOverlaysRef);

    crosswalks.forEach((point) => {
      const circle = new window.kakao.maps.Circle({
        center: new window.kakao.maps.LatLng(point.lat, point.lng),
        radius: 8,
        strokeColor: "#00E676",
        strokeOpacity: 0.8,
        strokeWeight: 2,
        fillColor: "#00E676",
        fillOpacity: 0.36,
        map: mapRef.current,
      });
      poiOverlaysRef.current.push(circle);
    });
  }, []);

  const drawRouteCandidates = useCallback((candidates) => {
    if (!mapRef.current) {
      return;
    }

    clearMapObjects(routeLinesRef);

    candidates.slice(1).forEach((candidate) => {
      const path = candidate.coords.map(([lng, lat]) => new window.kakao.maps.LatLng(lat, lng));
      const polyline = new window.kakao.maps.Polyline({
        map: mapRef.current,
        path,
        strokeWeight: 5,
        strokeColor: "#8A8A8A",
        strokeOpacity: 0.5,
      });
      routeLinesRef.current.push(polyline);
    });

    const best = candidates[0];

    if (!best) {
      return;
    }

    const path = best.coords.map(([lng, lat]) => new window.kakao.maps.LatLng(lat, lng));
    const polyline = new window.kakao.maps.Polyline({
      map: mapRef.current,
      path,
      strokeWeight: 8,
      strokeColor: "#7C3AED",
      strokeOpacity: 0.95,
    });
    routeLinesRef.current.push(polyline);

    const bounds = new window.kakao.maps.LatLngBounds();
    path.forEach((point) => bounds.extend(point));
    mapRef.current.setBounds(bounds);
  }, []);

  const fetchCrosswalks = useCallback(async () => {
    if (!mapRef.current) {
      return;
    }

    const bounds = mapRef.current.getBounds();
    const sw = bounds.getSouthWest();
    const ne = bounds.getNorthEast();
    const query = `
      [out:json][timeout:25];
      (
        node["highway"="crossing"](${sw.getLat()},${sw.getLng()},${ne.getLat()},${ne.getLng()});
        way["highway"="footway"]["footway"="crossing"](${sw.getLat()},${sw.getLng()},${ne.getLat()},${ne.getLng()});
      );
      out center;
    `;

    try {
      const response = await fetch("https://lz4.overpass-api.de/api/interpreter", {
        method: "POST",
        body: query,
      });
      const data = await response.json();
      const crosswalks = data.elements
        .map((element) => ({
          lat: element.lat || element.center?.lat,
          lng: element.lon || element.center?.lon,
        }))
        .filter((point) => point.lat && point.lng);

      setPoiData({ crosswalks, trafficLights: [] });
      drawCrosswalks(crosswalks);
    } catch (error) {
      console.warn("횡단보도 POI 로드 실패", error);
    }
  }, [drawCrosswalks]);

  const setCurrentMarker = useCallback((point) => {
    if (!mapRef.current) {
      return;
    }

    if (currentMarkerRef.current) {
      currentMarkerRef.current.setMap(null);
    }

    currentMarkerRef.current = new window.kakao.maps.Marker({
      map: mapRef.current,
      position: toLatLng(point),
      title: "앱 위치",
    });
  }, []);

  const setDestinationMarker = useCallback((point, name) => {
    if (!mapRef.current) {
      return;
    }

    if (destinationMarkerRef.current) {
      destinationMarkerRef.current.setMap(null);
    }

    destinationMarkerRef.current = new window.kakao.maps.Marker({
      map: mapRef.current,
      position: toLatLng(point),
      title: name,
    });
  }, []);

  const geocodeByKeyword = useCallback(async (keyword) => {
    if (!KAKAO_REST_KEY) {
      throw new Error("VITE_KAKAO_REST_KEY가 없습니다.");
    }

    const query = new URLSearchParams({
      query: keyword,
      x: String(currentPosition.lng),
      y: String(currentPosition.lat),
      radius: "2000",
      sort: "distance",
    });
    const response = await fetch(`https://dapi.kakao.com/v2/local/search/keyword.json?${query}`, {
      headers: { Authorization: `KakaoAK ${KAKAO_REST_KEY}` },
    });
    const data = await response.json();
    const first = data.documents?.[0];

    if (!first) {
      throw new Error(`목적지 후보를 찾지 못했습니다: ${keyword}`);
    }

    return {
      name: first.place_name || keyword,
      lat: Number(first.y),
      lng: Number(first.x),
    };
  }, [currentPosition]);

  const calculateAndDrawRoute = useCallback(async (point, name) => {
    if (!tactileGeoJson) {
      setStatus("점자블록 데이터 준비 중입니다.");
      return;
    }

    setStatus(`${name} 경로를 계산하고 있습니다.`);
    setDestinationState({ ...point, name });
    setDestinationMarker(point, name);

    const candidates = await buildRouteCandidates(currentPosition, point, tactileGeoJson, poiData);
    setRouteCandidates(candidates);
    drawRouteCandidates(candidates);

    const best = candidates[0];

    if (best) {
      setStatus(`최적 경로 계산 완료: ${best.finalScore}점, ${best.routeLengthMeter}m`);
      await fetch(apiUrl("/api/control/route-candidates"), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          area: "hwagok",
          destination: { name, ...point },
          start: currentPosition,
          candidates,
        }),
      });
    }
  }, [currentPosition, drawRouteCandidates, poiData, setDestinationMarker, tactileGeoJson]);

  const pollControlDestination = useCallback(async () => {
    try {
      const response = await fetch(apiUrl("/api/get_destination"));

      if (!response.ok) {
        return;
      }

      const data = await response.json();
      const name = data.destination || "";

      if (!name || name === destinationNameRef.current) {
        return;
      }

      destinationNameRef.current = name;

      if (data.lat && data.lng) {
        await calculateAndDrawRoute({ lat: Number(data.lat), lng: Number(data.lng) }, name);
      } else {
        const point = await geocodeByKeyword(name);
        await calculateAndDrawRoute({ lat: point.lat, lng: point.lng }, point.name);
      }
    } catch (error) {
      console.warn("목적지 동기화 실패", error);
    }
  }, [calculateAndDrawRoute, geocodeByKeyword]);

  const refreshBackendData = useCallback(async () => {
    try {
      const [riskResponse, detectionResponse, summaryResponse] = await Promise.all([
        fetch(apiUrl("/api/risks?area=hwagok")),
        fetch(apiUrl("/api/admin/detections?area=hwagok&limit=50")),
        fetch(apiUrl("/api/admin/summary?area=hwagok")),
      ]);
      const riskData = await riskResponse.json();
      const detectionData = await detectionResponse.json();
      const summaryData = await summaryResponse.json();
      setRisks(riskData.items || []);
      setDetections(detectionData.items || []);
      setSummary(summaryData);
      drawRiskMarkers(riskData.items || []);
      drawDetections(detectionData.items || []);
    } catch (error) {
      console.warn("백엔드 데이터 갱신 실패", error);
    }
  }, [drawDetections, drawRiskMarkers]);

  useEffect(() => {
    if (!KAKAO_JS_KEY) {
      setStatus("VITE_KAKAO_JS_KEY가 없습니다.");
      return;
    }

    const script = document.createElement("script");
    script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${KAKAO_JS_KEY}&autoload=false&libraries=services`;
    script.async = true;
    script.onload = () => {
      window.kakao.maps.load(async () => {
        const map = new window.kakao.maps.Map(mapDivRef.current, {
          center: toLatLng(DEFAULT_POSITION),
          level: 3,
        });
        mapRef.current = map;
        setCurrentMarker(DEFAULT_POSITION);

        navigator.geolocation?.getCurrentPosition(
          (position) => {
            const point = {
              lat: position.coords.latitude,
              lng: position.coords.longitude,
            };
            setCurrentPosition(point);
            setCurrentMarker(point);
            map.setCenter(toLatLng(point));
          },
          () => {},
          { enableHighAccuracy: true, timeout: 8000 }
        );

        try {
          const response = await fetch(apiUrl("/api/tactile-blocks?area=hwagok"));
          const geoJson = await response.json();
          setTactileGeoJson(geoJson);
          drawTactileBlocks(geoJson);
          setStatus("관제 지도를 준비했습니다.");
        } catch (error) {
          setStatus("점자블록 데이터를 불러오지 못했습니다.");
        }

        window.kakao.maps.event.addListener(map, "idle", fetchCrosswalks);
        fetchCrosswalks();
      });
    };
    document.head.appendChild(script);

    return () => {
      document.head.removeChild(script);
    };
  }, [drawTactileBlocks, fetchCrosswalks, setCurrentMarker]);

  useEffect(() => {
    const id = window.setInterval(pollControlDestination, 1500);
    return () => window.clearInterval(id);
  }, [pollControlDestination]);

  useEffect(() => {
    refreshBackendData();
    const id = window.setInterval(refreshBackendData, 2500);
    return () => window.clearInterval(id);
  }, [refreshBackendData]);

  return (
    <div className="control-shell">
      <div ref={mapDivRef} className="map" />

      <aside className="panel">
        <div>
          <p className="eyebrow">누니 관제 웹</p>
          <h1>화곡 시범구역</h1>
          <p className="status">{status}</p>
        </div>

        <div className="metrics">
          <div>
            <span>점자블록</span>
            <strong>{summary?.tactileBlockCount ?? "-"}</strong>
          </div>
          <div>
            <span>위험 지점</span>
            <strong>{summary?.riskCount ?? "-"}</strong>
          </div>
          <div>
            <span>앱 감지</span>
            <strong>{summary?.mobileDetectionLogCount ?? "-"}</strong>
          </div>
        </div>

        <section>
          <div className="section-title">
            <h2>현재 목적지</h2>
          </div>
          <p className="plain">
            {destination ? `${destination.name} (${destination.lat.toFixed(5)}, ${destination.lng.toFixed(5)})` : "앱에서 목적지를 설정하면 표시됩니다."}
          </p>
        </section>

        <section>
          <div className="section-title">
            <h2>추천 경로</h2>
            <span>{routeCandidates.length}개</span>
          </div>
          <div className="list">
            {routeCandidates.length === 0 && <p className="empty">아직 계산된 경로가 없습니다.</p>}
            {routeCandidates.map((candidate) => (
              <article className="item" key={candidate.id}>
                <b>{candidate.name}</b>
                <p>{candidate.reason}</p>
                <div className="chips">
                  <span>{candidate.finalScore}점</span>
                  <span>{candidate.routeLengthMeter}m</span>
                  <span>점자 {candidate.tactileRatioPercent}%</span>
                  <span>횡단 {candidate.crosswalkCount}</span>
                </div>
              </article>
            ))}
          </div>
        </section>

        <section>
          <div className="section-title">
            <h2>위험 지점</h2>
            <span>{risks.filter((risk) => risk.status === "open").length} open</span>
          </div>
          <div className="list compact">
            {risks.map((risk) => (
              <article className="item" key={risk.id}>
                <b>{risk.title}</b>
                <p>{risk.level} · {risk.status}</p>
              </article>
            ))}
          </div>
        </section>
      </aside>

      <div className={`stream ${streamVisible ? "" : "collapsed"}`}>
        <button type="button" onClick={() => setStreamVisible((value) => !value)}>
          {streamVisible ? "스트림 숨기기" : "스트림 보기"}
        </button>
        {streamVisible && (
          <>
            <div className="stream-title">현장 스마트폰 시점</div>
            <img src={apiUrl("/video_feed")} alt="YOLO stream" />
            <p>앱에서 "누니야 스트리밍 모드 켜줘"라고 말하면 표시됩니다.</p>
          </>
        )}
      </div>
    </div>
  );
}
