/* eslint-disable react-hooks/exhaustive-deps */
import { useEffect, useRef, useState } from "react";
import * as turf from "@turf/turf";
import { API_BASE_URL } from "./services/api";

const DEFAULT_CENTER = { lat: 37.54167, lng: 126.84028 };

function waitForTmapReady() {
  return new Promise((resolve, reject) => {
    const startedAt = Date.now();
    const timeoutMs = 8000;

    const timerId = window.setInterval(() => {
      const tmap = window.Tmapv2;

      if (tmap?.Map && tmap?.Marker && tmap?.Polyline) {
        window.clearInterval(timerId);
        resolve();
        return;
      }

      if (Date.now() - startedAt > timeoutMs) {
        window.clearInterval(timerId);
        reject(new Error("Tmap 지도 SDK가 완전히 초기화되지 않았습니다."));
      }
    }, 50);
  });
}

function loadTmapScript() {
  if (window.Tmapv2) {
    return waitForTmapReady();
  }

  const appKey = import.meta.env.VITE_TMAP_APP_KEY;

  if (!appKey) {
    return Promise.reject(
      new Error("VITE_TMAP_APP_KEY가 설정되어 있지 않습니다.")
    );
  }

  return waitForTmapReady();
  /*
  tmapScriptPromise = new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = `https://apis.openapi.sk.com/tmap/jsv2?version=1&appKey=${encodeURIComponent(
      appKey
    )}`;
    script.async = true;
    script.onload = () => {
      waitForTmapReady().then(resolve).catch(reject);
    };
    script.onerror = () => reject(new Error("Tmap 지도 스크립트를 불러오지 못했습니다."));
    document.head.appendChild(script);
  });

  return tmapScriptPromise;
  */
}

function createTmapLatLng(lat, lng) {
  const LatLng = window.Tmapv2?.LatLng;

  if (!LatLng) {
    throw new Error("Tmapv2.LatLng를 찾을 수 없습니다.");
  }

  try {
    return new LatLng(lat, lng);
  } catch (error) {
    try {
      return LatLng(lat, lng);
    } catch {
      throw error;
    }
  }
}

function getLatLng(latLng) {
  const lat = typeof latLng.lat === "function" ? latLng.lat() : latLng._lat;
  const lng = typeof latLng.lng === "function" ? latLng.lng() : latLng._lng;

  return { lat, lng };
}

function formatPoint(point) {
  if (!point) {
    return "";
  }

  return `${point.lat.toFixed(6)}, ${point.lng.toFixed(6)}`;
}

export default function TmapView({
  startPoint = null,
  endPoint = null,
  destinationName = "",
  risks = [],
  mode = "user",
  showLegacyControls = false,
  onRouteCandidates,
  onRouteError,
}) {
  const mapDivRef = useRef(null);
  const mapRef = useRef(null);
  const startMarkerRef = useRef(null);
  const endMarkerRef = useRef(null);
  const routePolylineRefs = useRef([]);
  const tactilePolylineRefs = useRef([]);
  const riskMarkerRefs = useRef([]);
  const tactileGeoJsonRef = useRef(null);
  const internalStartRef = useRef(null);
  const internalEndRef = useRef(null);

  const [mapReady, setMapReady] = useState(false);
  const [tactileReady, setTactileReady] = useState(false);
  const [internalStart, setInternalStart] = useState(null);
  const [internalEnd, setInternalEnd] = useState(null);
  const [routeCandidates, setRouteCandidates] = useState([]);
  const [errorMsg, setErrorMsg] = useState("");
  const [guideMsg, setGuideMsg] = useState("점자블록 지도를 준비하고 있습니다.");

  const activeStart = startPoint || internalStart;
  const activeEnd = endPoint || internalEnd;

  const drawPolyline = (map, coords, color, weight, opacity = 0.9) => {
    const path = coords.map(([lng, lat]) => {
      return createTmapLatLng(lat, lng);
    });

    return new window.Tmapv2.Polyline({
      path,
      strokeColor: color,
      strokeWeight: weight,
      strokeOpacity: opacity,
      map,
    });
  };

  const clearRoutePolylines = () => {
    routePolylineRefs.current.forEach((polyline) => {
      polyline.setMap(null);
    });
    routePolylineRefs.current = [];
  };

  const clearRoutes = () => {
    clearRoutePolylines();
    setRouteCandidates([]);
    setErrorMsg("");
  };

  const clearPointMarkers = () => {
    if (startMarkerRef.current) {
      startMarkerRef.current.setMap(null);
      startMarkerRef.current = null;
    }

    if (endMarkerRef.current) {
      endMarkerRef.current.setMap(null);
      endMarkerRef.current = null;
    }
  };

  const addPointMarker = (point, title) => {
    return new window.Tmapv2.Marker({
      position: createTmapLatLng(point.lat, point.lng),
      map: mapRef.current,
      title,
    });
  };

  const drawPointMarkers = (start, end) => {
    if (!mapRef.current) {
      return;
    }

    clearPointMarkers();

    if (start) {
      startMarkerRef.current = addPointMarker(start, "출발지");
    }

    if (end) {
      endMarkerRef.current = addPointMarker(end, destinationName || "목적지");
    }
  };

  const drawTactileBlocks = (map, tactileGeoJson) => {
    tactilePolylineRefs.current.forEach((polyline) => {
      polyline.setMap(null);
    });
    tactilePolylineRefs.current = [];

    tactileGeoJson.features.forEach((feature) => {
      if (feature.geometry.type !== "LineString") {
        return;
      }

      const polyline = drawPolyline(map, feature.geometry.coordinates, "#f2c200", 8, 0.9);
      tactilePolylineRefs.current.push(polyline);
    });
  };

  const drawRiskMarkers = () => {
    if (!mapRef.current) {
      return;
    }

    riskMarkerRefs.current.forEach((marker) => {
      marker.setMap(null);
    });
    riskMarkerRefs.current = [];

    risks.forEach((risk) => {
      const marker = new window.Tmapv2.Marker({
        position: createTmapLatLng(risk.lat, risk.lng),
        map: mapRef.current,
        title: risk.title,
      });
      riskMarkerRefs.current.push(marker);
    });
  };

  const fetchTmapRoute = async (start, end) => {
    const appKey = import.meta.env.VITE_TMAP_APP_KEY;

    if (!appKey) {
      throw new Error("VITE_TMAP_APP_KEY가 설정되어 있지 않습니다.");
    }

    const response = await fetch(
      "https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1",
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          appKey,
        },
        body: JSON.stringify({
          startX: start.lng,
          startY: start.lat,
          endX: end.lng,
          endY: end.lat,
          reqCoordType: "WGS84GEO",
          resCoordType: "WGS84GEO",
          startName: "출발지",
          endName: destinationName || "목적지",
          searchOption: "0",
          sort: "index",
        }),
      }
    );

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Tmap 보행 경로 API 오류: ${response.status} ${errorText}`);
    }

    const routeJson = await response.json();

    const routeCoords = routeJson.features
      .filter((feature) => feature.geometry.type === "LineString")
      .flatMap((feature) => feature.geometry.coordinates);

    if (routeCoords.length < 2) {
      throw new Error("Tmap에서 보행 경로 좌표를 받지 못했습니다.");
    }

    return routeCoords;
  };

  const calcTactileScore = (routeCoords, tactileGeoJson) => {
    const routeLine = turf.lineString(routeCoords);
    const routeLengthKm = turf.length(routeLine, { units: "kilometers" });
    const routeChunks = turf.lineChunk(routeLine, 0.01, {
      units: "kilometers",
    });

    let tactileLengthKm = 0;

    routeChunks.features.forEach((chunk) => {
      const chunkCenter = turf.centroid(chunk);

      const isNearTactile = tactileGeoJson.features.some((feature) => {
        if (feature.geometry.type !== "LineString") {
          return false;
        }

        const tactileBuffer = turf.buffer(feature, 0.008, {
          units: "kilometers",
        });

        return turf.booleanPointInPolygon(chunkCenter, tactileBuffer);
      });

      if (isNearTactile) {
        tactileLengthKm += turf.length(chunk, { units: "kilometers" });
      }
    });

    const tactileRatio =
      routeLengthKm === 0 ? 0 : tactileLengthKm / routeLengthKm;

    return {
      routeLengthMeter: Math.round(routeLengthKm * 1000),
      tactileLengthMeter: Math.round(tactileLengthKm * 1000),
      tactileRatioPercent: Math.round(tactileRatio * 100),
    };
  };

  const calcFinalScore = (candidateScore, directLengthMeter) => {
    const candidateLengthMeter = candidateScore.routeLengthMeter;
    const detourRatio = candidateLengthMeter / directLengthMeter;
    const tactileScore = candidateScore.tactileRatioPercent * 0.6;
    const distanceEfficiencyScore =
      Math.min(100, (directLengthMeter / candidateLengthMeter) * 100) * 0.3;

    let detourScore = 0;

    if (detourRatio <= 1.1) {
      detourScore = 100;
    } else if (detourRatio <= 1.3) {
      detourScore = 70;
    } else if (detourRatio <= 1.5) {
      detourScore = 40;
    }

    const finalScore = Math.round(
      tactileScore + distanceEfficiencyScore + detourScore * 0.1
    );

    return {
      finalScore,
      detourRatio: Number(detourRatio.toFixed(2)),
      distanceEfficiencyPercent: Math.round(
        Math.min(100, (directLengthMeter / candidateLengthMeter) * 100)
      ),
    };
  };

  const getTactileWaypoints = (start, end, tactileGeoJson) => {
    const startCoord = [start.lng, start.lat];
    const endCoord = [end.lng, end.lat];

    return tactileGeoJson.features
      .filter((feature) => feature.geometry.type === "LineString")
      .map((feature) => {
        const line = turf.lineString(feature.geometry.coordinates);
        const lineLength = turf.length(line, { units: "kilometers" });
        const midpoint = turf.along(line, lineLength / 2, {
          units: "kilometers",
        });
        const [lng, lat] = midpoint.geometry.coordinates;
        const startDist = turf.distance(turf.point(startCoord), midpoint, {
          units: "kilometers",
        });
        const endDist = turf.distance(midpoint, turf.point(endCoord), {
          units: "kilometers",
        });

        return {
          lat,
          lng,
          memo: feature.properties?.memo || "점자블록 구간",
          estimatedDist: startDist + endDist,
          startDist,
          endDist,
        };
      })
      .filter((waypoint) => waypoint.startDist > 0.03 && waypoint.endDist > 0.03)
      .sort((a, b) => a.estimatedDist - b.estimatedDist)
      .slice(0, 3);
  };

  const mergeRouteCoords = (firstCoords, secondCoords) => {
    return [...firstCoords, ...secondCoords.slice(1)];
  };

  const makeRouteCandidates = async (start, end) => {
    const tactileGeoJson = tactileGeoJsonRef.current;

    if (!tactileGeoJson) {
      throw new Error("점자블록 GeoJSON이 아직 로드되지 않았습니다.");
    }

    const candidates = [];
    const directCoords = await fetchTmapRoute(start, end);
    const directScore = calcTactileScore(directCoords, tactileGeoJson);
    const directLengthMeter = directScore.routeLengthMeter;
    const directFinal = calcFinalScore(directScore, directLengthMeter);

    candidates.push({
      id: "direct",
      name: "후보 1: Tmap 기본 경로",
      reason: `Tmap 기본 보행 경로입니다. 점자블록 인접률 ${directScore.tactileRatioPercent}%, 우회 비율 ${directFinal.detourRatio}배입니다.`,
      coords: directCoords,
      ...directScore,
      ...directFinal,
    });

    const waypoints = getTactileWaypoints(start, end, tactileGeoJson);

    for (let waypointIndex = 0; waypointIndex < waypoints.length; waypointIndex += 1) {
      const waypoint = waypoints[waypointIndex];

      try {
        const firstCoords = await fetchTmapRoute(start, waypoint);
        const secondCoords = await fetchTmapRoute(waypoint, end);
        const mergedCoords = mergeRouteCoords(firstCoords, secondCoords);
        const score = calcTactileScore(mergedCoords, tactileGeoJson);

        if (score.routeLengthMeter > directLengthMeter * 1.5) {
          continue;
        }

        const final = calcFinalScore(score, directLengthMeter);

        candidates.push({
          id: `via-${waypointIndex + 1}`,
          name: `후보 ${waypointIndex + 2}: 점자블록 경유 경로`,
          reason: `${waypoint.memo} 인근을 경유합니다. 점자블록 인접률 ${score.tactileRatioPercent}%, 우회 비율 ${final.detourRatio}배입니다.`,
          waypoint,
          coords: mergedCoords,
          ...score,
          ...final,
        });
      } catch {
        // 일부 경유 후보가 실패해도 기본 경로 추천은 유지한다.
      }
    }

    return candidates.sort((a, b) => {
      if (b.finalScore !== a.finalScore) {
        return b.finalScore - a.finalScore;
      }

      return a.routeLengthMeter - b.routeLengthMeter;
    });
  };

  const drawRouteCandidates = (candidates) => {
    clearRoutePolylines();

    const otherRoutes = candidates.slice(1);
    const bestRoute = candidates[0];

    otherRoutes.forEach((candidate) => {
      const polyline = drawPolyline(mapRef.current, candidate.coords, "#7d8590", 5, 0.52);
      routePolylineRefs.current.push(polyline);
    });

    if (bestRoute) {
      const bestPolyline = drawPolyline(mapRef.current, bestRoute.coords, "#006bd6", 8, 0.96);
      routePolylineRefs.current.push(bestPolyline);
    }
  };

  const requestCandidates = async (start, end) => {
    try {
      clearRoutes();
      setGuideMsg("추천 경로를 계산하고 있습니다.");

      const candidates = await makeRouteCandidates(start, end);

      if (candidates.length === 0) {
        throw new Error("생성 가능한 후보 경로가 없습니다.");
      }

      drawRouteCandidates(candidates);
      setRouteCandidates(candidates);
      setGuideMsg("추천 경로 계산이 완료되었습니다.");
      onRouteCandidates?.(candidates);
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error);
      const fallback =
        "현재 위치를 다시 확인하거나 가까운 지하철 출구, 공중화장실, 주민센터 같은 주변시설을 목적지로 선택해 보세요.";

      clearRoutePolylines();
      setRouteCandidates([]);
      setErrorMsg(reason);
      setGuideMsg("경로 탐색에 실패했습니다.");
      onRouteError?.({ reason, fallback });
    }
  };

  const resetLegacySelection = () => {
    internalStartRef.current = null;
    internalEndRef.current = null;
    setInternalStart(null);
    setInternalEnd(null);
    clearPointMarkers();
    clearRoutes();
    onRouteCandidates?.([]);
    setGuideMsg("지도에서 출발지를 선택하세요.");
  };

  const handleMapClick = (event) => {
    if (!showLegacyControls) {
      return;
    }

    const clickedPoint = getLatLng(event.latLng);

    if (!internalStartRef.current) {
      internalStartRef.current = clickedPoint;
      setInternalStart(clickedPoint);
      setGuideMsg("지도에서 목적지를 선택하세요.");
      return;
    }

    if (!internalEndRef.current) {
      internalEndRef.current = clickedPoint;
      setInternalEnd(clickedPoint);
      return;
    }

    setGuideMsg("출발지와 목적지가 이미 선택되었습니다. 초기화 후 다시 선택하세요.");
  };

  useEffect(() => {
    let isMounted = true;

    async function initializeMap() {
      try {
        await loadTmapScript();

        if (!isMounted || mapRef.current) {
          return;
        }

        const centerPoint = startPoint || DEFAULT_CENTER;
        const map = new window.Tmapv2.Map(mapDivRef.current, {
          center: createTmapLatLng(centerPoint.lat, centerPoint.lng),
          width: "100%",
          height: "100%",
          zoom: mode === "admin" ? 16 : 17,
        });

        mapRef.current = map;
        setMapReady(true);

        if (showLegacyControls) {
          map.addListener("click", handleMapClick);
          setGuideMsg("지도에서 출발지를 선택하세요.");
        }

        const tactileResponse = await fetch(
          `${API_BASE_URL}/api/tactile-blocks?area=hwagok`
        );

        if (!tactileResponse.ok) {
          throw new Error("점자블록 GeoJSON을 불러오지 못했습니다.");
        }

        const tactileGeoJson = await tactileResponse.json();
        tactileGeoJsonRef.current = tactileGeoJson;
        drawTactileBlocks(map, tactileGeoJson);
        setTactileReady(true);
      } catch (error) {
        const reason = error instanceof Error ? error.message : String(error);
        setErrorMsg(reason);
        setGuideMsg("지도를 준비하지 못했습니다.");
        onRouteError?.({
          reason,
          fallback: "Tmap API 키와 백엔드 서버 실행 상태를 확인하세요.",
        });
      }
    }

    initializeMap();

    return () => {
      isMounted = false;
    };
  }, []);

  useEffect(() => {
    if (!mapReady) {
      return;
    }

    drawRiskMarkers();
  }, [mapReady, risks]);

  useEffect(() => {
    if (!mapReady) {
      return;
    }

    drawPointMarkers(activeStart, activeEnd);

    if (activeStart) {
      mapRef.current.setCenter(
        createTmapLatLng(activeStart.lat, activeStart.lng)
      );
    }

    if (!activeStart || !activeEnd) {
      clearRoutePolylines();
    }
  }, [
    mapReady,
    activeStart?.lat,
    activeStart?.lng,
    activeEnd?.lat,
    activeEnd?.lng,
    destinationName,
  ]);

  useEffect(() => {
    if (!mapReady || !tactileReady || !activeStart || !activeEnd) {
      return;
    }

    const timerId = window.setTimeout(() => {
      requestCandidates(activeStart, activeEnd);
    }, 0);

    return () => window.clearTimeout(timerId);
  }, [
    mapReady,
    tactileReady,
    activeStart?.lat,
    activeStart?.lng,
    activeEnd?.lat,
    activeEnd?.lng,
  ]);

  return (
    <div className={`tmap-shell tmap-shell--${mode}`}>
      <div ref={mapDivRef} className="tmap-canvas" />

      {showLegacyControls && (
        <div className="legacy-map-panel">
          <div className="eyebrow">기존 지도 추천</div>
          <h2>점자블록 기반 후보 경로</h2>
          <p>{guideMsg}</p>

          <button className="button button--secondary" onClick={resetLegacySelection}>
            출발/목적지 초기화
          </button>

          <div className="coordinate-stack">
            <span>출발지 {formatPoint(activeStart) || "미선택"}</span>
            <span>목적지 {formatPoint(activeEnd) || "미선택"}</span>
          </div>

          {errorMsg && (
            <div className="notice notice--danger">
              <strong>탐색 실패</strong>
              <span>{errorMsg}</span>
            </div>
          )}

          {routeCandidates.length > 0 && (
            <div className="route-list">
              <strong>추천 {routeCandidates[0].name}</strong>
              {routeCandidates.map((candidate) => (
                <div className="route-list__item" key={candidate.id}>
                  <span>{candidate.name}</span>
                  <b>{candidate.finalScore}점</b>
                  <small>
                    점자블록 {candidate.tactileRatioPercent}% · 거리{" "}
                    {candidate.routeLengthMeter}m · 우회 {candidate.detourRatio}배
                  </small>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {!showLegacyControls && (errorMsg || guideMsg) && (
        <div className="map-status">
          <span>{errorMsg || guideMsg}</span>
        </div>
      )}
    </div>
  );
}
