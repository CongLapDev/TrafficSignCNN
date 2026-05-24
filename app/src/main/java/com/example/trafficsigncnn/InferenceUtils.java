package com.example.trafficsigncnn;

import android.graphics.Bitmap;
import android.graphics.Matrix;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared static utilities for bitmap preprocessing and inference.
 * Used by LiveScanActivity, CapturePhotoActivity, and GalleryRecognitionActivity.
 */
public class InferenceUtils {

    private InferenceUtils() {}

    /** Rotate a bitmap by the given degrees. Recycles the source bitmap. */
    public static Bitmap rotateBitmap(Bitmap bitmap, int degrees) {
        if (degrees == 0) return bitmap;
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        bitmap.recycle();
        return rotated;
    }

    /**
     * Crop the center square region (size = min(w,h)/2).
     *
     * @param src        source bitmap
     * @param recycleSrc if true, recycles the source after crop
     */
    public static Bitmap cropCenterSquare(Bitmap src, boolean recycleSrc) {
        int width = src.getWidth();
        int height = src.getHeight();
        int cropSize = Math.min(width, height) / 2;
        int x = (width - cropSize) / 2;
        int y = (height - cropSize) / 2;
        Bitmap cropped = Bitmap.createBitmap(src, x, y, cropSize, cropSize);
        if (recycleSrc) {
            src.recycle();
        }
        return cropped;
    }

    /** Convenience overload — always recycles source. */
    public static Bitmap cropCenterSquare(Bitmap src) {
        return cropCenterSquare(src, true);
    }

    /**
     * Run inference 3× on the cropped bitmap and return the majority-vote result.
     * This method is blocking — call it from a background thread.
     *
     * @param helper       initialized TFLiteHelper
     * @param croppedBitmap already-cropped bitmap (NOT recycled by this method)
     * @return InferenceResult with majority label and average confidence, or null on error
     */
    public static InferenceResult runMajorityVoteInference(TFLiteHelper helper, Bitmap croppedBitmap) {
        if (helper == null || croppedBitmap == null) return null;

        String[] labels = new String[3];
        float[] confidences = new float[3];

        for (int i = 0; i < 3; i++) {
            InferenceResult res = helper.classifyImage(croppedBitmap);
            if (res != null) {
                labels[i] = res.getLabel();
                confidences[i] = res.getConfidence();
            } else {
                labels[i] = "Không xác định";
                confidences[i] = 0.0f;
            }
        }

        // Count frequencies
        Map<String, Integer> freqMap = new HashMap<>();
        for (String label : labels) {
            freqMap.put(label, freqMap.getOrDefault(label, 0) + 1);
        }

        // Find majority label
        String majorityLabel = labels[0];
        int maxCount = 0;
        for (Map.Entry<String, Integer> entry : freqMap.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                majorityLabel = entry.getKey();
            }
        }

        // Average confidence for the majority label
        float sumConfidence = 0.0f;
        int countConfidence = 0;
        for (int i = 0; i < 3; i++) {
            if (labels[i].equals(majorityLabel)) {
                sumConfidence += confidences[i];
                countConfidence++;
            }
        }
        float avgConfidence = countConfidence > 0 ? (sumConfidence / countConfidence) : 0.0f;

        return new InferenceResult(majorityLabel, avgConfidence);
    }
}
