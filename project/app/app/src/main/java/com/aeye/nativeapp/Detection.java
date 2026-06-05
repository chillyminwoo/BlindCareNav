package com.aeye.nativeapp;

public class Detection {
    public final String label;
    public final float confidence;
    public final float centerX;
    public final float centerY;
    public final float width;
    public final float height;

    public Detection(String label, float confidence, float centerX, float centerY, float width, float height) {
        this.label = label;
        this.confidence = confidence;
        this.centerX = centerX;
        this.centerY = centerY;
        this.width = width;
        this.height = height;
    }

    public float area() {
        return width * height;
    }
}
