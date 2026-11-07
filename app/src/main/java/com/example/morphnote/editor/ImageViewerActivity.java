package com.example.morphnote.editor;

import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowInsetsControllerCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.example.morphnote.database.AppDatabase;
import com.example.morphnote.databinding.ActivityImageViewerBinding;

import java.io.OutputStream;

public class ImageViewerActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_URL = "extra_image_url";
    public static final String EXTRA_IMAGE_CAPTION = "extra_image_caption";

    private ActivityImageViewerBinding binding;
    private String imageUrl;
    private String caption;
    private Bitmap loadedBitmap;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityImageViewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Dark status bar with white icons
        WindowInsetsControllerCompat insetsController = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(false);

        imageUrl = getIntent().getStringExtra(EXTRA_IMAGE_URL);
        caption = getIntent().getStringExtra(EXTRA_IMAGE_CAPTION);

        if (caption != null && !caption.trim().isEmpty()) {
            binding.tvImageTitle.setText(caption.trim());
        } else {
            binding.tvImageTitle.setText("Image Preview");
        }

        binding.btnBack.setOnClickListener(v -> finish());

        binding.btnShare.setOnClickListener(v -> shareImage());

        binding.btnDownload.setOnClickListener(v -> saveImageToGallery());

        loadImage();
    }

    private void loadImage() {
        if (imageUrl == null || imageUrl.isEmpty()) {
            Toast.makeText(this, "Image URL is empty", Toast.LENGTH_SHORT).show();
            return;
        }

        Glide.with(this)
                .asBitmap()
                .load(imageUrl)
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                        loadedBitmap = resource;
                        binding.photoView.setImageBitmap(resource);
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {}

                    @Override
                    public void onLoadFailed(@Nullable Drawable errorDrawable) {
                        Toast.makeText(ImageViewerActivity.this, "Could not load image", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void shareImage() {
        if (imageUrl != null && (imageUrl.startsWith("http://") || imageUrl.startsWith("https://"))) {
            Intent sendIntent = new Intent(Intent.ACTION_SEND);
            sendIntent.setType("text/plain");
            sendIntent.putExtra(Intent.EXTRA_SUBJECT, caption != null ? caption : "Shared Image");
            sendIntent.putExtra(Intent.EXTRA_TEXT, imageUrl);
            startActivity(Intent.createChooser(sendIntent, "Share Image via"));
        } else if (loadedBitmap != null) {
            AppDatabase.getDatabaseExecutor().execute(() -> {
                try {
                    String path = MediaStore.Images.Media.insertImage(getContentResolver(), loadedBitmap, "MorphNote_Image", null);
                    if (path != null) {
                        Uri uri = Uri.parse(path);
                        runOnUiThread(() -> {
                            Intent shareIntent = new Intent(Intent.ACTION_SEND);
                            shareIntent.setType("image/*");
                            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                            startActivity(Intent.createChooser(shareIntent, "Share Image via"));
                        });
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "Failed to share image", Toast.LENGTH_SHORT).show());
                }
            });
        }
    }

    private void saveImageToGallery() {
        if (loadedBitmap == null) {
            Toast.makeText(this, "Image is still loading...", Toast.LENGTH_SHORT).show();
            return;
        }

        AppDatabase.getDatabaseExecutor().execute(() -> {
            try {
                String fileName = "MorphNote_" + System.currentTimeMillis() + ".jpg";
                OutputStream fos;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MorphNote");

                    Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) throw new Exception("Failed to create MediaStore entry");
                    fos = getContentResolver().openOutputStream(uri);
                } else {
                    String path = MediaStore.Images.Media.insertImage(getContentResolver(), loadedBitmap, fileName, "Image from MorphNote");
                    if (path == null) throw new Exception("Failed to insert image");
                    runOnUiThread(() -> Toast.makeText(this, "Saved to Gallery", Toast.LENGTH_SHORT).show());
                    return;
                }

                if (fos != null) {
                    loadedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                    fos.flush();
                    fos.close();
                    runOnUiThread(() -> Toast.makeText(this, "Saved to Gallery", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Failed to save: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }
}

