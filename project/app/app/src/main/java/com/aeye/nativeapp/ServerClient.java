package com.aeye.nativeapp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
                connection.setRequestProperty("bypass-tunnel-reminder", "true");

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
                URL url = new URL(normalizedUrl + "/api/mobile/detections");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(4000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setRequestProperty("bypass-tunnel-reminder", "true");

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
                connection.setRequestProperty("bypass-tunnel-reminder", "true");

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
                connection.setRequestProperty("bypass-tunnel-reminder", "true");

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

                JSONObject response = new JSONObject(responseBody);
                result.ok = response.optBoolean("ok", false);

                if (!result.ok) {
                    result.reason = response.optString("reason", "목적지를 찾지 못했습니다.");
                    callback.onResult(result);
                    return;
                }

                JSONObject destination = response.optJSONObject("destination");
                JSONObject summary = response.optJSONObject("summary");
                JSONArray steps = response.optJSONArray("steps");

                if (destination != null) {
                    result.destinationName = destination.optString("name", destinationKeyword);
                } else {
                    result.destinationName = destinationKeyword;
                }

                if (summary != null) {
                    result.distanceMeter = summary.optInt("distanceMeter", 0);
                    result.durationMinute = summary.optInt("durationMinute", 0);
                }

                if (steps != null && steps.length() > 0) {
                    JSONObject currentStep = steps.optJSONObject(0);
                    JSONObject nextStep = steps.length() > 1 ? steps.optJSONObject(1) : null;

                    if (currentStep != null) {
                        result.currentInstruction = currentStep.optString("instruction", "직진 200m");
                    }

                    if (nextStep != null) {
                        result.nextInstruction = nextStep.optString("instruction", "다음 안내를 준비 중입니다.");
                    }
                }

                if (result.currentInstruction.isEmpty()) {
                    result.currentInstruction = "직진 200m";
                }

                if (result.nextInstruction.isEmpty()) {
                    result.nextInstruction = "다음 안내를 준비 중입니다.";
                }

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
                URL url = new URL(normalizedUrl + "/api/mobile/emergency");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(4000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setRequestProperty("bypass-tunnel-reminder", "true");

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
}
