package com.example.morphnote.util;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import android.webkit.MimeTypeMap;

import com.example.morphnote.database.AppDatabase;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Zero-Cost, Card-Free Document & File Cloud Uploader for MorphNote.
 * Uploads documents (PDF, DOCX, TXT, etc.) to free, card-free REST endpoints
 * (Catbox and File.io) and returns permanent downloadable HTTPS URLs for cross-device sync.
 */
public final class FileUploadService {

    private static final String TAG = "FileUploadService";

    public interface UploadCallback {
        void onSuccess(String fileName, String remoteUrl, String fileSizeStr);
        void onError(String errorMessage);
    }

    private FileUploadService() {}

    public static void uploadFile(Context context, Uri fileUri, UploadCallback callback) {
        final Context appContext = context.getApplicationContext();
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        AppDatabase.getDatabaseExecutor().execute(() -> {
            try {
                String fileName = getFileName(appContext, fileUri);
                byte[] fileBytes = readFileBytes(appContext, fileUri);
                if (fileBytes == null || fileBytes.length == 0) {
                    mainHandler.post(() -> callback.onError("Failed to read selected file"));
                    return;
                }

                String fileSizeStr = formatFileSize(fileBytes.length);
                String mimeType = appContext.getContentResolver().getType(fileUri);
                if (mimeType == null || mimeType.isEmpty()) {
                    String ext = MimeTypeMap.getFileExtensionFromUrl(fileName);
                    if (ext != null) {
                        mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
                    }
                }
                if (mimeType == null || mimeType.isEmpty()) {
                    mimeType = "application/octet-stream";
                }

                String uploadedUrl = null;

                // 1. Attempt Catbox API upload (zero-cost, no account required)
                try {
                    uploadedUrl = uploadToCatbox(fileBytes, fileName, mimeType);
                } catch (Exception e) {
                    Log.w(TAG, "Catbox upload attempt failed: " + e.getMessage());
                }

                // 2. Fallback to File.io API upload
                if (uploadedUrl == null || uploadedUrl.isEmpty()) {
                    try {
                        uploadedUrl = uploadToFileIo(fileBytes, fileName, mimeType);
                    } catch (Exception e) {
                        Log.w(TAG, "File.io upload attempt failed: " + e.getMessage());
                    }
                }

                if (uploadedUrl != null && !uploadedUrl.isEmpty()) {
                    final String finalUrl = uploadedUrl;
                    mainHandler.post(() -> callback.onSuccess(fileName, finalUrl, fileSizeStr));
                } else {
                    // 3. Fallback: Base64 data URI for small documents (< 1MB) so cross-device sync functions
                    if (fileBytes.length <= 1024 * 1024) {
                        String base64Data = Base64.encodeToString(fileBytes, Base64.NO_WRAP);
                        String dataUri = "data:" + mimeType + ";base64," + base64Data;
                        mainHandler.post(() -> callback.onSuccess(fileName, dataUri, fileSizeStr));
                    } else {
                        mainHandler.post(() -> callback.onError("Failed to upload file to cloud server. Check network connection."));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "File upload error", e);
                mainHandler.post(() -> callback.onError("Upload failed: " + e.getMessage()));
            }
        });
    }

    private static String uploadToCatbox(byte[] fileBytes, String fileName, String mimeType) throws Exception {
        String boundary = "===Boundary-" + UUID.randomUUID().toString() + "===";
        String lineEnd = "\r\n";
        String twoHyphens = "--";

        URL url = new URL("https://catbox.moe/user/api.php");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(25000);
        conn.setReadTimeout(35000);
        conn.setDoInput(true);
        conn.setDoOutput(true);
        conn.setUseCaches(false);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Connection", "Keep-Alive");
        conn.setRequestProperty("User-Agent", "MorphNote-Android/1.0");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (DataOutputStream dos = new DataOutputStream(conn.getOutputStream())) {
            // Form field: reqtype = fileupload
            dos.writeBytes(twoHyphens + boundary + lineEnd);
            dos.writeBytes("Content-Disposition: form-data; name=\"reqtype\"" + lineEnd + lineEnd);
            dos.writeBytes("fileupload" + lineEnd);

            // Form field: fileToUpload
            dos.writeBytes(twoHyphens + boundary + lineEnd);
            dos.writeBytes("Content-Disposition: form-data; name=\"fileToUpload\"; filename=\"" + fileName + "\"" + lineEnd);
            dos.writeBytes("Content-Type: " + mimeType + lineEnd + lineEnd);
            dos.write(fileBytes);
            dos.writeBytes(lineEnd);

            dos.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);
            dos.flush();
        }

        int status = conn.getResponseCode();
        if (status >= 200 && status < 300) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (line != null && line.trim().startsWith("http")) {
                    return line.trim();
                }
            }
        }
        return null;
    }

    private static String uploadToFileIo(byte[] fileBytes, String fileName, String mimeType) throws Exception {
        String boundary = "===Boundary-" + UUID.randomUUID().toString() + "===";
        String lineEnd = "\r\n";
        String twoHyphens = "--";

        URL url = new URL("https://file.io");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(25000);
        conn.setReadTimeout(35000);
        conn.setDoInput(true);
        conn.setDoOutput(true);
        conn.setUseCaches(false);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Connection", "Keep-Alive");
        conn.setRequestProperty("User-Agent", "MorphNote-Android/1.0");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (DataOutputStream dos = new DataOutputStream(conn.getOutputStream())) {
            dos.writeBytes(twoHyphens + boundary + lineEnd);
            dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"" + lineEnd);
            dos.writeBytes("Content-Type: " + mimeType + lineEnd + lineEnd);
            dos.write(fileBytes);
            dos.writeBytes(lineEnd);

            dos.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);
            dos.flush();
        }

        int status = conn.getResponseCode();
        if (status >= 200 && status < 300) {
            StringBuilder resp = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    resp.append(line);
                }
            }
            JSONObject json = new JSONObject(resp.toString());
            if (json.optBoolean("success", false) && json.has("link")) {
                return json.getString("link");
            }
        }
        return null;
    }

    private static String getFileName(Context context, Uri uri) {
        String name = null;
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int colIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (colIdx != -1) name = cursor.getString(colIdx);
                }
            } catch (Exception ignored) {}
        }
        if (name == null || name.trim().isEmpty()) {
            name = uri.getLastPathSegment();
        }
        if (name == null || name.trim().isEmpty()) {
            name = "document_" + System.currentTimeMillis();
        }
        return name;
    }

    private static byte[] readFileBytes(Context context, Uri uri) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) return null;
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
            return out.toByteArray();
        }
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}

