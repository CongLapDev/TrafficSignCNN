package com.example.trafficsigncnn;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentId;
import com.google.firebase.firestore.ServerTimestamp;

/**
 * Firestore document model for a single scan result.
 *
 * Collection path: users/{uid}/scan_history/{id}
 *
 * Fields (stored in Firestore):
 *  - label       Detected sign label, e.g. "Speed Limit 50"
 *  - confidence  Raw float [0.0 – 1.0]
 *  - imageUrl    Optional URL of captured image (empty string if none)
 *  - source      Origin of scan: "live" | "capture" | "gallery"
 *  - timestamp   Server-side Firestore Timestamp (auto-set via @ServerTimestamp)
 *
 * NOTE: uid is NOT duplicated here; it lives at the parent users/{uid} path.
 */
public class ScanHistory {

    @DocumentId
    private String id;

    private String    label;
    private float     confidence;
    private String    imageUrl;
    private String    source;

    @ServerTimestamp
    private Timestamp timestamp;

    /** Required by Firestore SDK for automatic deserialization. */
    public ScanHistory() {}

    public ScanHistory(String label, float confidence, String imageUrl,
                       String source) {
        this.label      = label;
        this.confidence = confidence;
        this.imageUrl   = imageUrl;
        this.source     = source;
        // timestamp is set server-side via @ServerTimestamp
    }

    // ─── Getters & Setters ─────────────────────────────────────────────────

    public String    getId()              { return id; }
    public void      setId(String id)     { this.id = id; }

    public String    getLabel()           { return label; }
    public void      setLabel(String v)   { this.label = v; }

    public float     getConfidence()      { return confidence; }
    public void      setConfidence(float v) { this.confidence = v; }

    public String    getImageUrl()        { return imageUrl; }
    public void      setImageUrl(String v){ this.imageUrl = v; }

    public String    getSource()          { return source; }
    public void      setSource(String v)  { this.source = v; }

    public Timestamp getTimestamp()       { return timestamp; }
    public void      setTimestamp(Timestamp v) { this.timestamp = v; }
}
