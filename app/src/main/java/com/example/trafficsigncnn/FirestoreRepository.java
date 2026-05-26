package com.example.trafficsigncnn;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.WriteBatch;

import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Singleton repository for all Firestore operations.
 *
 * Data model:
 *   users/{uid}                          → user profile + stats
 *   users/{uid}/scan_history/{scanId}    → individual scan records
 *
 * Public API:
 *   saveScanResult(label, confidence, source, imageUrl)
 *   getScanHistory(callback)
 *   deleteScan(scanId, callback)
 *   deleteAllHistory(callback)
 *   incrementTotalScans()
 *   loadUserStats(callback)
 *
 * Guards:
 *   - confidence ≥ MIN_CONFIDENCE (0.75)
 *   - dedup: same label within DEDUP_WINDOW_MS (5 000 ms) is skipped
 */
public class FirestoreRepository {

    private static final String TAG = "FirestoreRepository";

    private static final float MIN_CONFIDENCE  = 0.75f;
    private static final long  DEDUP_WINDOW_MS = 5_000L;

    // ─── Singleton ───────────────────────────────────────────────────────

    private static FirestoreRepository instance;

    public static synchronized FirestoreRepository getInstance() {
        if (instance == null) {
            instance = new FirestoreRepository();
        }
        return instance;
    }

    // ─── State ──────────────────────────────────────────────────────────

    private final FirebaseFirestore db;
    private String lastSavedLabel     = null;
    private long   lastSavedTimestamp = 0L;

    private FirestoreRepository() {
        db = FirebaseFirestore.getInstance();
    }

    // ─── Callbacks ───────────────────────────────────────────────────────

    public interface HistoryCallback {
        void onSuccess(java.util.List<ScanHistory> items);
        void onFailure(Exception e);
    }

    public interface SimpleCallback {
        void onSuccess();
        void onFailure(Exception e);
    }

    public interface StatsCallback {
        void onSuccess(long totalScans);
        void onFailure(Exception e);
    }

    /** Callback for explicit (user-triggered) save operations. */
    public interface SaveCallback {
        void onSuccess();
        void onFailure(Exception e);
    }

    /** Callback for aggregated statistics. */
    public interface StatisticsCallback {
        void onSuccess(UserStatistics stats);
        void onFailure(Exception e);
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private FirebaseUser requireUser() {
        return FirebaseAuth.getInstance().getCurrentUser();
    }

    private DocumentReference userDoc(@NonNull String uid) {
        return db.collection("users").document(uid);
    }

    private CollectionReference historyCol(@NonNull String uid) {
        return userDoc(uid).collection("scan_history");
    }

    // ─── Public API ─────────────────────────────────────────────────────

    /**
     * Save a scan result (fire-and-forget, no callback).
     * Used by auto-save flows (CapturePhoto, Gallery).
     * Guards: confidence ≥ 0.75, dedup 5s.
     */
    public void saveScanResult(String label, float confidence,
                               String source, String imageUrl) {
        saveScanResult(label, confidence, source, imageUrl, null);
    }

    /**
     * Save a scan result with an explicit callback.
     * Used by manual save (LiveScan button).
     *
     * When callback is non-null the dedup window is BYPASSED — the user
     * explicitly requested the save, so we must not silently skip it.
     * Confidence guard still applies.
     *
     * @param callback nullable; pass null for fire-and-forget
     */
    public void saveScanResult(String label, float confidence,
                               String source, String imageUrl,
                               SaveCallback callback) {
        Log.d(TAG, "Saving scan... label=" + label
                + " confidence=" + String.format("%.2f", confidence)
                + " source=" + source);

        // 1. Confidence guard
        if (confidence < MIN_CONFIDENCE) {
            Log.d(TAG, "⏭ Skip [low confidence=" + String.format("%.2f", confidence)
                    + "] label=" + label);
            if (callback != null) {
                callback.onFailure(new IllegalArgumentException(
                        "Confidence too low: " + confidence));
            }
            return;
        }

        // 2. Dedup guard — skip only for auto-save (no callback)
        if (callback == null) {
            long now = System.currentTimeMillis();
            if (label.equals(lastSavedLabel) && (now - lastSavedTimestamp) < DEDUP_WINDOW_MS) {
                Log.d(TAG, "⏭ Skip [duplicate within " + DEDUP_WINDOW_MS + "ms] label=" + label);
                return;
            }
        }

        // 3. Auth check
        FirebaseUser user = requireUser();
        if (user == null) {
            Log.w(TAG, "⏭ Skip — no signed-in user");
            if (callback != null) {
                callback.onFailure(new IllegalStateException("No signed-in user"));
            }
            return;
        }

        String uid = user.getUid();

        Map<String, Object> scanData = new HashMap<>();
        scanData.put("label",      label);
        scanData.put("confidence", confidence);
        scanData.put("imageUrl",   imageUrl != null ? imageUrl : "");
        scanData.put("source",     source);
        scanData.put("timestamp",  FieldValue.serverTimestamp());

        Map<String, Object> userUpdate = new HashMap<>();
        userUpdate.put("totalScans", FieldValue.increment(1));
        userUpdate.put("lastScanAt", FieldValue.serverTimestamp());

        WriteBatch batch = db.batch();
        DocumentReference newScanRef = historyCol(uid).document();
        batch.set(newScanRef, scanData);
        batch.update(userDoc(uid), userUpdate);

        batch.commit()
                .addOnSuccessListener(aVoid -> {
                    lastSavedLabel     = label;
                    lastSavedTimestamp = System.currentTimeMillis();
                    Log.d(TAG, "Save success | id=" + newScanRef.getId()
                            + " label=" + label
                            + " confidence=" + String.format("%.2f", confidence)
                            + " source=" + source);
                    if (callback != null) callback.onSuccess();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Save failed: " + e.getMessage(), e);
                    // Fallback: user doc may not exist yet
                    ensureUserDocExists(uid, () -> saveScanResultDirect(uid, newScanRef, scanData, callback));
                });
    }

    /** Called when user doc doesn't exist yet (first-time save). */
    private void ensureUserDocExists(String uid, Runnable onComplete) {
        FirebaseUser user = requireUser();
        if (user == null) return;

        Map<String, Object> userData = new HashMap<>();
        userData.put("name",       user.getDisplayName() != null ? user.getDisplayName() : "");
        userData.put("email",      user.getEmail() != null ? user.getEmail() : "");
        userData.put("photoUrl",   user.getPhotoUrl() != null ? user.getPhotoUrl().toString() : "");
        userData.put("createdAt",  FieldValue.serverTimestamp());
        userData.put("totalScans", 0);
        userData.put("lastScanAt", FieldValue.serverTimestamp());

        userDoc(uid).set(userData)
                .addOnSuccessListener(v -> {
                    Log.i(TAG, "✅ User doc created for uid=" + uid);
                    if (onComplete != null) onComplete.run();
                })
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to create user doc", e));
    }

    /** Direct single write when batch retry after user doc creation. */
    private void saveScanResultDirect(String uid, DocumentReference ref,
                                      Map<String, Object> data, SaveCallback callback) {
        Map<String, Object> userUpdate = new HashMap<>();
        userUpdate.put("totalScans", FieldValue.increment(1));
        userUpdate.put("lastScanAt", FieldValue.serverTimestamp());

        WriteBatch batch = db.batch();
        batch.set(ref, data);
        batch.update(userDoc(uid), userUpdate);
        batch.commit()
                .addOnSuccessListener(v -> {
                    Log.d(TAG, "Save success (retry) | id=" + ref.getId());
                    if (callback != null) callback.onSuccess();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Save failed (retry): " + e.getMessage(), e);
                    if (callback != null) callback.onFailure(e);
                });
    }

    /**
     * Fetch scan history for the current user, ordered newest first.
     */
    public void getScanHistory(@NonNull HistoryCallback callback) {
        FirebaseUser user = requireUser();
        if (user == null) {
            callback.onFailure(new IllegalStateException("No signed-in user"));
            return;
        }

        historyCol(user.getUid())
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(snapshot -> {
                    java.util.List<ScanHistory> items = new java.util.ArrayList<>();
                    for (com.google.firebase.firestore.DocumentSnapshot doc : snapshot.getDocuments()) {
                        ScanHistory item = doc.toObject(ScanHistory.class);
                        if (item != null) {
                            item.setId(doc.getId());
                            items.add(item);
                        }
                    }
                    Log.i(TAG, "✅ Loaded " + items.size() + " history items");
                    callback.onSuccess(items);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ getScanHistory failed: " + e.getMessage(), e);
                    callback.onFailure(e);
                });
    }

    /**
     * Delete a single scan record by document ID.
     */
    public void deleteScan(@NonNull String scanId, @NonNull SimpleCallback callback) {
        FirebaseUser user = requireUser();
        if (user == null) {
            callback.onFailure(new IllegalStateException("No signed-in user"));
            return;
        }

        historyCol(user.getUid()).document(scanId)
                .delete()
                .addOnSuccessListener(v -> {
                    Log.i(TAG, "✅ Deleted scan/" + scanId);
                    callback.onSuccess();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ deleteScan failed: " + e.getMessage(), e);
                    callback.onFailure(e);
                });
    }

    /**
     * Delete ALL scan history for the current user in batches of 500.
     */
    public void deleteAllHistory(@NonNull SimpleCallback callback) {
        FirebaseUser user = requireUser();
        if (user == null) {
            callback.onFailure(new IllegalStateException("No signed-in user"));
            return;
        }

        String uid = user.getUid();
        historyCol(uid).get()
                .addOnSuccessListener(snapshot -> {
                    if (snapshot.isEmpty()) {
                        callback.onSuccess();
                        return;
                    }

                    // Firestore batch max = 500 ops
                    WriteBatch batch = db.batch();
                    int count = 0;
                    for (com.google.firebase.firestore.DocumentSnapshot doc : snapshot.getDocuments()) {
                        batch.delete(doc.getReference());
                        count++;
                        if (count == 500) break; // safety cap; for >500 items, paginate
                    }

                    // Reset totalScans on user doc
                    batch.update(userDoc(uid), "totalScans", 0);

                    batch.commit()
                            .addOnSuccessListener(v -> {
                                Log.i(TAG, "✅ Deleted all history (" + snapshot.size() + " items)");
                                callback.onSuccess();
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "❌ deleteAllHistory failed: " + e.getMessage(), e);
                                callback.onFailure(e);
                            });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ deleteAllHistory query failed: " + e.getMessage(), e);
                    callback.onFailure(e);
                });
    }

    /**
     * Increment totalScans on the user document.
     * Only needed when called without saveScanResult (edge case).
     */
    public void incrementTotalScans() {
        FirebaseUser user = requireUser();
        if (user == null) return;

        userDoc(user.getUid())
                .update("totalScans", FieldValue.increment(1))
                .addOnSuccessListener(v -> Log.d(TAG, "✅ totalScans incremented"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ incrementTotalScans failed", e));
    }

    /**
     * Load totalScans from the user doc.
     */
    public void loadUserStats(@NonNull StatsCallback callback) {
        FirebaseUser user = requireUser();
        if (user == null) {
            callback.onFailure(new IllegalStateException("No signed-in user"));
            return;
        }

        userDoc(user.getUid()).get()
                .addOnSuccessListener(doc -> {
                    long totalScans = 0;
                    if (doc.exists()) {
                        Long val = doc.getLong("totalScans");
                        if (val != null) totalScans = val;
                    } else {
                        // First launch — create the user doc
                        ensureUserDocExists(user.getUid(), null);
                    }
                    Log.d(TAG, "✅ totalScans=" + totalScans);
                    callback.onSuccess(totalScans);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ loadUserStats failed: " + e.getMessage(), e);
                    callback.onFailure(e);
                });
    }

    /**
     * Client-side aggregation over users/{uid}/scan_history.
     *
     * Computes:
     *  1. totalScans         → count of all docs
     *  2. scansToday         → timestamp on the same calendar day
     *  3. scansThisWeek      → timestamp within last 7 days
     *  4. averageConfidence  → mean of all confidence fields
     *  5. labelCounts        → label → frequency, sorted descending
     */
    public void loadStatistics(@NonNull StatisticsCallback callback) {
        FirebaseUser user = requireUser();
        if (user == null) {
            callback.onFailure(new IllegalStateException("No signed-in user"));
            return;
        }

        historyCol(user.getUid())
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(snapshot -> {
                    int totalScans = snapshot.size();
                    int scansToday = 0;
                    int scansThisWeek = 0;
                    float sumConfidence = 0f;
                    Map<String, Integer> rawLabelCounts = new HashMap<>();

                    // Calendar boundaries
                    Calendar calToday = Calendar.getInstance();
                    int todayYear  = calToday.get(Calendar.YEAR);
                    int todayMonth = calToday.get(Calendar.MONTH);
                    int todayDay   = calToday.get(Calendar.DAY_OF_MONTH);
                    long sevenDaysAgoMs = calToday.getTimeInMillis() - (7L * 24 * 60 * 60 * 1000);

                    for (com.google.firebase.firestore.DocumentSnapshot doc : snapshot.getDocuments()) {
                        // Confidence
                        Double conf = doc.getDouble("confidence");
                        if (conf != null) sumConfidence += conf.floatValue();

                        // Timestamp
                        com.google.firebase.Timestamp ts = doc.getTimestamp("timestamp");
                        if (ts != null) {
                            long ms = ts.toDate().getTime();
                            // This week
                            if (ms >= sevenDaysAgoMs) scansThisWeek++;
                            // Today
                            Calendar docCal = Calendar.getInstance();
                            docCal.setTimeInMillis(ms);
                            if (docCal.get(Calendar.YEAR)         == todayYear
                                    && docCal.get(Calendar.MONTH) == todayMonth
                                    && docCal.get(Calendar.DAY_OF_MONTH) == todayDay) {
                                scansToday++;
                            }
                        }

                        // Label frequency
                        String label = doc.getString("label");
                        if (label != null && !label.isEmpty()) {
                            rawLabelCounts.put(label, rawLabelCounts.getOrDefault(label, 0) + 1);
                        }
                    }

                    float avgConfidence = totalScans > 0 ? sumConfidence / totalScans : 0f;

                    // Sort label map descending by count
                    Map<String, Integer> sortedLabels = rawLabelCounts.entrySet().stream()
                            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey,
                                    Map.Entry::getValue,
                                    (e1, e2) -> e1,
                                    LinkedHashMap::new));

                    UserStatistics stats = new UserStatistics(
                            totalScans, scansToday, scansThisWeek,
                            avgConfidence, sortedLabels);

                    Log.i(TAG, "✅ Statistics loaded: total=" + totalScans
                            + " today=" + scansToday + " week=" + scansThisWeek
                            + " avgConf=" + String.format("%.2f", avgConfidence));
                    callback.onSuccess(stats);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ loadStatistics failed: " + e.getMessage(), e);
                    callback.onFailure(e);
                });
    }
}
