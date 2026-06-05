package com.aeye.nativeapp;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;

import androidx.camera.core.ImageProxy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class ImageUtils {
    private ImageUtils() {
    }

    public static Bitmap imageProxyToBitmap(ImageProxy image) {
        byte[] nv21 = yuv420ToNv21(image);
        YuvImage yuvImage = new YuvImage(
                nv21,
                ImageFormat.NV21,
                image.getWidth(),
                image.getHeight(),
                null
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 78, out);
        byte[] jpegBytes = out.toByteArray();
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
    }

    public static Bitmap rotateBitmap(Bitmap bitmap, int rotationDegrees) {
        if (rotationDegrees == 0) {
            return bitmap;
        }

        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    private static byte[] yuv420ToNv21(ImageProxy image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int ySize = width * height;
        int uvSize = width * height / 2;
        byte[] nv21 = new byte[ySize + uvSize];

        ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
        ImageProxy.PlaneProxy uPlane = image.getPlanes()[1];
        ImageProxy.PlaneProxy vPlane = image.getPlanes()[2];

        copyYPlane(yPlane.getBuffer(), nv21, width, height, yPlane.getRowStride());
        copyUvPlanes(
                uPlane.getBuffer(),
                vPlane.getBuffer(),
                nv21,
                width,
                height,
                uPlane.getRowStride(),
                vPlane.getRowStride(),
                uPlane.getPixelStride(),
                vPlane.getPixelStride(),
                ySize
        );

        return nv21;
    }

    private static void copyYPlane(ByteBuffer source, byte[] target, int width, int height, int rowStride) {
        ByteBuffer buffer = source.duplicate();
        int targetIndex = 0;

        for (int row = 0; row < height; row += 1) {
            int rowStart = row * rowStride;
            buffer.position(rowStart);
            buffer.get(target, targetIndex, width);
            targetIndex += width;
        }
    }

    private static void copyUvPlanes(
            ByteBuffer uSource,
            ByteBuffer vSource,
            byte[] target,
            int width,
            int height,
            int uRowStride,
            int vRowStride,
            int uPixelStride,
            int vPixelStride,
            int offset
    ) {
        ByteBuffer uBuffer = uSource.duplicate();
        ByteBuffer vBuffer = vSource.duplicate();
        int chromaWidth = width / 2;
        int chromaHeight = height / 2;

        for (int row = 0; row < chromaHeight; row += 1) {
            for (int col = 0; col < chromaWidth; col += 1) {
                int targetIndex = offset + row * width + col * 2;
                int uIndex = row * uRowStride + col * uPixelStride;
                int vIndex = row * vRowStride + col * vPixelStride;
                target[targetIndex] = vBuffer.get(vIndex);
                target[targetIndex + 1] = uBuffer.get(uIndex);
            }
        }
    }
}
