package com.mu.qrcodestreamclient;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Writes the reconstructed payload to the public Download folder. */
public final class CaptureWriter {

    public static final String MIME_TYPE = "application/octet-stream";

    private CaptureWriter() {}

    /** Human-readable output path for the landing screen (runtime-resolved Download folder). */
    @SuppressWarnings("deprecation")
    public static String downloadDisplayPath(@NonNull String fileName) {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return new File(downloads, fileName).getAbsolutePath();
    }

    /**
     * Writes {@code data} as {@code fileName} in the public Download folder.
     *
     * @return the absolute path (API &le; 28) or the MediaStore content URI string (API 29+).
     */
    public static String write(@NonNull Context context, @NonNull String fileName, @NonNull byte[] data)
            throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return writeViaMediaStore(context, fileName, data);
        }
        return writeToExternalStorage(fileName, data);
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static String writeViaMediaStore(Context context, String fileName, byte[] data) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("MediaStore refused to create " + fileName);
        }
        try {
            try (OutputStream out = resolver.openOutputStream(uri, "w")) {
                if (out == null) {
                    throw new IOException("No output stream for " + uri);
                }
                out.write(data);
                out.flush();
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return uri.toString();
        } catch (IOException e) {
            resolver.delete(uri, null, null);
            throw e;
        }
    }

    @SuppressWarnings("deprecation")
    private static String writeToExternalStorage(String fileName, byte[] data) throws IOException {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloads.exists() && !downloads.mkdirs()) {
            throw new IOException("Cannot create directory " + downloads);
        }
        File target = new File(downloads, fileName);
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(data);
            out.flush();
        }
        return target.getAbsolutePath();
    }
}
