package com.example.trafficsigncnn;

import com.google.firebase.auth.FirebaseUser;

/**
 * Lightweight user session model.
 *
 * Populated from FirebaseAuth.getCurrentUser().
 * Stats fields (scansToday, mostDetectedSign) remain placeholder until
 * Firestore integration is added in a future sprint.
 */
public class UserSession {

    private static final String MODEL_VERSION = "custom_cnn_v1_fp16";
    private static final String APP_VERSION   = "1.0.0";

    private final String displayName;
    private final String email;
    private final String uid;
    private final String avatarInitial;
    private final int    scansToday;
    private final String mostDetectedSign;
    private final String modelVersion;
    private final String appVersion;

    /**
     * Creates a UserSession from a live FirebaseUser.
     * Falls back gracefully when displayName is not set (e.g. first-time Google login).
     */
    public static UserSession fromFirebaseUser(FirebaseUser user) {
        String name  = user.getDisplayName();
        String email = user.getEmail();
        String uid   = user.getUid();

        // Fallback: derive a name from email prefix when displayName is absent
        if (name == null || name.trim().isEmpty()) {
            name = (email != null && email.contains("@"))
                    ? email.substring(0, email.indexOf('@'))
                    : "Người dùng";
        }

        // Placeholder stats — replace with Firestore reads when ready
        return new UserSession(uid, name, email, 0, "—", MODEL_VERSION, APP_VERSION);
    }

    /** Legacy placeholder — kept for compile-time safety. Prefer fromFirebaseUser(). */
    @Deprecated
    public static UserSession placeholder() {
        return new UserSession(
                "uid_placeholder",
                "Người dùng",
                "",
                0,
                "—",
                MODEL_VERSION,
                APP_VERSION
        );
    }

    public UserSession(String uid, String displayName, String email,
                       int scansToday, String mostDetectedSign,
                       String modelVersion, String appVersion) {
        this.uid              = uid;
        this.displayName      = displayName;
        this.email            = email;
        this.scansToday       = scansToday;
        this.mostDetectedSign = mostDetectedSign;
        this.modelVersion     = modelVersion;
        this.appVersion       = appVersion;

        // Avatar initial: first character of displayName
        this.avatarInitial = (displayName != null && !displayName.isEmpty())
                ? String.valueOf(displayName.charAt(0)).toUpperCase()
                : "?";
    }

    public String getUid()              { return uid; }
    public String getDisplayName()      { return displayName; }
    public String getEmail()            { return email; }
    public String getAvatarInitial()    { return avatarInitial; }
    public int    getScansToday()       { return scansToday; }
    public String getMostDetectedSign() { return mostDetectedSign; }
    public String getModelVersion()     { return modelVersion; }
    public String getAppVersion()       { return appVersion; }

    public String getAppInfoLine() {
        return "TrafficSign AI  \u00B7  Model " + modelVersion + "  \u00B7  App v" + appVersion;
    }
}
