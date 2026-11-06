package com.example.morphnote.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.morphnote.database.AppDatabase;

public class BlockContentProvider extends ContentProvider {

    public static final String AUTHORITY = "com.example.morphnote.provider";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/blocks");

    private static final int CODE_BLOCKS = 1;
    private static final int CODE_BLOCK_ID = 2;
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        MATCHER.addURI(AUTHORITY, "blocks", CODE_BLOCKS);
        MATCHER.addURI(AUTHORITY, "blocks/*", CODE_BLOCK_ID);
    }

    private AppDatabase database;

    @Override
    public boolean onCreate() {
        if (getContext() != null) {
            database = AppDatabase.getInstance(getContext());
        }
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        if (database == null && getContext() != null) {
            database = AppDatabase.getInstance(getContext());
        }
        if (database == null) {
            return null;
        }

        int match = MATCHER.match(uri);
        Cursor cursor;
        switch (match) {
            case CODE_BLOCKS:
            case CODE_BLOCK_ID:
                cursor = database.blockDao().queryAllBlocksCursor();
                break;
            default:
                throw new IllegalArgumentException("Unsupported URI: " + uri);
        }

        if (getContext() != null && cursor != null) {
            cursor.setNotificationUri(getContext().getContentResolver(), uri);
        }
        return cursor;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        switch (MATCHER.match(uri)) {
            case CODE_BLOCKS:
                return "vnd.android.cursor.dir/vnd.com.example.morphnote.block";
            case CODE_BLOCK_ID:
                return "vnd.android.cursor.item/vnd.com.example.morphnote.block";
            default:
                throw new IllegalArgumentException("Unknown URI: " + uri);
        }
    }

    // Secure read-only enforcement
    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        throw new UnsupportedOperationException("BlockContentProvider is read-only");
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("BlockContentProvider is read-only");
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("BlockContentProvider is read-only");
    }
}

