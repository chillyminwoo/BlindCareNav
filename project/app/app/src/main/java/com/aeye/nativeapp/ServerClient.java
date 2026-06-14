package com.aeye.nativeapp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ServerClient {
    public interface Callback {
        void onResult(String message);
    }

    public interface RouteCallback {
        void onResult(RouteResult result);
    }

    public static class RouteResult {
        public boolean ok;
        public String reason = "";
        public String destinationName = "";
        public int distanceMeter = 0;
        public int durationMinute = 0;
        public String currentInstruction = "";
        public String nextInstruction = "";
        
        // 실시간 추적을 위해 추가되는 필드들
        public List<LatLng> geometry = new ArrayList<>();
        public List<RouteStep> steps = new ArrayList<>();
    }

    public static class LatLng {
        public double lat;
        public double lng;
        public LatLng(double lat, double lng) { this.lat = lat; this.lng = lng; }
    }

    public static class RouteStep {
        public String id;
        public String type;
        public String instruction;
        public String direction;
        public int distanceMeter;
        public int startIndex; // geometry 리스트에서의 시작 인덱스
        public int endIndex;   // geometry 리스트에서의 종료 인덱스
    }

    public interface AssistantCallback {
        void onResult(AssistantResult result);
    }

    public interface MobileCommandsCallback {
        void onResult(MobileCommandsResult result);
    }

    public static class AssistantResult {
        public boolean ok;
        public String action = "speak";
        public String intent = "unknown";
        public String message = "";
        public String destinationKeyword = "";
        public String placeType = "";
        public String guidanceMode = "";
        public RouteResult route;
    }

    public static class MobileCommandsResult {
        public boolean ok;
        public final List<String> commands = new ArrayList<>();
        public boolean streamRequested = false;
        public boolean streamActive = false;
        public String message = "";
    }

    private ServerClient() {
    }

    public static void checkServer(String baseUrl, Callback callback) {
        new Thread(() -> {
            String normalizedUrl = normalizeBaseUrl(baseUrl);

            if (normalizedUrl.isEmpty()) {
                callback.onResult("서버 주소가 비어 있습니다.");
                return;
            }

            HttpURLConnection connection = null;

            try {
                URL url = new URL(normalizedUrl + "/api/admin/summary?area=hwagok");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                setTunnelHeaders(connection);

                int status = connection.getResponseCode();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        status >= 200 && status < 300
                                ? connection.getInputStream()
                                : connection.getErrorStream(),
                        StandardCharsets.UTF_8
                ));
                String body = reader.readLine();

                if (status >= 200 && status < 300) {
                    callback.onResult("서버 연결 성공. 화곡 시범구역 데이터를 받을 수 있습니다.");
                } else {
                    callback.onResult("서버 응답 오류 " + status + ". " + body);
                }
            } catch (Exception error) {
                callback.onResult("서버 연결 실패. " + error.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    public static void postDetection(
            String baseUrl,
            String deviceId,
            double lat,
            double lng,
            Float heading,
            List<Detection> detections,
            String message,
            Callback callback
    ) {
        new Thread(() -> {
            String normalizedUrl = normalizeBaseUrl(baseUrl);

            if (normalizedUrl.isEmpty()) {
                callback.onResult("서버 주소가 비어 있어 감지 이벤트를 전송하지 못했습니다.");
                return;
            }

            HttpURLConnection connection = null;

            try {
                URL url = new URL(normalizedUrl + "/api/mobile/tactile-hazard");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(4000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                setTunnelHeaders(connection);

                JSONObject payload = new JSONObject();
                payload.put("area", "hwagok");
                payload.put("deviceId", deviceId);
                payload.put("lat", lat);
                payload.put("lng", lng);
                payload.put("heading", heading == null ? JSONObject.NULL : heading);
                payload.put("message", message);

                JSONArray detectionArray = new JSONArray();
                int count = Math.min(3, detections.size());

                for (int index = 0; index < count; index += 1) {
                    Detection detection = detections.get(index);
                    JSONObject detectionObject = new JSONObject();
                    detectionObject.put("label", detection.label);
                    detectionObject.put("confidence", detection.confidence);
                    detectionObject.put("direction", toDirectionKey(detection.centerX));
                    detectionObject.put("distanceLevel", toDistanceLevelKey(detection.area()));
                    detectionObject.put("isStaticObstacle", isStaticObstacleLabel(detection.label));
                    detectionArray.put(detectionObject);
                }

                payload.put("detections", detectionArray);

                byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);

                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(body);
                }

                int status = connection.getResponseCode();

                if (status >= 200 && status < 300) {
                    callback.onResult("감지 이벤트를 서버로 전송했습니다.");
                } else {
                    callback.onResult("감지 이벤트 전송 실패: HTTP " + status);
                }
            } catch (Exception error) {
                callback.onResult("감지 이벤트 전송 실패: " + error.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    public static void requestGuidance(
            String baseUrl,
            double lat,
            double lng,
            Callback callback
    ) {
        new Thread(() -> {
            String normalizedUrl = normalizeBaseUrl(baseUrl);

            if (normalizedUrl.isEmpty()) {
                callback.onResult("");
                return;
            }

            HttpURLConnection connection = null;

            try {
                URL url = new URL(normalizedUrl + "/api/guidance");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(4000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                setTunnelHeaders(connection);

                JSONObject payload = new JSONObject();
                JSONObject currentLocation = new JSONObject();
                currentLocation.put("lat", lat);
                currentLocation.put("lng", lng);
                payload.put("currentLocation", currentLocation);

                JSONObject nextStep = new JSONObject();
                nextStep.put("direction", "straight");
                nextStep.put("distanceMeter", 200);
                payload.put("nextStep", nextStep);
                payload.put("risks", new JSONArray());

                byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);

                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(body);
                }

                int status = connection.getResponseCode();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        status >= 200 && status < 300
                                ? connection.getInputStream()
                                : connection.getErrorStream(),
                        StandardCharsets.UTF_8
                ));
                String responseBody = reader.readLine();

                if (status >= 200 && status < 300 && responseBody != null) {
                    JSONObject response = new JSONObject(responseBody);
                    callback.onResult(response.optString("message", ""));
                } else {
                    callback.onResult("");
                }
            } catch (Exception error) {
                callback.onResult("");
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    public static void requestMobileRoute(
            String baseUrl,
            String destinationKeyword,
            double lat,
            double lng,
            RouteCallback callback
    ) {
        new Thread(() -> {
            RouteResult result = new RouteResult();
            String normalizedUrl = normalizeBaseUrl(baseUrl);

            if (normalizedUrl.isEmpty()) {
                result.reason = "서버 주소가 비어 있습니다.";
                callback.onResult(result);
                return;
            }

            HttpURLConnection connection = null;

            try {
                URL url = new URL(normalizedUrl + "/api/mobile/route");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                setTunnelHeaders(connection);

                JSONObject currentLocation = new JSONObject();
                currentLocation.put("lat", lat);
                currentLocation.put("lng", lng);

                JSONObject payload = new JSONObject();
                payload.put("area", "hwagok");
                payload.put("destinationKeyword", destinationKeyword);
                payload.put("currentLocation", currentLocation);

                byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);

                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(body);
                }

                int status = connection.getResponseCode();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        status >= 200 && status < 300
                                ? connection.getInputStream()
                                : connection.getErrorStream(),
                        StandardCharsets.UTF_8
                ));
                String responseBody = reader.readLine();

                if (status < 200 || status >= 300 || responseBody == null) {
                    result.reason = "경로 요청 실패: HTTP " + status;
                    callback.onResult(result);
                    return;
                }

                result = parseRouteResult(new JSONObject(responseBody), destinationKeyword);
                callback.onResult(result);
            } catch (Exception error) {
                result.reason = "경로 요청 실패: " + error.getMessage();
                callback.onResult(result);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    public static void requestAssistantCommand(
            String baseUrl,
            String deviceId,
            String utterance,
            double lat,
            double lng,
            AssistantCallback callback
    ) {
        new Thread(() -> {
            AssistantResult result = new AssistantResult();
            String normalizedUrl = normalizeBaseUrl(baseUrl);

            if (normalizedUrl.isEmpty()) {
                result.message = "서버 주소가 비어 있습니다.";
                callback.onResult(result);
                return;
            }

            HttpURLConnection connection = null;

            try {
                URL url = new URL(normalizedUrl + "/api/assistant/command");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(7000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                setTunnelHeaders(connection);

                JSONObject currentLocation = new JSONObject();
                currentLocation.put("lat", lat);
                currentLocation.put("lng", lng);

                JSONObject payload = new JSONObject();
                payload.put("area", "hwagok");
                payload.put("deviceId", deviceId);
                payload.put("utterance", utterance);
                payload.put("currentLocation", currentLocation);

                byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);

                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(body);
                }

                int status = connection.getResponseCode();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        status >= 200 && status < 300
                                ? connection.getInputStream()
                                : connection.getErrorStream(),
                        StandardCharsets.UTF_8
                ));
                String responseBody = reader.readLine();

                if (status < 200 || status >= 300 || responseBody == null) {
                    result.message = "누니 서버 응답 오류: HTTP " + status;
                    callback.onResult(result);
                    return;
                }

                JSONObject response = new JSONObject(responseBody);
                result.ok = response.optBoolean("ok", false);
                result.action = response.optString("action", "speak");
                result.intent = response.optString("intent", "unknown");
                result.message = response.optString("tts", response.optString("message", ""));
                result.destinationKeyword = response.optString("destinationKeyword", "");
                result.placeType = response.optString("placeType", "");
                result.guidanceMode = response.optString("guidanceMode", "");

                JSONObject route = response.optJSONObject("route");

                if (route != null) {
                    result.route = parseRouteResult(route, result.destinationKeyword);
                }

                callback.onResult(result);
            } catch (Exception error) {
                result.message = "누니 서버 연결 실패: " + error.getMessage();
                callback.onResult(result);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    public static void requestSceneGuidance(
            String baseUrl,
            String deviceId,
            String mode,
            double lat,
            double lng,
            List<Detection> detections,
            Callback callback
    ) {
        new Thread(() -> {
            String normalizedUrl = normalizeBaseUrl(baseUrl);

            if (normalizedUrl.isEmpty()) {
                callback.onResult("");
                return;
            }

            HttpURLConnection connection = null;

            try {
                URL url = new URL(normalizedUrl + "/api/mobile/scene-guidance");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(7000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                setTunnelHeaders(connection);

                JSONObject payload = new JSONObject();
                payload.put("area", "hwagok");
                payload.put("deviceId", deviceId);
                payload.put("mode", mode == null ? "general" : mode);
                payload.put("lat", lat);
                payload.put("lng", lng);

                JSONArray detectionArray = new JSONArray();
                int count = Math.min(6, detections.size());

                for (int index = 0; index < count; index += 1) {
                    Detection detection = detections.get(index);
                    JSONObject detectionObject = new JSONObject();
                    detectionObject.put("label", detection.label);
                    detectionObject.put("confidence", detection.confidence);
                    detectionObject.put("direction", toDirectionKey(detection.centerX));
                    detectionObject.put("distanceLevel", toDistanceLevelKey(detection.area()));
                    detectionObject.put("isStaticObstacle", isStaticObstacleLabel(detection.label));
                    detectionArray.put(detectionObject);
                }

                payload.put("detections", detectionArray);

                byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);

                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(body);
                }

                int status = connection.getResponseCode();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        status >= 200 && status < 300
                                ? connection.getInputStream()
                                : connection.getErrorStream(),
                        StandardCharsets.UTF_8
                ));
                String responseBody = reader.readLine();

                if (status < 200 || status >= 300 || responseBody == null) {
                    callback.onResult("");
                    return;
                }

                JSONObject response = new JSONObject(responseBody);
                if (!response.optBoolean("shouldSpeak", true)) {
                    callback.onResult("");
                    return;
                }

                callback.onResult(response.optString("message", ""));
            } catch (Exception error) {
                callback.onResult("");
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    public static void requestNearby(
            String baseUrl,
            double lat,
            double lng,
            String type,
            Callback callback
    ) {
        new Thread(() -> {
            String normalizedUrl = normalizeBaseUrl(baseUrl);

            if (normalizedUrl.isEmpty()) {
                callback.onResult("서버 주소가 비어 있어 주변시설을 확인하지 못했습니다.");
                return;
            }

            HttpURLConnection connection = null;

            try {
                String query = "/api/nearby?area=hwagok"
                        + "&lat=" + lat
                        + "&lng=" + lng;

                if (type != null && !type.trim().isEmpty()) {
                    query += "&type=" + URLEncoder.encode(type.trim(), StandardCharsets.UTF_8.toString());
                }

                URL url = new URL(normalizedUrl + query);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                setTunnelHeaders(connection);

                int status = connection.getResponseCode();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        status >= 200 && status < 300
                                ? connection.getInputStream()
                                : connection.getErrorStream(),
                        StandardCharsets.UTF_8
                ));
                String responseBody = reader.readLine();

                if (status < 200 || status >= 300 || responseBody == null) {
                    callback.onResult("주변시설 정보를 가져오지 못했습니다. HTTP " + status);
                    return;
                }

                JSONObject response = new JSONObject(responseBody);
                JSONArray items = response.optJSONArray("items");

                if (items == null || items.length() == 0) {
                    callback.onResult("현재 위치 주변에서 해당 시설을 찾지 못했습니다.");
                    return;
                }

                StringBuilder message = new StringBuilder("가까운 주변시설입니다. ");
                int count = Math.min(3, items.length());

                for (int index = 0; index < count; index += 1) {
                    JSONObject item = items.optJSONObject(index);

                    if (item == null) {
                        continue;
                    }

                    if (index > 0) {
                        message.append(". ");
                    }

                    message.append(index + 1)
                            .append("번 ")
                            .append(item.optString("name", "이름 없는 시설"))
                            .append(", 약 ")
                            .append(item.optInt("distanceMeter", 0))
                            .append("미터");
                }

                callback.onResult(message.toString());
            } catch (Exception error) {
                callback.onResult("주변시설 정보 확인에 실패했습니다. " + error.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    public static void requestRisks(String baseUrl, Callback callback) {
        new Thread(() -> {
            String normalizedUrl = normalizeBaseUrl(baseUrl);

            if (normalizedUrl.isEmpty()) {
                callback.onResult("서버 주소가 비어 있어 위험 정보를 확인하지 못했습니다.");
                return;
            }

            HttpURLConnection connection = null;

            try {
                URL url = new URL(normalizedUrl + "/api/risks?area=hwagok");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                setTunnelHeaders(connection);

                int status = connection.getResponseCode();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        status >= 200 && status < 300
                                ? connection.getInputStream()
                                : connection.getErrorStream(),
                        StandardCharsets.UTF_8
                ));
                String responseBody = reader.readLine();

                if (status < 200 || status >= 300 || responseBody == null) {
                    callback.onResult("위험 정보를 가져오지 못했습니다. HTTP " + status);
                    return;
                }

                JSONObject response = new JSONObject(responseBody);
                JSONArray items = response.optJSONArray("items");

                if (items == null || items.length() == 0) {
                    callback.onResult("현재 시범 구역에 등록된 위험 정보가 없습니다.");
                    return;
                }

                StringBuilder message = new StringBuilder("화곡 시범 구역 위험 정보입니다. ");
                int spokenCount = 0;

                for (int index = 0; index < items.length() && spokenCount < 3; index += 1) {
                    JSONObject item = items.optJSONObject(index);

                    if (item == null) {
                        continue;
                    }

                    if ("resolved".equals(item.optString("status", ""))) {
                        continue;
                    }

                    if (spokenCount > 0) {
                        message.append(". ");
                    }

                    message.append(spokenCount + 1)
                            .append("번 ")
                            .append(item.optString("title", "위험 지점"))
                            .append(", ")
                            .append(item.optString("level", "주의"));
                    spokenCount += 1;
                }

                if (spokenCount == 0) {
                    callback.onResult("현재 열려 있는 위험 지점은 없습니다.");
                    return;
                }

                callback.onResult(message.toString());
            } catch (Exception error) {
                callback.onResult("위험 정보 확인에 실패했습니다. " + error.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    public static void postEmergency(
            String baseUrl,
            String deviceId,
            double lat,
            double lng,
            Float heading,
            String message,
            Callback callback
    ) {
        new Thread(() -> {
            String normalizedUrl = normalizeBaseUrl(baseUrl);

            if (normalizedUrl.isEmpty()) {
                callback.onResult("서버 주소가 비어 있어 긴급 위치를 전송하지 못했습니다.");
                return;
            }

            HttpURLConnection connection = null;

            try {
                URL url = new URL(normalizedUrl + "/api/support/emergency");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(4000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                setTunnelHeaders(connection);

                JSONObject payload = new JSONObject();
                payload.put("area", "hwagok");
                payload.put("deviceId", deviceId);
                payload.put("lat", lat);
                payload.put("lng", lng);
                payload.put("heading", heading == null ? JSONObject.NULL : heading);
                payload.put("message", message);

                byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);

                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(body);
                }

                int status = connection.getResponseCode();

                if (status >= 200 && status < 300) {
                    callback.onResult("긴급 위치를 서버에 전송했습니다.");
                } else {
                    callback.onResult("긴급 위치 전송 실패: HTTP " + status);
                }
            } catch (Exception error) {
                callback.onResult("긴급 위치 전송 실패: " + error.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    public static void postSupportRequest(
            String baseUrl,
            String type,
            String deviceId,
            double lat,
            double lng,
            Float heading,
            String mode,
            String sceneDescription,
            List<Detection> detections,
            String message,
            Callback callback
    ) {
        new Thread(() -> {
            String normalizedUrl = normalizeBaseUrl(baseUrl);

            if (normalizedUrl.isEmpty()) {
                callback.onResult("서버 주소가 비어 있어 관제 요청을 전송하지 못했습니다.");
                return;
            }

            HttpURLConnection connection = null;
            String requestType = "emergency".equals(type) ? "emergency" : "help";

            try {
                URL url = new URL(normalizedUrl + "/api/support/" + requestType);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(5000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                setTunnelHeaders(connection);

                JSONObject payload = new JSONObject();
                payload.put("area", "hwagok");
                payload.put("deviceId", deviceId);
                payload.put("lat", lat);
                payload.put("lng", lng);
                payload.put("heading", heading == null ? JSONObject.NULL : heading);
                payload.put("mode", mode == null ? "general" : mode);
                payload.put("sceneDescription", sceneDescription == null ? "" : sceneDescription);
                payload.put("message", message == null ? "" : message);

                JSONArray detectionArray = new JSONArray();
                int count = Math.min(8, detections == null ? 0 : detections.size());

                for (int index = 0; index < count; index += 1) {
                    Detection detection = detections.get(index);
                    JSONObject detectionObject = new JSONObject();
                    detectionObject.put("label", detection.label);
                    detectionObject.put("confidence", detection.confidence);
                    detectionObject.put("direction", toDirectionKey(detection.centerX));
                    detectionObject.put("distanceLevel", toDistanceLevelKey(detection.area()));
                    detectionObject.put("isStaticObstacle", isStaticObstacleLabel(detection.label));
                    detectionArray.put(detectionObject);
                }

                payload.put("detections", detectionArray);

                byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);

                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(body);
                }

                int status = connection.getResponseCode();

                if (status >= 200 && status < 300) {
                    callback.onResult("emergency".equals(requestType)
                            ? "긴급 요청을 관제에 전송했습니다."
                            : "도움 요청을 관제에 전송했습니다.");
                } else {
                    callback.onResult("관제 요청 전송 실패: HTTP " + status);
                }
            } catch (Exception error) {
                callback.onResult("관제 요청 전송 실패: " + error.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    public static void fetchMobileCommands(
            String baseUrl,
            String deviceId,
            MobileCommandsCallback callback
    ) {
        new Thread(() -> {
            MobileCommandsResult result = new MobileCommandsResult();
            String normalizedUrl = normalizeBaseUrl(baseUrl);

            if (normalizedUrl.isEmpty()) {
                callback.onResult(result);
                return;
            }

            HttpURLConnection connection = null;

            try {
                String query = "?deviceId="
                        + URLEncoder.encode(deviceId, StandardCharsets.UTF_8.name())
                        + "&area=hwagok";
                URL url = new URL(normalizedUrl + "/api/mobile/commands" + query);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);
                setTunnelHeaders(connection);

                int status = connection.getResponseCode();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        status >= 200 && status < 300
                                ? connection.getInputStream()
                                : connection.getErrorStream(),
                        StandardCharsets.UTF_8
                ));
                String responseBody = reader.readLine();

                if (status < 200 || status >= 300 || responseBody == null) {
                    callback.onResult(result);
                    return;
                }

                JSONObject response = new JSONObject(responseBody);
                result.ok = response.optBoolean("ok", false);

                JSONArray commands = response.optJSONArray("commands");
                if (commands != null) {
                    for (int index = 0; index < commands.length(); index += 1) {
                        JSONObject command = commands.optJSONObject(index);
                        if (command != null) {
                            result.commands.add(command.optString("type", ""));
                        }
                    }
                }

                JSONObject stream = response.optJSONObject("stream");
                if (stream != null) {
                    result.streamRequested = stream.optBoolean("requested", false);
                    result.streamActive = stream.optBoolean("active", false);
                    result.message = stream.optString("message", "");
                }

                callback.onResult(result);
            } catch (Exception ignored) {
                callback.onResult(result);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    public static void postLocation(
            String baseUrl,
            String deviceId,
            double lat,
            double lng,
            Float heading,
            Callback callback
    ) {
        new Thread(() -> {
            String normalizedUrl = normalizeBaseUrl(baseUrl);

            if (normalizedUrl.isEmpty()) {
                if (callback != null) callback.onResult("");
                return;
            }

            HttpURLConnection connection = null;

            try {
                URL url = new URL(normalizedUrl + "/api/mobile/location");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                setTunnelHeaders(connection);

                JSONObject payload = new JSONObject();
                payload.put("area", "hwagok");
                payload.put("deviceId", deviceId);
                payload.put("lat", lat);
                payload.put("lng", lng);
                payload.put("heading", heading == null ? JSONObject.NULL : heading);

                byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);

                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(body);
                }

                int status = connection.getResponseCode();
                if (callback != null) {
                    if (status >= 200 && status < 300) {
                        callback.onResult("OK");
                    } else {
                        callback.onResult("Error " + status);
                    }
                }
            } catch (Exception error) {
                if (callback != null) callback.onResult(error.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    private static RouteResult parseRouteResult(JSONObject response, String fallbackKeyword) {
        RouteResult result = new RouteResult();
        result.ok = response.optBoolean("ok", false);

        if (!result.ok) {
            result.reason = response.optString("reason", "목적지를 찾지 못했습니다.");
            return result;
        }

        JSONObject destination = response.optJSONObject("destination");
        JSONObject summary = response.optJSONObject("summary");
        JSONArray geometryArray = response.optJSONArray("geometry");
        JSONArray steps = response.optJSONArray("steps");

        if (destination != null) {
            result.destinationName = destination.optString("name", fallbackKeyword);
        } else {
            result.destinationName = fallbackKeyword;
        }

        if (summary != null) {
            result.distanceMeter = summary.optInt("distanceMeter", 0);
            result.durationMinute = summary.optInt("durationMinute", 0);
        }

        if (geometryArray != null) {
            for (int i = 0; i < geometryArray.length(); i++) {
                JSONObject point = geometryArray.optJSONObject(i);

                if (point != null) {
                    result.geometry.add(new LatLng(point.optDouble("lat"), point.optDouble("lng")));
                }
            }
        }

        if (steps != null && steps.length() > 0) {
            for (int i = 0; i < steps.length(); i++) {
                JSONObject stepObject = steps.optJSONObject(i);

                if (stepObject == null) {
                    continue;
                }

                RouteStep step = new RouteStep();
                step.id = stepObject.optString("id", "step-" + (i + 1));
                step.type = stepObject.optString("type", "");
                step.direction = stepObject.optString("direction", "");
                step.instruction = stepObject.optString("instruction", "");
                step.distanceMeter = stepObject.optInt("distanceMeter", 0);
                step.startIndex = stepObject.optInt("startIndex", 0);
                step.endIndex = stepObject.optInt("endIndex", step.startIndex);

                if (step.endIndex < step.startIndex) {
                    step.endIndex = step.startIndex;
                }

                if (!result.geometry.isEmpty()) {
                    int maxIndex = result.geometry.size() - 1;
                    step.startIndex = Math.max(0, Math.min(step.startIndex, maxIndex));
                    step.endIndex = Math.max(0, Math.min(step.endIndex, maxIndex));
                }

                result.steps.add(step);
            }

            if (!result.steps.isEmpty()) {
                result.currentInstruction = result.steps.get(0).instruction;
            }

            if (result.steps.size() > 1) {
                result.nextInstruction = result.steps.get(1).instruction;
            }
        }

        if (result.currentInstruction.isEmpty()) {
            result.currentInstruction = "직진 200m";
        }

        if (result.nextInstruction.isEmpty()) {
            result.nextInstruction = "다음 안내를 준비 중입니다.";
        }

        return result;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }

        String normalizedUrl = baseUrl.trim();

        if (normalizedUrl.endsWith("/")) {
            normalizedUrl = normalizedUrl.substring(0, normalizedUrl.length() - 1);
        }

        return normalizedUrl;
    }

    private static void setTunnelHeaders(HttpURLConnection connection) {
        connection.setRequestProperty("bypass-tunnel-reminder", "true");
        connection.setRequestProperty("ngrok-skip-browser-warning", "true");
    }

    private static String toDirectionKey(float centerX) {
        if (centerX < 0.38f) {
            return "left";
        }

        if (centerX > 0.62f) {
            return "right";
        }

        return "front";
    }

    private static String toDistanceLevelKey(float area) {
        if (area > 0.20f) {
            return "near";
        }

        if (area > 0.08f) {
            return "middle";
        }

        return "far";
    }

    private static boolean isStaticObstacleLabel(String label) {
        if (label == null) return false;
        // 사람과 점자블록은 마커 표시 대상에서 제외 (단순 안내용)
        return !label.equals("person") && !label.equals("braille_block");
    }
}
