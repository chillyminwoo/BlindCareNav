package com.aeye.nativeapp;

public class VoiceCommand {
    public enum IntentType {
        NAVIGATE,
        REPEAT_GUIDANCE,
        NEXT_GUIDANCE,
        NEARBY,
        RISK_INFO,
        EMERGENCY,
        STOP_NAVIGATION,
        CURRENT_LOCATION,
        OPEN_SETTINGS,
        FAVORITE,
        SAVE_FAVORITE,
        RECENT_DESTINATION,
        START_STREAMING,
        STOP_STREAMING,
        UNKNOWN
    }

    private final IntentType intent;
    private final String rawText;
    private final String destination;
    private final String placeType;

    public VoiceCommand(IntentType intent, String rawText, String destination, String placeType) {
        this.intent = intent;
        this.rawText = rawText == null ? "" : rawText;
        this.destination = destination == null ? "" : destination;
        this.placeType = placeType == null ? "" : placeType;
    }

    public IntentType getIntent() {
        return intent;
    }

    public String getRawText() {
        return rawText;
    }

    public String getDestination() {
        return destination;
    }

    public String getPlaceType() {
        return placeType;
    }

    public boolean hasDestination() {
        return !destination.trim().isEmpty();
    }

    public String toJsonLikeString() {
        return "{"
                + "\"intent\":\"" + intent.name().toLowerCase() + "\","
                + "\"destination\":\"" + escape(destination) + "\","
                + "\"placeType\":\"" + escape(placeType) + "\""
                + "}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
