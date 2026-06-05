A-eye 프로젝트 총정리
1. 프로젝트 한 줄 정의

A-eye는 시각장애인의 보행을 돕기 위해 현재 위치, 목적지, 점자블록 연결 정보, 위험 지점, 음성 안내를 결합한 시각장애인용 보행 내비게이션 MVP이다.

기존 일반 지도 앱처럼 최단거리만 안내하는 것이 아니라, 점자블록이 얼마나 연결되어 있는지, 위험 구간이 있는지, 우회가 과하지 않은지를 계산해서 시각장애인에게 더 적합한 경로를 추천하는 것이 핵심이다.

2. 최종 산출물 구조

산출물은 3개로 잡는다.

구분	산출물	역할
1	시각장애인용 사용자 앱/PWA	현재 위치 기반 목적지 검색, 추천 경로, TTS 안내, 위험 안내
2	관제용 웹 대시보드	점자블록 지도, 위험 지점, 후보 경로 점수, 탐지 결과 확인
3	FastAPI 백엔드 서버	점자블록 GeoJSON, 위험 지점, 주변시설, 안내 문구 API 제공

서버는 사용자가 직접 보는 산출물은 아니지만, 사용자 앱과 관제 웹을 연결하는 핵심 기술 산출물로 잡는다.

3. 현재까지 구현된 내용

현재 프로젝트는 Vite + React 프론트와 FastAPI 백엔드로 구성되어 있다.

현재 폴더 구조
TMAPDEMO/
└─ vite-project/
   ├─ backend/
   │  ├─ main.py
   │  └─ data/
   │     └─ tactile_blocks_hwagok.geojson
   └─ frontend/
      ├─ package.json
      ├─ index.html
      ├─ src/
      │  ├─ App.jsx
      │  ├─ TmapView.jsx
      │  └─ index.css
      └─ .env.local
현재 완료 기능
- 화곡역 주변 점자블록 GeoJSON 수동 구축
- FastAPI에서 점자블록 GeoJSON 반환
- React 프론트에서 FastAPI API 호출
- Tmap 지도에 점자블록 노란색 레이어 표시
- 지도 클릭으로 출발지/도착지 설정
- Tmap 보행 경로 API 호출
- 점자블록 중간점을 경유지로 사용해 후보 경로 생성
- Turf.js 기반 점자블록 포함률 계산
- 점자블록 포함률 + 거리 효율 + 우회 비율 기반 최종 점수 계산
- 추천 경로 파란색 표시
- 나머지 후보 경로 회색 표시
- 후보별 점수와 추천 이유 카드 출력
현재 API
GET /api/tactile-blocks?area=hwagok

역할:

backend/data/tactile_blocks_hwagok.geojson 파일을 읽어서 프론트에 반환
4. 현재 핵심 로직

현재 추천 경로는 단순 최단거리가 아니다.

경로 후보 생성 방식
1. 현재 위치와 목적지 선택
2. Tmap 기본 보행 경로 생성
3. 점자블록 구간 중간점을 경유지로 삼아 후보 경로 추가 생성
4. 후보 경로별 점자블록 포함률 계산
5. 거리 효율과 우회 비율 계산
6. 최종 점수 기준으로 추천 경로 선택
점수 공식
최종 점수 =
점자블록 포함률 점수 60%
+ 거리 효율 점수 30%
+ 우회 적정성 점수 10%
tactileScore = tactileRatioPercent * 0.6

distanceEfficiencyScore =
  Math.min(100, (directLengthMeter / candidateLengthMeter) * 100) * 0.3

detourRatio = candidateLengthMeter / directLengthMeter

detourScore =
  detourRatio <= 1.1 ? 100 :
  detourRatio <= 1.3 ? 70 :
  detourRatio <= 1.5 ? 40 :
  0

finalScore = Math.round(
  tactileScore + distanceEfficiencyScore + detourScore * 0.1
)
후보 제외 조건
if (score.routeLengthMeter > directLengthMeter * 1.5) {
  continue;
}

즉, 점자블록 포함률이 높아도 너무 멀리 돌아가면 제외한다.

5. 전체 시스템 구조
[시각장애인용 사용자 앱]
- GPS 현재 위치
- 목적지 입력
- 추천 경로 확인
- 위험 안내
- TTS 안내
- 주변시설 조회
- 즐겨찾기

        ↓ API 요청

[FastAPI 백엔드]
- 점자블록 GeoJSON 반환
- 위험 지점 반환
- 주변시설 반환
- 안내 문구 생성
- 관제용 요약 데이터 반환

        ↓ 데이터 파일

[backend/data]
- tactile_blocks_hwagok.geojson
- risk_points_hwagok.json
- nearby_places_hwagok.json

        ↑ API 조회

[관제용 웹 대시보드]
- 점자블록 지도 확인
- 위험 지점 마커 확인
- 후보 경로 점수 확인
- 탐지/위험 로그 확인
6. 사용자 앱 방향

G-EYE 리뷰를 보면 사용자가 원하는 핵심은 이거다.

- 출발지를 직접 고르는 게 불편함
- 현재 위치 기준 출발이 필요함
- 음성 검색이 필요함
- 주변시설이 가까운 순서대로 나와야 함
- 카테고리가 고정되면 불편함
- 경로를 못 찾았을 때 이유와 대안이 필요함
- 기능 depth가 깊으면 사용하기 어려움

따라서 사용자 앱은 지도 조작 중심이 아니라 음성 + 큰 버튼 중심으로 간다.

7. 사용자 앱 화면 구성
1) 홈 화면
A-eye

현재 위치 확인 중...

[목적지 입력]
[현재 위치 듣기]
[주변시설 찾기]
[즐겨찾기]
[위치 공유]

원칙:

- 버튼 크게
- 글자 크게
- 화면 depth 최소화
- 색상만으로 상태 구분하지 않기
- 누르면 음성 피드백 제공
2) 목적지 입력 화면
목적지를 입력하거나 말씀해 주세요.

[음성 입력]
[텍스트 입력창]

검색 결과:
1. 화곡역 3번 출구 - 120m
2. 화곡시장 - 280m
3. 강서구청입구 - 430m

[이 목적지로 안내]
[다시 검색]

MVP에서는 텍스트 입력 먼저 구현하고, 가능하면 Web Speech API로 음성 입력을 붙인다.

3) 추천 경로 화면
추천 경로를 찾았습니다.

점자블록 연결률: 62%
예상 거리: 480m
위험 구간: 2개
우회 비율: 1.12배

안내 문구:
점자블록 연결률이 높은 경로입니다.
전방에 위험 구간이 있어 주의가 필요합니다.

[안내 시작]
[다시 듣기]
[다른 경로 보기]

사용자에게는 점수보다 자연어 설명이 중요하다.

4) 내비게이션 진행 화면
안내 중

현재 위치:
화곡역 2번 출구 주변

다음 안내:
30m 직진 후 왼쪽으로 이동하세요.

위험 안내:
전방 12m 지점에 점자블록 단절 구간이 있습니다.

[다시 듣기]
[안내 종료]
[위치 공유]
5) 주변시설 화면
주변시설 찾기

[화장실]
[대중교통]
[은행]
[편의점]
[병원]
[관공서]
[공원]
[마트]

결과는 무조건 현재 위치 기준 거리순 정렬.

6) 즐겨찾기 화면
즐겨찾기

카테고리:
- 집
- 회사
- 식당
- 복지관
- 대중교통
- 기타
- 직접 만든 카테고리

[카테고리 추가]
[카테고리 이름 변경]
[카테고리 삭제]
[장소 추가]

리뷰에서 “고정 카테고리 불편”이 있었으므로, MVP에서는 localStorage 기반으로 간단히 구현한다.

주의점:

- 삭제는 배열 index 기준으로 하지 말 것
- 반드시 id 기준으로 삭제
- 다른 카테고리 장소가 삭제되지 않게 할 것
8. 관제 웹 방향

관제 웹은 실시간 사용자 추적 시스템이 아니다.
이번 MVP에서는 시범 구역 데이터 확인용 관리자 화면으로 잡는다.

관제 웹 화면 구성
좌측:
- Tmap 지도
- 점자블록 노란색 레이어
- 위험 지점 마커
- 추천 경로 표시

우측:
- 시범 구역 요약
- 위험 지점 목록
- 후보 경로 점수
- 탐지 결과 로그
관제 웹 기능
기능	설명
점자블록 레이어 표시	GeoJSON 기반 노란색 선 표시
위험 지점 마커 표시	파손, 장애물, 공사구간 등 표시
후보 경로 점수 확인	점자블록 포함률, 거리, 우회율 확인
위험 목록 확인	위험 지점 리스트 확인
탐지 결과 로그	YOLO 결과나 테스트 데이터 확인
관제 웹에서 제외할 것
- 로그인
- 실시간 다중 사용자 위치 추적
- 실제 119 신고 연동
- 실제 택시 호출
- 전국 단위 데이터 관리
- 관리자 권한 관리
9. 백엔드 API 구성

현재 API는 유지한다.

GET /api/tactile-blocks?area=hwagok

추가할 API는 아래 정도면 충분하다.

1) 위험 지점 조회
GET /api/risks?area=hwagok

응답 예시:

{
  "area": "hwagok",
  "items": [
    {
      "id": "risk_001",
      "type": "blocked_tactile_block",
      "title": "점자블록 위 장애물",
      "description": "점자블록 위에 적치물이 있는 구간",
      "lat": 37.5421,
      "lng": 126.8413,
      "level": "warning",
      "status": "open"
    }
  ]
}
2) 주변시설 조회
GET /api/nearby?area=hwagok&lat=37.5421&lng=126.8413&type=toilet

응답 예시:

{
  "area": "hwagok",
  "items": [
    {
      "id": "place_001",
      "type": "toilet",
      "name": "화곡역 공중화장실",
      "lat": 37.5425,
      "lng": 126.8419,
      "distanceMeter": 145,
      "description": "현재 위치 기준 약 145m"
    }
  ]
}
3) 안내 문구 생성
POST /api/guidance

요청 예시:

{
  "currentLocation": {
    "lat": 37.5421,
    "lng": 126.8413
  },
  "nextStep": {
    "distanceMeter": 30,
    "direction": "left"
  },
  "risks": [
    {
      "type": "blocked_tactile_block",
      "distanceMeter": 12
    }
  ]
}

응답 예시:

{
  "message": "30미터 앞에서 왼쪽으로 이동하세요. 전방 12미터 지점에 점자블록을 막고 있는 장애물이 있습니다.",
  "level": "warning"
}
4) 관제 요약
GET /api/admin/summary?area=hwagok

응답 예시:

{
  "area": "hwagok",
  "tactileBlockCount": 5,
  "riskCount": 7,
  "openRiskCount": 3,
  "resolvedRiskCount": 4,
  "nearbyPlaceCount": 12
}
5) 목적지 검색
POST /api/places/search

요청 예시:

{
  "area": "hwagok",
  "keyword": "화곡역 3번 출구",
  "currentLocation": {
    "lat": 37.5421,
    "lng": 126.8413
  }
}

응답 예시:

{
  "candidates": [
    {
      "id": "place_001",
      "name": "화곡역 3번 출구",
      "lat": 37.5419,
      "lng": 126.8408,
      "distanceMeter": 120,
      "address": "서울특별시 강서구 화곡동"
    }
  ]
}

10. 백엔드 데이터 파일

DB는 아직 쓰지 않는다.
MVP에서는 JSON + GeoJSON으로 간다.

backend/data/
├─ tactile_blocks_hwagok.geojson
├─ risk_points_hwagok.json
└─ nearby_places_hwagok.json

DB는 나중에 이런 경우에만 도입한다.

- 사용자 제보 저장
- 위험 지점 상태 변경 저장
- 탐지 로그 누적
- 구역이 여러 개로 늘어남
- 관리자 페이지에서 데이터를 직접 수정해야 함

11. 추천 폴더 구조
Frontend
frontend/src/
├─ App.jsx
├─ pages/
│  ├─ UserApp.jsx
│  └─ AdminDashboard.jsx
├─ components/
│  ├─ TmapRouteView.jsx
│  ├─ RouteSummaryCard.jsx
│  ├─ VoiceGuidePanel.jsx
│  ├─ NearbyInfoPanel.jsx
│  ├─ FavoritePanel.jsx
│  ├─ AdminSummaryPanel.jsx
│  └─ AdminRiskList.jsx
├─ services/
│  ├─ api.js
│  ├─ routeApi.js
│  ├─ placeApi.js
│  ├─ riskApi.js
│  ├─ favoriteStorage.js
│  └─ speech.js
└─ index.css
Backend
backend/
├─ main.py
├─ routers/
│  ├─ tactile.py
│  ├─ risks.py
│  ├─ nearby.py
│  ├─ places.py
│  ├─ guidance.py
│  └─ admin.py
├─ services/
│  ├─ geojson_loader.py
│  ├─ nearby_service.py
│  ├─ risk_service.py
│  └─ guidance_text.py
└─ data/
   ├─ tactile_blocks_hwagok.geojson
   ├─ risk_points_hwagok.json
   └─ nearby_places_hwagok.json

기존 구조를 무리하게 다 갈아엎지 말라.
지금 돌아가는 기능이 있으므로, 리팩터링보다 기능 추가 우선.

12. MVP 구현 우선순위
1순위
- 기존 Tmap 경로 추천 기능 유지
- 사용자 앱 기본 화면 구성
- 현재 위치 GPS 가져오기
- 목적지 텍스트 입력
- 추천 경로 표시
- TTS 다시 듣기
2순위
- 위험 지점 JSON 추가
- /api/risks 추가
- 지도에 위험 마커 표시
- 추천 경로 근처 위험 안내
- /api/guidance 추가
3순위
- 주변시설 JSON 추가
- /api/nearby 추가
- 주변시설 거리순 정렬
- 사용자 앱 주변시설 화면
4순위
- 관제 웹 대시보드
- 점자블록 레이어 확인
- 위험 지점 목록
- 요약 카드
5순위
- 즐겨찾기 localStorage
- 사용자 카테고리 추가/수정/삭제
- 음성 입력 버튼