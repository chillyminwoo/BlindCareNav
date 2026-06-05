function getDefaultApiBaseUrl() {
  const devPorts = new Set(["5173", "5174", "5175"]);

  if (devPorts.has(window.location.port)) {
    return `${window.location.protocol}//${window.location.hostname}:8000`;
  }

  return window.location.origin;
}

export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL || getDefaultApiBaseUrl();

export const STREAM_BASE_URL =
  import.meta.env.VITE_STREAM_BASE_URL || API_BASE_URL;

async function fetchJson(path, options = {}) {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers: {
      "Content-Type": "application/json",
      ...(options.headers || {}),
    },
    ...options,
  });

  if (!response.ok) {
    let detail = response.statusText;

    try {
      const errorBody = await response.json();
      detail = errorBody.detail || detail;
    } catch {
      detail = await response.text();
    }

    throw new Error(detail || "API 요청에 실패했습니다.");
  }

  return response.json();
}

export function getRisks(area = "hwagok") {
  return fetchJson(`/api/risks?area=${encodeURIComponent(area)}`);
}

export function getAdminSummary(area = "hwagok") {
  return fetchJson(`/api/admin/summary?area=${encodeURIComponent(area)}`);
}

export function getAdminDetections(area = "hwagok", limit = 50) {
  const params = new URLSearchParams({
    area,
    limit: String(limit),
  });

  return fetchJson(`/api/admin/detections?${params.toString()}`);
}

export function getNearby({ area = "hwagok", lat, lng, type = "all" }) {
  const params = new URLSearchParams({
    area,
    lat: String(lat),
    lng: String(lng),
  });

  if (type && type !== "all") {
    params.set("type", type);
  }

  return fetchJson(`/api/nearby?${params.toString()}`);
}

export function createGuidance(payload) {
  return fetchJson("/api/guidance", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

export function searchPlaces(payload) {
  return fetchJson("/api/places/search", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}
