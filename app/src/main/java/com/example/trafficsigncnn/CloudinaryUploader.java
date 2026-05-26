package com.example.trafficsigncnn;

import android.graphics.Bitmap;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Uploads a Bitmap to Cloudinary via unsigned upload (no API secret required).
 *
 * Config:
 *   Cloud name  : doggmspyo
 *   Upload preset: traffic_sign_preset
 *   Endpoint    : https://api.cloudinary.com/v1_1/doggmspyo/image/upload
 *
 * Usage:
 *   CloudinaryUploader.uploadBitmap(bitmap, new CloudinaryUploader.UploadCallback() {
 *       public void onSuccess(String url) { ... }
 *       public void onFailure(Exception e) { ... }
 *   });
 *
 * Threading:
 *   OkHttp dispatches the network call on its own thread pool.
 *   Callbacks are invoked on that background thread — callers must
 *   use runOnUiThread() for any UI updates.
 */
public class CloudinaryUploader {

    private static final String TAG            = "CloudinaryUploader";
    private static final String CLOUD_NAME     = "doggmspyo";
    private static final String UPLOAD_PRESET  = "traffic_sign_preset";
    private static final String UPLOAD_URL     =
            "https://api.cloudinary.com/v1_1/" + CLOUD_NAME + "/image/upload";

    private static final int    JPEG_QUALITY   = 85;
    private static final OkHttpClient CLIENT   = new OkHttpClient();

    private CloudinaryUploader() {}

    // ─── Callback interface ────────────────────────────────────────────

    public interface UploadCallback {
        void onSuccess(String imageUrl);
        void onFailure(Exception e);
    }

    // ─── Public API ───────────────────────────────────────────────────

    /**
     * Compress {@code bitmap} to JPEG and upload to Cloudinary.
     * The callback is invoked on a background thread.
     *
     * @param bitmap   source bitmap (not recycled by this method)
     * @param callback result handler
     */
    public static void uploadBitmap(Bitmap bitmap, UploadCallback callback) {
        if (bitmap == null || bitmap.isRecycled()) {
            callback.onFailure(new IllegalArgumentException("Bitmap is null or recycled"));
            return;
        }

        // 1. Compress to JPEG bytes in memory
        byte[] jpegBytes = toJpeg(bitmap);
        if (jpegBytes == null) {
            callback.onFailure(new IOException("Failed to compress bitmap to JPEG"));
            return;
        }

        Log.d(TAG, "Uploading bitmap → " + jpegBytes.length + " bytes, preset=" + UPLOAD_PRESET);

        // 2. Build multipart request
        RequestBody fileBody = RequestBody.create(jpegBytes, MediaType.parse("image/jpeg"));
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "scan.jpg", fileBody)
                .addFormDataPart("upload_preset", UPLOAD_PRESET)
                .build();

        Request request = new Request.Builder()
                .url(UPLOAD_URL)
                .post(requestBody)
                .build();

        // 3. Execute asynchronously
        CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Upload failed (network): " + e.getMessage(), e);
                callback.onFailure(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response r = response) {
                    String body = r.body() != null ? r.body().string() : "";
                    if (!r.isSuccessful()) {
                        Log.e(TAG, "Upload HTTP error " + r.code() + ": " + body);
                        callback.onFailure(new IOException("HTTP " + r.code() + ": " + body));
                        return;
                    }

                    JSONObject json = new JSONObject(body);
                    String secureUrl = json.optString("secure_url", "");
                    if (secureUrl.isEmpty()) {
                        callback.onFailure(new IOException("No secure_url in response: " + body));
                        return;
                    }

                    Log.i(TAG, "Upload success: " + secureUrl);
                    callback.onSuccess(secureUrl);

                } catch (Exception e) {
                    Log.e(TAG, "Upload parse error: " + e.getMessage(), e);
                    callback.onFailure(e);
                }
            }
        });
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private static byte[] toJpeg(Bitmap bitmap) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
            return baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "toJpeg failed: " + e.getMessage(), e);
            return null;
        }
    }
}
