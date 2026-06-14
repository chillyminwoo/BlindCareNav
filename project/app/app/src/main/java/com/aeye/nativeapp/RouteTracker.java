package com.aeye.nativeapp;

import android.location.Location;

public class RouteTracker {
    private static final int OFF_ROUTE_THRESHOLD_METERS = 30;
    private static final int OFF_ROUTE_CONFIRM_COUNT = 3;
    private static final int STEP_COMPLETE_THRESHOLD_METERS = 18;
    private static final long FAR_REMINDER_INTERVAL_MS = 60000L;

    private final ServerClient.RouteResult route;
    private int currentStepIndex = 0;
    private long lastAnnouncementTime = 0L;
    private String lastAnnouncementKey = "";
    private int offRouteCount = 0;

    public interface NavigationListener {
        void onUpdate(String instruction, int remainingDistance);
        void onStepCompleted(int nextStepIndex);
        void onOffRoute();
        void onArrival();
    }

    public RouteTracker(ServerClient.RouteResult route) {
        this.route = route;
    }

    public void track(double lat, double lng, NavigationListener listener) {
        if (!hasUsableRoute()) {
            return;
        }

        currentStepIndex = Math.max(0, Math.min(currentStepIndex, route.steps.size() - 1));
        ServerClient.RouteStep currentStep = route.steps.get(currentStepIndex);
        int targetIndex = clampGeometryIndex(currentStep.endIndex);

        MatchResult match = findClosestSegment(lat, lng);
        updateOffRouteState(match.distanceMeter, listener);

        int remainingDistance = calculateRemainingDistance(match.progressIndex, targetIndex);
        triggerInstructionIfNeeded(currentStep.instruction, remainingDistance, listener);

        if (remainingDistance <= STEP_COMPLETE_THRESHOLD_METERS) {
            advanceStep(listener);
        }
    }

    public String getCurrentInstruction() {
        if (!hasUsableRoute()) {
            return "";
        }

        return route.steps.get(currentStepIndex).instruction;
    }

    public String getNextInstruction() {
        if (!hasUsableRoute()) {
            return "다음 안내를 준비 중입니다.";
        }

        int nextIndex = currentStepIndex + 1;

        if (nextIndex >= route.steps.size()) {
            return "목적지 주변 안전을 확인하세요.";
        }

        return route.steps.get(nextIndex).instruction;
    }

    private boolean hasUsableRoute() {
        return route != null && !route.steps.isEmpty() && route.geometry.size() >= 2;
    }

    private void updateOffRouteState(float distanceMeter, NavigationListener listener) {
        if (distanceMeter > OFF_ROUTE_THRESHOLD_METERS) {
            offRouteCount += 1;

            if (offRouteCount >= OFF_ROUTE_CONFIRM_COUNT) {
                listener.onOffRoute();
                offRouteCount = 0;
            }

            return;
        }

        offRouteCount = 0;
    }

    private void advanceStep(NavigationListener listener) {
        if (currentStepIndex < route.steps.size() - 1) {
            currentStepIndex += 1;
            lastAnnouncementKey = "";
            listener.onStepCompleted(currentStepIndex);
            return;
        }

        listener.onArrival();
    }

    private void triggerInstructionIfNeeded(String instruction, int distance, NavigationListener listener) {
        if (instruction == null || instruction.trim().isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        String message = "";
        String key = "";

        if (distance > 100) {
            if (now - lastAnnouncementTime > FAR_REMINDER_INTERVAL_MS) {
                key = "far-" + currentStepIndex;
                message = instruction + " " + distance + "미터 남았습니다.";
            }
        } else if (distance <= 50 && distance > 20) {
            key = "50m-" + currentStepIndex;
            message = "50미터 앞에서 " + instruction;
        } else if (distance <= 20) {
            key = "soon-" + currentStepIndex;
            message = "곧 " + instruction;
        }

        if (!message.isEmpty() && !key.equals(lastAnnouncementKey)) {
            lastAnnouncementTime = now;
            lastAnnouncementKey = key;
            listener.onUpdate(message, distance);
        }
    }

    private int calculateRemainingDistance(int progressIndex, int targetIndex) {
        int startIndex = Math.max(0, Math.min(progressIndex, targetIndex));
        int distance = 0;

        for (int index = startIndex; index < targetIndex; index += 1) {
            ServerClient.LatLng start = route.geometry.get(index);
            ServerClient.LatLng end = route.geometry.get(index + 1);
            distance += distanceBetween(start.lat, start.lng, end.lat, end.lng);
        }

        return distance;
    }

    private MatchResult findClosestSegment(double lat, double lng) {
        float closestDistance = Float.MAX_VALUE;
        int closestProgressIndex = 0;

        for (int index = 0; index < route.geometry.size() - 1; index += 1) {
            ServerClient.LatLng start = route.geometry.get(index);
            ServerClient.LatLng end = route.geometry.get(index + 1);
            float segmentDistance = distanceToSegmentMeters(lat, lng, start, end);

            if (segmentDistance < closestDistance) {
                closestDistance = segmentDistance;
                closestProgressIndex = index;
            }
        }

        return new MatchResult(closestDistance, closestProgressIndex);
    }

    private float distanceToSegmentMeters(double lat, double lng, ServerClient.LatLng start, ServerClient.LatLng end) {
        float[] startToEnd = new float[1];
        float[] startToPoint = new float[1];
        float[] endToPoint = new float[1];

        Location.distanceBetween(start.lat, start.lng, end.lat, end.lng, startToEnd);
        Location.distanceBetween(start.lat, start.lng, lat, lng, startToPoint);
        Location.distanceBetween(end.lat, end.lng, lat, lng, endToPoint);

        if (startToEnd[0] <= 0.1f) {
            return startToPoint[0];
        }

        double[] xy = toLocalMeters(lat, lng, start);
        double[] endXy = toLocalMeters(end.lat, end.lng, start);
        double segmentLengthSquared = endXy[0] * endXy[0] + endXy[1] * endXy[1];
        double projection = (xy[0] * endXy[0] + xy[1] * endXy[1]) / segmentLengthSquared;
        double clampedProjection = Math.max(0.0, Math.min(1.0, projection));
        double closestX = endXy[0] * clampedProjection;
        double closestY = endXy[1] * clampedProjection;
        double dx = xy[0] - closestX;
        double dy = xy[1] - closestY;

        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private double[] toLocalMeters(double lat, double lng, ServerClient.LatLng origin) {
        double earthRadiusMeter = 6371000.0;
        double latRad = Math.toRadians(origin.lat);
        double x = Math.toRadians(lng - origin.lng) * earthRadiusMeter * Math.cos(latRad);
        double y = Math.toRadians(lat - origin.lat) * earthRadiusMeter;

        return new double[]{x, y};
    }

    private int distanceBetween(double lat1, double lng1, double lat2, double lng2) {
        float[] result = new float[1];
        Location.distanceBetween(lat1, lng1, lat2, lng2, result);
        return Math.round(result[0]);
    }

    private int clampGeometryIndex(int index) {
        return Math.max(0, Math.min(index, route.geometry.size() - 1));
    }

    private static class MatchResult {
        final float distanceMeter;
        final int progressIndex;

        MatchResult(float distanceMeter, int progressIndex) {
            this.distanceMeter = distanceMeter;
            this.progressIndex = progressIndex;
        }
    }
}
