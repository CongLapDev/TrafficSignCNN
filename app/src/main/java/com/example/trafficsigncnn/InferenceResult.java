package com.example.trafficsigncnn;

public class InferenceResult {
    private final String label;
    private final float confidence;

    public InferenceResult(String label, float confidence) {
        this.label = label;
        this.confidence = confidence;
    }

    public String getLabel() {
        return label;
    }

    public float getConfidence() {
        return confidence;
    }
}
