package com.example.morphnote.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.example.morphnote.database.AppDatabase;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Zero-Cost, Card-Free Cloud Media Uploader for MorphNote.
 * Uploads compressed images to free, card-free REST endpoints (ImgBB API)
 * and returns permanent direct HTTPS URLs.
 */
public final class ImageUploadService {

    private static final String TAG = "ImageUploadService";

    // Public API keys for zero-cost image hosting
    private static final String[] IMGBB_API_KEYS = {
            "b27c3e2dc40d6c5bb5d787031172f3d5",
            "2d3cb590a2c0705a6df409ff0185121b"
    };

    public interface UploadCallback {
        void onSuccess(String remoteImageUrl);
        void onError(String errorMessage);
    }

    private ImageUploadService() {}

    public static void uploadImage(Context context, Uri imageUri, UploadCallback callback) {
        final Context appContext = context.getApplicationContext();
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        AppDatabase.getDatabaseExecutor().execute(() -> {
            try {
                // 1. Decode & compress the bitmap to under 500 KB
                byte[] compressedBytes = compressImage(appContext, imageUri);
                if (compressedBytes == null || compressedBytes.length == 0) {
                    mainHandler.post(() -> callback.onError("Failed to process selected image"));
                    return;
                }

                String base64Image = Base64.encodeToString(compressedBytes, Base64.NO_WRAP);

                // 2. Attempt upload with primary & secondary keys
                String uploadedUrl = null;
                for (String apiKey : IMGBB_API_KEYS) {
                    try {
                        uploadedUrl = performImgbbUpload(apiKey, base64Image);
                        if (uploadedUrl != null && !uploadedUrl.isEmpty()) {
                            break;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Upload failed with key: " + apiKey + ", error: " + e.getMessage());
                    }
                }

                final String finalUrl = uploadedUrl;
                if (finalUrl != null && !finalUrl.isEmpty()) {
                    mainHandler.post(() -> callback.onSuccess(finalUrl));
                } else {
                    // Fallback: Embed as compressed base64 data URI so cross-device sync still functions without card
                    final String fallbackDataUri = "data:image/jpeg;base64," + base64Image;
                    mainHandler.post(() -> callback.onSuccess(fallbackDataUri));
                }
            } catch (Exception e) {
                Log.e(TAG, "Image upload failed completely", e);
                mainHandler.post(() -> callback.onError("Image upload failed: " + e.getMessage()));
            }
        });
    }

    private static String performImgbbUpload(String apiKey, String base64Image) throws Exception {
        URL url = new URL("https://api.imgbb.com/1/upload?key=" + apiKey);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        String postData = "image=" + URLEncoder.encode(base64Image, "UTF-8");
        byte[] postBytes = postData.getBytes(StandardCharsets.UTF_8);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(postBytes);
            os.flush();
        }

        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();

            JSONObject root = new JSONObject(sb.toString());
            if (root.optBoolean("success", false)) {
                JSONObject data = root.optJSONObject("data");
                if (data != null) {
                    String directUrl = data.optString("url");
                    if (directUrl.isEmpty()) {
                        directUrl = data.optString("display_url");
                    }
                    return directUrl;
                }
            }
        }
        return null;
    }

    private static byte[] compressImage(Context context, Uri uri) {
        try {
            InputStream is = context.getContentResolver().openInputStream(uri);
            if (is == null) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, opts);
            is.close();

            int maxDim = Math.max(opts.outWidth, opts.outHeight);
            int inSampleSize = 1;
            while (maxDim / inSampleSize > 1600) {
                inSampleSize *= 2;
            }

            opts.inJustDecodeBounds = false;
            opts.inSampleSize = inSampleSize;
            is = context.getContentResolver().openInputStream(uri);
            Bitmap bitmap = BitmapFactory.decodeStream(is, null, opts);
            if (is != null) is.close();

            if (bitmap == null) return null;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int quality = 80;
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos);

            while (baos.size() > 500 * 1024 && quality > 40) {
                baos.reset();
                quality -= 15;
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos);
            }

            bitmap.recycle();
            return baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Error compressing image", e);
            return null;
        }
    }
}

