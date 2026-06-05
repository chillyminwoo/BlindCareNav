import * as turf from "@turf/turf";

/* =========================
   거리 계산 (m)
========================= */
const distanceMeters = (a, b) => {
  return turf.distance(
    turf.point([a.lng, a.lat]),
    turf.point([b.lng, b.lat]),
    { units: "meters" }
  );
};

/* =========================
   TMAP ROUTE (FIXED)
========================= */
export const fetchTmapRoute = async (start, end) => {
  const appKey = import.meta.env.VITE_TMAP_APP_KEY;

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

  if (!response.ok) throw new Error("Tmap API 오류");

  const json = await response.json();

  const coords = [];

  json.features.forEach((f) => {
    if (f.geometry.type === "LineString") {
      f.geometry.coordinates.forEach((c) => {
        coords.push([c[0], c[1]]);
      });
    }
  });

  return coords;
};

/* =========================
   ROUTE LENGTH 계산 (추가 FIX)
========================= */
export const getRouteLengthMeters = (coords) => {
  if (!coords || coords.length < 2) return 0;

  let total = 0;

  for (let i = 0; i < coords.length - 1; i++) {
    total += distanceMeters(
      { lng: coords[i][0], lat: coords[i][1] },
      { lng: coords[i + 1][0], lat: coords[i + 1][1] }
    );
  }

  return Math.round(total);
};

/* =========================
   tactile score
========================= */
export const calcTactileScore = (routeCoords, tactileGeoJson) => {
  if (!routeCoords?.length) {
    return {
      routeLengthMeter: 0,
      tactileLengthMeter: 0,
      tactileRatioPercent: 0,
    };
  }

  const routeLine = turf.lineString(routeCoords);
  const routeLengthKm = turf.length(routeLine);

  const routeChunks = turf.lineChunk(routeLine, 0.01);

  let tactileLengthKm = 0;

  routeChunks.features.forEach((chunk) => {
    const center = turf.centroid(chunk);

    const isNear = tactileGeoJson?.features?.some((f) => {
      if (f.geometry.type !== "LineString") return false;

      const buffer = turf.buffer(f, 0.008);
      return turf.booleanPointInPolygon(center, buffer);
    });

    if (isNear) tactileLengthKm += turf.length(chunk);
  });

  return {
    routeLengthMeter: Math.round(routeLengthKm * 1000),
    tactileLengthMeter: Math.round(tactileLengthKm * 1000),
    tactileRatioPercent: Math.round(
      routeLengthKm ? (tactileLengthKm / routeLengthKm) * 100 : 0
    ),
  };
};

/* =========================
   POI SCORE SYSTEM
========================= */
const calcCrosswalkScore = (routeCoords, poi) => {
  let score = 0;

  poi.crosswalks?.forEach((cw) => {
    const minDist = Math.min(
      ...routeCoords.map((p) =>
        distanceMeters({ lat: p[1], lng: p[0] }, cw)
      )
    );

    if (minDist < 20) score += 10;
    else if (minDist < 50) score += 5;
  });

  return Math.min(100, score);
};

const calcTrafficLightRisk = (routeCoords, poi) => {
  let risk = 0;

  poi.trafficLights?.forEach((tl) => {
    const minDist = Math.min(
      ...routeCoords.map((p) =>
        distanceMeters({ lat: p[1], lng: p[0] }, tl)
      )
    );

    if (minDist < 10) risk += 15;
    else if (minDist < 30) risk += 7;
  });

  return Math.min(100, risk);
};

const calcDangerZoneRisk = (routeCoords, poi) => {
  let risk = 0;

  poi.dangerZones?.forEach((dz) => {
    const minDist = Math.min(
      ...routeCoords.map((p) =>
        distanceMeters({ lat: p[1], lng: p[0] }, dz)
      )
    );

    if (minDist < 30) risk += 20;
  });

  return Math.min(100, risk);
};

const calcDistanceScore = (routeLength, directLength) => {
  if (!routeLength || !directLength) return 0;
  const ratio = directLength / routeLength;
  return Math.min(100, ratio * 100);
};

/* =========================
   FINAL SCORE
========================= */
export const calcAISafetyScore = (
  routeCoords,
  poi,
  routeLength,
  directLength,
  tactileGeoJson
) => {
  const tactileObj = calcTactileScore(routeCoords, tactileGeoJson);
  const tactile = tactileObj.tactileRatioPercent;

  const crosswalk = calcCrosswalkScore(routeCoords, poi);
  const trafficRisk = calcTrafficLightRisk(routeCoords, poi);
  const danger = calcDangerZoneRisk(routeCoords, poi);
  const distance = calcDistanceScore(routeLength, directLength);

  const finalScore =
    tactile * 0.45 +
    crosswalk * 0.25 +
    distance * 0.15 -
    trafficRisk * 0.1 -
    danger * 0.05;

  return {
    finalScore: Math.max(0, Math.min(100, Math.round(finalScore))),
    tactile,
    crosswalk,
    trafficRisk,
    danger,
    distance,
  };
};

export const calcFinalScore = calcAISafetyScore;