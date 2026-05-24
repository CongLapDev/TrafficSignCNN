package com.example.trafficsigncnn;

/**
 * Lightweight user session model.
 *
 * Currently uses placeholder data.
 * Firebase-ready: replace static fields with Firestore document reads
 * when auth integration is added.
 *
 * Firebase migration path:
 *   FirebaseAuth.getInstance().getCurrentUser() → uid
 *   FirebaseFirestore.getInstance().collection("users").document(uid).get()
 *       → map fields to this model
 */
public class UserSession {

    // Placeholder data — replace with Firebase reads
    private static final String PLACEHOLDER_NAME        = "Lập";
    private static final int    PLACEHOLDER_SCANS_TODAY = 12;
    private static final String PLACEHOLDER_MOST_DETECTED = "Giới hạn 60";
    private static final String MODEL_VERSION           = "custom_cnn_v1_fp16";
    private static final String APP_VERSION             = "1.0.0";

    private final String displayName;
    private final String avatarInitial;
    private final int    scansToday;
    private final String mostDetectedSign;
    private final String modelVersion;
    private final String appVersion;

    /** Creates a placeholder (pre-Firebase) session. */
    public static UserSession placeholder() {
        return new UserSession(
                PLACEHOLDER_NAME,
                PLACEHOLDER_SCANS_TODAY,
                PLACEHOLDER_MOST_DETECTED,
                MODEL_VERSION,
                APP_VERSION
        );
    }

    public UserSession(String displayName, int scansToday,
                       String mostDetectedSign, String modelVersion, String appVersion) {
        this.displayName     = displayName;
        this.avatarInitial   = displayName.isEmpty() ? "?" : String.valueOf(displayName.charAt(0)).toUpperCase();
        this.scansToday      = scansToday;
        this.mostDetectedSign = mostDetectedSign;
        this.modelVersion    = modelVersion;
        this.appVersion      = appVersion;
    }

    public String getDisplayName()      { return displayName; }
    public String getAvatarInitial()    { return avatarInitial; }
    public int    getScansToday()       { return scansToday; }
    public String getMostDetectedSign() { return mostDetectedSign; }
    public String getModelVersion()     { return modelVersion; }
    public String getAppVersion()       { return appVersion; }

    public String getAppInfoLine() {
        return "TrafficSign AI  \u00B7  Model " + modelVersion + "  \u00B7  App v" + appVersion;
    }
}
