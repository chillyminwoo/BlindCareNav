import { useEffect, useMemo, useRef, useState } from "react";
import TmapView from "./TmapView";
import {
  createGuidance,
  getAdminSummary,
  getNearby,
  getRisks,
  searchPlaces,
} from "./services/api";
import {
  addFavorite,
  addFavoriteCategory,
  deleteFavoriteById,
  getFavoriteCategories,
  getFavorites,
  removeFavoriteCategory,
} from "./services/favoriteStorage";
import { listenOnce, speak, stopSpeaking } from "./services/speech";

const AREA = "hwagok";
const DEFAULT_LOCATION = { lat: 37.54167, lng: 126.84028 };

const NEARBY_TYPES = [
  { type: "all", label: "전체" },
  { type: "toilet", label: "화장실" },
  { type: "subway", label: "대중교통" },
  { type: "pharmacy", label: "약국" },
  { type: "hospital", label: "병원" },
  { type: "public_office", label: "관공서" },
  { type: "store", label: "편의점" },
];

function haversineMeter(lat1, lng1, lat2, lng2) {
  const earthRadiusMeter = 6371000;
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLng = ((lng2 - lng1) * Math.PI) / 180;
  const radLat1 = (lat1 * Math.PI) / 180;
  const radLat2 = (lat2 * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(radLat1) * Math.cos(radLat2) * Math.sin(dLng / 2) ** 2;
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

  return Math.round(earthRadiusMeter * c);
}

function formatDistance(distanceMeter) {
  if (distanceMeter === undefined || distanceMeter === null) {
    return "";
  }

  if (distanceMeter >= 1000) {
    return `${(distanceMeter / 1000).toFixed(1)}km`;
  }

  return `${distanceMeter}m`;
}

function pointLabel(point) {
  if (!point) {
    return "위치 확인 중";
  }

  return `${point.lat.toFixed(5)}, ${point.lng.toFixed(5)}`;
}

function App() {
  const recognitionRef = useRef(null);
  const [activeView, setActiveView] = useState("user");
  const [currentLocation, setCurrentLocation] = useState(DEFAULT_LOCATION);
  const [locationStatus, setLocationStatus] = useState("GPS 현재 위치를 확인하고 있습니다.");
  const [destinationQuery, setDestinationQuery] = useState("");
  const [searchResults, setSearchResults] = useState([]);
  const [searchStatus, setSearchStatus] = useState("");
  const [selectedDestination, setSelectedDestination] = useState(null);
  const [routeCandidates, setRouteCandidates] = useState([]);
  const [routeFailure, setRouteFailure] = useState(null);
  const [risks, setRisks] = useState([]);
  const [riskStatus, setRiskStatus] = useState("");
  const [nearbyType, setNearbyType] = useState("all");
  const [nearbyPlaces, setNearbyPlaces] = useState([]);
  const [nearbyStatus, setNearbyStatus] = useState("");
  const [guidance, setGuidance] = useState(null);
  const [favorites, setFavorites] = useState(() => getFavorites());
  const [categories, setCategories] = useState(() => getFavoriteCategories());
  const [selectedCategory, setSelectedCategory] = useState("기타");
  const [categoryInput, setCategoryInput] = useState("");
  const [adminSummary, setAdminSummary] = useState(null);
  const [adminPlaces, setAdminPlaces] = useState([]);
  const [adminStatus, setAdminStatus] = useState("");

  const bestRoute = routeCandidates[0] || null;

  const sortedRisks = useMemo(() => {
    return risks
      .map((risk) => ({
        ...risk,
        distanceMeter: haversineMeter(
          currentLocation.lat,
          currentLocation.lng,
          risk.lat,
          risk.lng
        ),
      }))
      .sort((a, b) => a.distanceMeter - b.distanceMeter);
  }, [currentLocation.lat, currentLocation.lng, risks]);

  const routeRiskItems = useMemo(() => {
    return sortedRisks.filter((risk) => risk.status === "open").slice(0, 3);
  }, [sortedRisks]);

  function requestCurrentLocation() {
    setLocationStatus("GPS 현재 위치를 확인하고 있습니다.");

    if (!navigator.geolocation) {
      setCurrentLocation(DEFAULT_LOCATION);
      setLocationStatus("GPS를 사용할 수 없어 화곡역 기준 위치를 사용합니다.");
      return;
    }

    navigator.geolocation.getCurrentPosition(
      (position) => {
        const nextLocation = {
          lat: position.coords.latitude,
          lng: position.coords.longitude,
        };
        setCurrentLocation(nextLocation);
        setLocationStatus("GPS 현재 위치를 출발지로 사용합니다.");
      },
      () => {
        setCurrentLocation(DEFAULT_LOCATION);
        setLocationStatus("GPS 권한 또는 신호 문제로 화곡역 기준 위치를 사용합니다.");
      },
      {
        enableHighAccuracy: true,
        timeout: 8000,
        maximumAge: 60000,
      }
    );
  }

  async function handleSearch(keyword = destinationQuery) {
    const nextKeyword = keyword.trim();
    setSearchStatus("");
    setSearchResults([]);

    if (!nextKeyword) {
      setSearchStatus("목적지를 입력하세요.");
      return;
    }

    try {
      setSearchStatus("목적지를 검색하고 있습니다.");
      const result = await searchPlaces({
        area: AREA,
        keyword: nextKeyword,
        currentLocation,
      });

      setSearchResults(result.candidates);

      if (result.candidates.length === 0) {
        setSearchStatus("검색 결과가 없습니다. 주변시설 목록에서 가까운 장소를 선택하세요.");
      } else {
        setSearchStatus(`${result.candidates.length}개 결과를 찾았습니다.`);
      }
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error);
      setSearchStatus(`검색 실패: ${reason}`);
    }
  }

  function selectDestination(place) {
    setSelectedDestination(place);
    setDestinationQuery(place.name);
    setSearchResults([]);
    setRouteCandidates([]);
    setRouteFailure(null);
    setGuidance(null);
    speak(`${place.name}을 목적지로 설정했습니다.`);
  }

  function startVoiceInput() {
    if (recognitionRef.current) {
      recognitionRef.current.stop();
      recognitionRef.current = null;
    }

    setSearchStatus("음성 입력을 듣고 있습니다.");

    recognitionRef.current = listenOnce({
      onResult: (transcript) => {
        setDestinationQuery(transcript);
        setSearchStatus(`음성 입력: ${transcript}`);
        handleSearch(transcript);
        recognitionRef.current = null;
      },
      onError: (message) => {
        setSearchStatus(message);
        recognitionRef.current = null;
      },
    });
  }

  function handleRouteCandidates(candidates) {
    setRouteCandidates(candidates);
    setRouteFailure(null);
  }

  function handleRouteError(error) {
    setRouteFailure(error);
    setRouteCandidates([]);
    setGuidance(null);
  }

  function addSelectedFavorite() {
    if (!selectedDestination) {
      return;
    }

    setFavorites(addFavorite(selectedDestination, selectedCategory));
  }

  function addCategory() {
    const nextCategories = addFavoriteCategory(categoryInput);
    setCategories(nextCategories);
    setSelectedCategory(categoryInput.trim() || selectedCategory);
    setCategoryInput("");
  }

  function removeCategory(name) {
    const nextCategories = removeFavoriteCategory(name);
    setCategories(nextCategories);

    if (selectedCategory === name) {
      setSelectedCategory(nextCategories[0] || "기타");
    }
  }

  function readGuidance() {
    if (guidance?.message) {
      speak(guidance.message);
      return;
    }

    if (bestRoute) {
      speak(
        `추천 경로는 약 ${bestRoute.routeLengthMeter}미터입니다. 점자블록 인접률은 ${bestRoute.tactileRatioPercent}퍼센트입니다.`
      );
    }
  }

  useEffect(() => {
    const timerId = window.setTimeout(() => {
      requestCurrentLocation();
    }, 0);

    return () => window.clearTimeout(timerId);
  }, []);

  useEffect(() => {
    async function loadRisks() {
      try {
        const result = await getRisks(AREA);
        setRisks(result.items);
        setRiskStatus("");
      } catch (error) {
        const reason = error instanceof Error ? error.message : String(error);
        setRiskStatus(`위험 지점 로드 실패: ${reason}`);
      }
    }

    loadRisks();
  }, []);

  useEffect(() => {
    async function loadNearby() {
      try {
        setNearbyStatus("주변시설을 불러오고 있습니다.");
        const result = await getNearby({
          area: AREA,
          lat: currentLocation.lat,
          lng: currentLocation.lng,
          type: nearbyType,
        });
        setNearbyPlaces(result.items);
        setNearbyStatus("");
      } catch (error) {
        const reason = error instanceof Error ? error.message : String(error);
        setNearbyStatus(`주변시설 로드 실패: ${reason}`);
      }
    }

    loadNearby();
  }, [currentLocation.lat, currentLocation.lng, nearbyType]);

  useEffect(() => {
    async function loadAdmin() {
      try {
        const [summaryResult, placesResult] = await Promise.all([
          getAdminSummary(AREA),
          getNearby({
            area: AREA,
            lat: DEFAULT_LOCATION.lat,
            lng: DEFAULT_LOCATION.lng,
            type: "all",
          }),
        ]);
        setAdminSummary(summaryResult);
        setAdminPlaces(placesResult.items);
        setAdminStatus("");
      } catch (error) {
        const reason = error instanceof Error ? error.message : String(error);
        setAdminStatus(`관제 데이터 로드 실패: ${reason}`);
      }
    }

    loadAdmin();
  }, []);

  useEffect(() => {
    async function loadGuidance() {
      if (!bestRoute) {
        return;
      }

      try {
        const result = await createGuidance({
          currentLocation,
          nextStep: {
            distanceMeter: Math.min(30, bestRoute.routeLengthMeter),
            direction: "straight",
          },
          risks: routeRiskItems.map((risk) => ({
            type: risk.type,
            level: risk.level,
            distanceMeter: risk.distanceMeter,
          })),
        });
        setGuidance(result);
      } catch {
        setGuidance({
          level: "info",
          message: `추천 경로는 약 ${bestRoute.routeLengthMeter}미터입니다. 점자블록 인접률은 ${bestRoute.tactileRatioPercent}퍼센트입니다.`,
        });
      }
    }

    loadGuidance();
  }, [bestRoute, currentLocation, routeRiskItems]);

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand-block">
          <strong>A-eye</strong>
          <span>화곡 시범구역 보행 내비게이션</span>
        </div>
        <nav className="view-tabs" aria-label="화면 선택">
          <button
            className={activeView === "user" ? "is-active" : ""}
            onClick={() => setActiveView("user")}
          >
            사용자 앱
          </button>
          <button
            className={activeView === "admin" ? "is-active" : ""}
            onClick={() => setActiveView("admin")}
          >
            관제 웹
          </button>
          <button
            className={activeView === "map" ? "is-active" : ""}
            onClick={() => setActiveView("map")}
          >
            기존 지도 추천
          </button>
        </nav>
      </header>

      {activeView === "user" && (
        <UserAppView
          currentLocation={currentLocation}
          locationStatus={locationStatus}
          destinationQuery={destinationQuery}
          searchResults={searchResults}
          searchStatus={searchStatus}
          selectedDestination={selectedDestination}
          bestRoute={bestRoute}
          routeCandidates={routeCandidates}
          routeFailure={routeFailure}
          risks={risks}
          sortedRisks={sortedRisks}
          routeRiskItems={routeRiskItems}
          nearbyType={nearbyType}
          nearbyPlaces={nearbyPlaces}
          nearbyStatus={nearbyStatus}
          guidance={guidance}
          favorites={favorites}
          categories={categories}
          selectedCategory={selectedCategory}
          categoryInput={categoryInput}
          onLocationRefresh={requestCurrentLocation}
          onDestinationQueryChange={setDestinationQuery}
          onSearch={() => handleSearch()}
          onVoiceInput={startVoiceInput}
          onSelectDestination={selectDestination}
          onNearbyTypeChange={setNearbyType}
          onRouteCandidates={handleRouteCandidates}
          onRouteError={handleRouteError}
          onReadGuidance={readGuidance}
          onStopGuidance={stopSpeaking}
          onFavoriteAdd={addSelectedFavorite}
          onFavoriteDelete={(id) => setFavorites(deleteFavoriteById(id))}
          onCategoryChange={setSelectedCategory}
          onCategoryInputChange={setCategoryInput}
          onCategoryAdd={addCategory}
          onCategoryRemove={removeCategory}
        />
      )}

      {activeView === "admin" && (
        <AdminDashboardView
          summary={adminSummary}
          risks={risks}
          places={adminPlaces}
          status={adminStatus || riskStatus}
        />
      )}

      {activeView === "map" && (
        <main className="map-only-layout">
          <TmapView
            risks={risks}
            mode="legacy"
            showLegacyControls
            onRouteCandidates={handleRouteCandidates}
            onRouteError={handleRouteError}
          />
        </main>
      )}
    </div>
  );
}

function UserAppView({
  currentLocation,
  locationStatus,
  destinationQuery,
  searchResults,
  searchStatus,
  selectedDestination,
  bestRoute,
  routeCandidates,
  routeFailure,
  risks,
  sortedRisks,
  routeRiskItems,
  nearbyType,
  nearbyPlaces,
  nearbyStatus,
  guidance,
  favorites,
  categories,
  selectedCategory,
  categoryInput,
  onLocationRefresh,
  onDestinationQueryChange,
  onSearch,
  onVoiceInput,
  onSelectDestination,
  onNearbyTypeChange,
  onRouteCandidates,
  onRouteError,
  onReadGuidance,
  onStopGuidance,
  onFavoriteAdd,
  onFavoriteDelete,
  onCategoryChange,
  onCategoryInputChange,
  onCategoryAdd,
  onCategoryRemove,
}) {
  return (
    <main className="user-layout">
      <section className="user-primary">
        <div className="status-strip">
          <div>
            <span className="eyebrow">출발지</span>
            <strong>GPS 현재 위치</strong>
            <small>{pointLabel(currentLocation)}</small>
          </div>
          <button className="button button--secondary" onClick={onLocationRefresh}>
            현재 위치 갱신
          </button>
        </div>
        <p className="muted">{locationStatus}</p>

        <div className="destination-box">
          <label htmlFor="destination-input">목적지</label>
          <div className="search-row">
            <input
              id="destination-input"
              value={destinationQuery}
              placeholder="예: 화곡역 3번 출구"
              onChange={(event) => onDestinationQueryChange(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  onSearch();
                }
              }}
            />
            <button className="button button--primary" onClick={onSearch}>
              검색
            </button>
            <button className="button button--secondary" onClick={onVoiceInput}>
              음성 입력
            </button>
          </div>
          {searchStatus && <p className="inline-status">{searchStatus}</p>}
        </div>

        {searchResults.length > 0 && (
          <div className="result-list">
            {searchResults.map((place) => (
              <button
                className="result-item"
                key={place.id}
                onClick={() => onSelectDestination(place)}
              >
                <span>
                  <strong>{place.name}</strong>
                  <small>{place.address}</small>
                </span>
                <b>{formatDistance(place.distanceMeter)}</b>
              </button>
            ))}
          </div>
        )}

        <div className="route-summary">
          <div className="summary-head">
            <div>
              <span className="eyebrow">추천 경로</span>
              <h1>{selectedDestination ? selectedDestination.name : "목적지를 선택하세요"}</h1>
            </div>
            <button
              className="button button--primary button--large"
              onClick={onReadGuidance}
              disabled={!bestRoute && !guidance}
            >
              안내 시작
            </button>
          </div>

          {bestRoute && (
            <div className="metric-grid">
              <div>
                <span>예상 거리</span>
                <strong>{bestRoute.routeLengthMeter}m</strong>
              </div>
              <div>
                <span>점자블록 인접률</span>
                <strong>{bestRoute.tactileRatioPercent}%</strong>
              </div>
              <div>
                <span>위험 안내</span>
                <strong>{routeRiskItems.length}개</strong>
              </div>
              <div>
                <span>우회 비율</span>
                <strong>{bestRoute.detourRatio}배</strong>
              </div>
            </div>
          )}

          {guidance?.message && (
            <div className={`notice notice--${guidance.level}`}>
              <strong>음성 안내</strong>
              <span>{guidance.message}</span>
            </div>
          )}

          {routeFailure && (
            <div className="notice notice--danger">
              <strong>경로 탐색 실패</strong>
              <span>이유: {routeFailure.reason}</span>
              <span>대안: {routeFailure.fallback}</span>
            </div>
          )}

          <div className="action-row">
            <button className="button button--secondary" onClick={onReadGuidance}>
              다시 듣기
            </button>
            <button className="button button--secondary" onClick={onStopGuidance}>
              안내 종료
            </button>
            <button
              className="button button--secondary"
              onClick={onFavoriteAdd}
              disabled={!selectedDestination}
            >
              즐겨찾기 추가
            </button>
          </div>
        </div>

        <div className="split-panels">
          <section className="flat-panel">
            <div className="panel-head">
              <h2>주변시설</h2>
              <span>{nearbyStatus}</span>
            </div>
            <div className="chip-row">
              {NEARBY_TYPES.map((item) => (
                <button
                  className={nearbyType === item.type ? "chip is-active" : "chip"}
                  key={item.type}
                  onClick={() => onNearbyTypeChange(item.type)}
                >
                  {item.label}
                </button>
              ))}
            </div>
            <div className="compact-list">
              {nearbyPlaces.slice(0, 6).map((place) => (
                <button
                  className="compact-item"
                  key={place.id}
                  onClick={() => onSelectDestination(place)}
                >
                  <span>
                    <strong>{place.name}</strong>
                    <small>{place.description}</small>
                  </span>
                  <b>{formatDistance(place.distanceMeter)}</b>
                </button>
              ))}
            </div>
          </section>

          <section className="flat-panel">
            <div className="panel-head">
              <h2>즐겨찾기</h2>
              <span>{favorites.length}개</span>
            </div>
            <div className="category-row">
              <select
                value={selectedCategory}
                onChange={(event) => onCategoryChange(event.target.value)}
              >
                {categories.map((category) => (
                  <option key={category} value={category}>
                    {category}
                  </option>
                ))}
              </select>
              <input
                value={categoryInput}
                placeholder="카테고리 추가"
                onChange={(event) => onCategoryInputChange(event.target.value)}
              />
              <button className="button button--secondary" onClick={onCategoryAdd}>
                추가
              </button>
            </div>
            <div className="chip-row">
              {categories.map((category) => (
                <button
                  className="chip chip--light"
                  key={category}
                  onClick={() => onCategoryRemove(category)}
                >
                  {category} 삭제
                </button>
              ))}
            </div>
            <div className="compact-list">
              {favorites.length === 0 && <p className="muted">저장된 장소가 없습니다.</p>}
              {favorites.map((favorite) => (
                <div className="compact-item compact-item--static" key={favorite.id}>
                  <span>
                    <strong>{favorite.name}</strong>
                    <small>{favorite.category}</small>
                  </span>
                  <div className="item-actions">
                    <button
                      className="mini-button"
                      onClick={() => onSelectDestination(favorite)}
                    >
                      선택
                    </button>
                    <button
                      className="mini-button mini-button--danger"
                      onClick={() => onFavoriteDelete(favorite.id)}
                    >
                      삭제
                    </button>
                  </div>
                </div>
              ))}
            </div>
          </section>
        </div>
      </section>

      <aside className="user-map">
        <TmapView
          startPoint={currentLocation}
          endPoint={selectedDestination}
          destinationName={selectedDestination?.name || ""}
          risks={risks}
          mode="user"
          onRouteCandidates={onRouteCandidates}
          onRouteError={onRouteError}
        />
        <div className="risk-band">
          <h2>가까운 위험 지점</h2>
          <div className="compact-list">
            {sortedRisks.slice(0, 4).map((risk) => (
              <div className="risk-item" key={risk.id}>
                <span>
                  <strong>{risk.title}</strong>
                  <small>{risk.description}</small>
                </span>
                <b>{formatDistance(risk.distanceMeter)}</b>
              </div>
            ))}
          </div>
        </div>
      </aside>

      <div className="sr-only" aria-live="polite">
        {routeCandidates.length > 0 ? "추천 경로 계산 완료" : ""}
      </div>
    </main>
  );
}

function AdminDashboardView({ summary, risks, places, status }) {
  const openRisks = risks.filter((risk) => risk.status === "open");
  const resolvedRisks = risks.filter((risk) => risk.status === "resolved");

  return (
    <main className="admin-layout">
      <section className="admin-map">
        <TmapView risks={risks} mode="admin" />
      </section>

      <aside className="admin-panel">
        <div className="panel-head">
          <div>
            <span className="eyebrow">관제 웹</span>
            <h1>화곡 시범구역 데이터</h1>
          </div>
        </div>
        {status && <p className="inline-status">{status}</p>}

        <div className="metric-grid">
          <div>
            <span>점자블록</span>
            <strong>{summary?.tactileBlockCount ?? "-"}</strong>
          </div>
          <div>
            <span>위험 지점</span>
            <strong>{summary?.riskCount ?? "-"}</strong>
          </div>
          <div>
            <span>미해결</span>
            <strong>{summary?.openRiskCount ?? openRisks.length}</strong>
          </div>
          <div>
            <span>주변시설</span>
            <strong>{summary?.nearbyPlaceCount ?? places.length}</strong>
          </div>
        </div>

        <section className="flat-panel">
          <div className="panel-head">
            <h2>위험 지점 목록</h2>
            <span>해결 {summary?.resolvedRiskCount ?? resolvedRisks.length}</span>
          </div>
          <div className="compact-list">
            {risks.map((risk) => (
              <div className="risk-item" key={risk.id}>
                <span>
                  <strong>{risk.title}</strong>
                  <small>
                    {risk.description} · {risk.status}
                  </small>
                </span>
                <b>{risk.level}</b>
              </div>
            ))}
          </div>
        </section>

        <section className="flat-panel">
          <div className="panel-head">
            <h2>주변시설 데이터</h2>
            <span>{places.length}개</span>
          </div>
          <div className="compact-list">
            {places.slice(0, 8).map((place) => (
              <div className="compact-item compact-item--static" key={place.id}>
                <span>
                  <strong>{place.name}</strong>
                  <small>{place.address}</small>
                </span>
                <b>{place.type}</b>
              </div>
            ))}
          </div>
        </section>
      </aside>
    </main>
  );
}

export default App;
