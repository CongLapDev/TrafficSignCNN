package com.example.trafficsigncnn;

import java.util.Map;

/**
 * Aggregated statistics computed client-side from users/{uid}/scan_history.
 *
 * Fields:
 *  - totalScans         count of all scan_history docs
 *  - scansToday         docs whose timestamp falls on today's calendar day
 *  - scansThisWeek      docs within the last 7 days
 *  - averageConfidence  mean of all confidence values
 *  - labelCounts        label → frequency map, sorted descending
 */
public class UserStatistics {

    private final int totalScans;
    private final int scansToday;
    private final int scansThisWeek;
    private final float averageConfidence;
    private final Map<String, Integer> labelCounts;

    public UserStatistics(int totalScans, int scansToday, int scansThisWeek,
                          float averageConfidence, Map<String, Integer> labelCounts) {
        this.totalScans        = totalScans;
        this.scansToday        = scansToday;
        this.scansThisWeek     = scansThisWeek;
        this.averageConfidence  = averageConfidence;
        this.labelCounts       = labelCounts;
    }

    public int   getTotalScans()        { return totalScans; }
    public int   getScansToday()        { return scansToday; }
    public int   getScansThisWeek()     { return scansThisWeek; }
    public float getAverageConfidence() { return averageConfidence; }
    public Map<String, Integer> getLabelCounts() { return labelCounts; }
}
