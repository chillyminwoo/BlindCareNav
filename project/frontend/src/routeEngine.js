import * as turf from "@turf/turf";

export async function fetchTmapRoute(start, end) {
  const appKey = import.meta.env.VITE_TMAP_APP_KEY;

  if (!appKey) {
    throw new Error("VITE_TMAP_APP_KEY가 없습니다.");
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
        endName: "도착지",
        searchOption: "0",
      }),
    }
  );

  if (!response.ok) {
    throw new Error(`Tmap API 오류 ${response.status}`);
  }

  const json = await response.json();
  const coords = [];

  json.features?.forEach((feature) => {
    if (feature.geometry?.type === "LineString") {
      feature.geometry.coordinates.forEach((coord) => coords.push([coord[0], coord[1]]));
    }
  });

  return coords;
}

export function getRouteLengthMeters(coords) {
  if (!coords || coords.length < 2) {
    return 0;
  }

  return Math.round(turf.length(turf.lineString(coords), { units: "kilometers" }) * 1000);
}

export function extractTactileWaypoints(start, end, tactileGeoJson) {
  const features = tactileGeoJson?.features || [];

  return features
    .filter((feature) => feature.geometry?.type === "LineString")
    .map((feature) => {
      const line = turf.lineString(feature.geometry.coordinates);
      const lineLength = turf.length(line, { units: "kilometers" });
      const midpoint = turf.along(line, lineLength / 2, { units: "kilometers" });
      const [lng, lat] = midpoint.geometry.coordinates;
      const startDist = turf.distance(turf.point([start.lng, start.lat]), midpoint, { units: "kilometers" });
      const endDist = turf.distance(midpoint, turf.point([end.lng, end.lat]), { units: "kilometers" });

      return {
        lat,
        lng,
        memo: feature.properties?.memo || "점자블록 구간",
        estimatedDist: startDist + endDist,
        startDist,
        endDist,
      };
    })
    .filter((point) => point.startDist > 0.02 && point.endDist > 0.02)
    .sort((a, b) => a.estimatedDist - b.estimatedDist)
    .slice(0, 3);
}

export function calculateSafetyScore(coords, totalLength, directLength, tactileGeoJson, poiData) {
  if (!coords || coords.length < 2) {
    return {
      finalScore: 10,
      tactileRatio: 0,
      tactileLength: 0,
      distanceEfficiency: 100,
      crosswalkCount: 0,
    };
  }

  const routeLine = turf.lineString(coords);
  let tactileMatchMeter = 0;

  tactileGeoJson?.features?.forEach((feature) => {
    if (feature.geometry?.type !== "LineString") {
      return;
    }

    const blockLine = turf.lineString(feature.geometry.coordinates);
    const buffer = turf.buffer(routeLine, 0.015, { units: "kilometers" });

    if (turf.lineIntersect(blockLine, buffer).features.length > 0) {
      tactileMatchMeter += Math.round(turf.length(blockLine, { units: "kilometers" }) * 1000 * 0.4);
    }
  });

  tactileMatchMeter = Math.min(tactileMatchMeter, totalLength);
  const tactileRatio = totalLength > 0 ? (tactileMatchMeter / totalLength) * 100 : 0;
  const tactileScore = (tactileRatio / 100) * 45;
  const distanceEfficiency = directLength > 0 ? (directLength / totalLength) * 100 : 100;
  const distanceScore = (distanceEfficiency / 100) * 35;
  const routeBuffer = turf.buffer(routeLine, 0.015, { units: "kilometers" });
  let crosswalkCount = 0;

  poiData?.crosswalks?.forEach((crosswalk) => {
    if (turf.booleanPointInPolygon(turf.point([crosswalk.lng, crosswalk.lat]), routeBuffer)) {
      crosswalkCount += 1;
    }
  });

  const crosswalkScore = Math.min(crosswalkCount * 5, 20);

  return {
    finalScore: Math.max(10, Math.round(tactileScore + distanceScore + crosswalkScore)),
    tactileRatio: Math.round(tactileRatio),
    tactileLength: Math.round(tactileMatchMeter),
    distanceEfficiency: Math.round(distanceEfficiency),
    crosswalkCount,
  };
}

export async function buildRouteCandidates(start, end, tactileGeoJson, poiData) {
  const candidates = [];
  const directCoords = await fetchTmapRoute(start, end);
  const directLength = getRouteLengthMeters(directCoords);
  const directMetrics = calculateSafetyScore(
    directCoords,
    directLength,
    directLength,
    tactileGeoJson,
    poiData
  );

  candidates.push({
    id: "direct",
    name: "후보 1: Tmap 기본 보행 경로",
    reason: `최단 보행 동선입니다. 안전 점수 ${directMetrics.finalScore}점.`,
    coords: directCoords,
    routeLengthMeter: directLength,
    tactileRatioPercent: directMetrics.tactileRatio,
    tactileLengthMeter: directMetrics.tactileLength,
    distanceEfficiencyPercent: directMetrics.distanceEfficiency,
    crosswalkCount: directMetrics.crosswalkCount,
    detourRatio: 1.0,
    finalScore: directMetrics.finalScore,
  });

  for (const [index, waypoint] of extractTactileWaypoints(start, end, tactileGeoJson).entries()) {
    try {
      const first = await fetchTmapRoute(start, waypoint);
      const second = await fetchTmapRoute(waypoint, end);
      const coords = [...first, ...second.slice(1)];
      const routeLength = getRouteLengthMeters(coords);

      if (directLength > 0 && routeLength > directLength * 1.6) {
        continue;
      }

      const metrics = calculateSafetyScore(coords, routeLength, directLength, tactileGeoJson, poiData);

      candidates.push({
        id: `via-${index + 1}`,
        name: `후보 ${index + 2}: 점자블록 연계 경로`,
        reason: `${waypoint.memo} 인근을 경유하는 대안 동선입니다.`,
        coords,
        routeLengthMeter: routeLength,
        tactileRatioPercent: metrics.tactileRatio,
        tactileLengthMeter: metrics.tactileLength,
        distanceEfficiencyPercent: metrics.distanceEfficiency,
        crosswalkCount: metrics.crosswalkCount,
        detourRatio: directLength > 0 ? Number((routeLength / directLength).toFixed(2)) : 1,
        finalScore: metrics.finalScore,
      });
    } catch (error) {
      console.warn("대안 경로 계산 스킵", error);
    }
  }

  return candidates.sort((a, b) => b.finalScore - a.finalScore);
}
