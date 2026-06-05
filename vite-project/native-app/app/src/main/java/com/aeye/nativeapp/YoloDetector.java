package com.aeye.nativeapp;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class YoloDetector implements AutoCloseable {
    public static final String MODEL_FILE = "yolov8n_coco80_float32.tflite";
    private static final String LABEL_FILE = "coco.txt";
    private static final float SCORE_THRESHOLD = 0.45f;
    private static final float NMS_THRESHOLD = 0.45f;
    private static final int MAX_DETECTIONS = 12;

    private final Interpreter interpreter;
    private final List<String> labels;
    private final int inputWidth;
    private final int inputHeight;
    private final DataType inputType;

    public YoloDetector(Context context) throws IOException {
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(4);
        interpreter = new Interpreter(loadModelFile(context, MODEL_FILE), options);
        labels = loadLabels(context);

        Tensor inputTensor = interpreter.getInputTensor(0);
        int[] inputShape = inputTensor.shape();
        inputHeight = inputShape[1];
        inputWidth = inputShape[2];
        inputType = inputTensor.dataType();
    }

    public List<Detection> detect(Bitmap sourceBitmap) {
        Bitmap inputBitmap = Bitmap.createScaledBitmap(sourceBitmap, inputWidth, inputHeight, true);
        ByteBuffer inputBuffer = createInputBuffer(inputBitmap);

        int[] outputShape = interpreter.getOutputTensor(0).shape();

        if (outputShape.length != 3) {
            return new ArrayList<>();
        }

        float[][][] output = new float[outputShape[0]][outputShape[1]][outputShape[2]];
        interpreter.run(inputBuffer, output);

        return parseOutput(output, outputShape);
    }

    private ByteBuffer createInputBuffer(Bitmap bitmap) {
        int bytesPerChannel = inputType == DataType.FLOAT32 ? 4 : 1;
        ByteBuffer buffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * bytesPerChannel);
        buffer.order(ByteOrder.nativeOrder());

        int[] pixels = new int[inputWidth * inputHeight];
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight);

        for (int pixel : pixels) {
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;

            if (inputType == DataType.FLOAT32) {
                buffer.putFloat(r / 255.0f);
                buffer.putFloat(g / 255.0f);
                buffer.putFloat(b / 255.0f);
            } else {
                buffer.put((byte) r);
                buffer.put((byte) g);
                buffer.put((byte) b);
            }
        }

        buffer.rewind();
        return buffer;
    }

    private List<Detection> parseOutput(float[][][] output, int[] shape) {
        int first = shape[1];
        int second = shape[2];
        boolean channelsFirst = first < second;
        int boxes = channelsFirst ? second : first;
        int channels = channelsFirst ? first : second;
        boolean hasObjectness = channels == labels.size() + 5;
        int classOffset = hasObjectness ? 5 : 4;
        int classCount = Math.min(labels.size(), channels - classOffset);
        List<Detection> rawDetections = new ArrayList<>();

        for (int boxIndex = 0; boxIndex < boxes; boxIndex += 1) {
            float x = valueAt(output, channelsFirst, boxIndex, 0);
            float y = valueAt(output, channelsFirst, boxIndex, 1);
            float w = valueAt(output, channelsFirst, boxIndex, 2);
            float h = valueAt(output, channelsFirst, boxIndex, 3);
            float objectness = hasObjectness ? valueAt(output, channelsFirst, boxIndex, 4) : 1.0f;

            int bestClass = -1;
            float bestScore = 0.0f;

            for (int classIndex = 0; classIndex < classCount; classIndex += 1) {
                float classScore = valueAt(output, channelsFirst, boxIndex, classOffset + classIndex);
                float score = objectness * classScore;

                if (score > bestScore) {
                    bestScore = score;
                    bestClass = classIndex;
                }
            }

            if (bestClass < 0 || bestScore < SCORE_THRESHOLD) {
                continue;
            }

            float normalizedX = normalizeCoordinate(x, inputWidth);
            float normalizedY = normalizeCoordinate(y, inputHeight);
            float normalizedW = normalizeCoordinate(w, inputWidth);
            float normalizedH = normalizeCoordinate(h, inputHeight);

            rawDetections.add(new Detection(
                    labels.get(bestClass),
                    bestScore,
                    clamp(normalizedX, 0.0f, 1.0f),
                    clamp(normalizedY, 0.0f, 1.0f),
                    clamp(normalizedW, 0.0f, 1.0f),
                    clamp(normalizedH, 0.0f, 1.0f)
            ));
        }

        rawDetections.sort(Comparator.comparing((Detection detection) -> detection.confidence).reversed());
        return nonMaxSuppression(rawDetections);
    }

    private float valueAt(float[][][] output, boolean channelsFirst, int boxIndex, int channelIndex) {
        if (channelsFirst) {
            return output[0][channelIndex][boxIndex];
        }

        return output[0][boxIndex][channelIndex];
    }

    private List<Detection> nonMaxSuppression(List<Detection> detections) {
        List<Detection> selected = new ArrayList<>();

        for (Detection candidate : detections) {
            boolean overlaps = false;

            for (Detection existing : selected) {
                if (candidate.label.equals(existing.label) && iou(candidate, existing) > NMS_THRESHOLD) {
                    overlaps = true;
                    break;
                }
            }

            if (!overlaps) {
                selected.add(candidate);
            }

            if (selected.size() >= MAX_DETECTIONS) {
                break;
            }
        }

        return selected;
    }

    private float iou(Detection a, Detection b) {
        float aLeft = a.centerX - a.width / 2.0f;
        float aTop = a.centerY - a.height / 2.0f;
        float aRight = a.centerX + a.width / 2.0f;
        float aBottom = a.centerY + a.height / 2.0f;
        float bLeft = b.centerX - b.width / 2.0f;
        float bTop = b.centerY - b.height / 2.0f;
        float bRight = b.centerX + b.width / 2.0f;
        float bBottom = b.centerY + b.height / 2.0f;

        float intersectionLeft = Math.max(aLeft, bLeft);
        float intersectionTop = Math.max(aTop, bTop);
        float intersectionRight = Math.min(aRight, bRight);
        float intersectionBottom = Math.min(aBottom, bBottom);
        float intersectionWidth = Math.max(0.0f, intersectionRight - intersectionLeft);
        float intersectionHeight = Math.max(0.0f, intersectionBottom - intersectionTop);
        float intersectionArea = intersectionWidth * intersectionHeight;
        float unionArea = a.area() + b.area() - intersectionArea;

        if (unionArea <= 0.0f) {
            return 0.0f;
        }

        return intersectionArea / unionArea;
    }

    private float normalizeCoordinate(float value, int size) {
        if (value > 1.5f) {
            return value / size;
        }

        return value;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private MappedByteBuffer loadModelFile(Context context, String modelPath) throws IOException {
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(modelPath);

        try (FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor())) {
            FileChannel fileChannel = inputStream.getChannel();
            long startOffset = fileDescriptor.getStartOffset();
            long declaredLength = fileDescriptor.getDeclaredLength();
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        }
    }

    private List<String> loadLabels(Context context) throws IOException {
        List<String> loadedLabels = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open(LABEL_FILE)))) {
            String line;

            while ((line = reader.readLine()) != null) {
                String label = line.trim();

                if (!label.isEmpty()) {
                    loadedLabels.add(label);
                }
            }
        }

        return loadedLabels;
    }

    @Override
    public void close() {
        interpreter.close();
    }
}
