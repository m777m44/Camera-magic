package com.mahdi.camera;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Owns a single pending MP4. Failed/too-short recordings never become gallery entries. */
final class ClipStore {
    interface Saved { void onSaved(Uri uri); }
    private final Context context;
    private Uri uri;
    private File legacyFile;
    private ParcelFileDescriptor descriptor;
    private boolean completed;

    ClipStore(Context context) { this.context = context.getApplicationContext(); }

    ParcelFileDescriptor open() throws IOException {
        String name = "Mahdi_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                .format(new Date()) + ".mp4";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/MahdiCamera");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
                uri = context.getContentResolver().insert(
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
                if (uri == null) throw new IOException("Could not create a gallery entry.");
                descriptor = context.getContentResolver().openFileDescriptor(uri, "w");
            } else {
                if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    throw new IOException("Allow storage access to save videos on Android 6–9.");
                }
                File directory = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DCIM), "MahdiCamera");
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IOException("Could not create DCIM/MahdiCamera.");
                }
                legacyFile = new File(directory, name);
                descriptor = ParcelFileDescriptor.open(legacyFile,
                        ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE
                                | ParcelFileDescriptor.MODE_READ_WRITE);
            }
            if (descriptor == null) throw new IOException("Could not open the video output.");
            return descriptor;
        } catch (IOException | RuntimeException exception) {
            discard();
            throw exception;
        }
    }

    void finish(Saved callback) throws IOException {
        closeDescriptor();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            if (context.getContentResolver().update(uri, values, null, null) != 1) {
                throw new IOException("Could not publish the completed recording.");
            }
            completed = true;
            callback.onSaved(uri);
        } else {
            completed = true;
            MediaScannerConnection.scanFile(context, new String[]{legacyFile.getAbsolutePath()},
                    new String[]{"video/mp4"}, (path, scanned) -> callback.onSaved(scanned));
        }
    }

    void discard() {
        try { closeDescriptor(); } catch (IOException ignored) { /* Best-effort cleanup. */ }
        if (completed) return;
        if (uri != null) {
            try { context.getContentResolver().delete(uri, null, null); }
            catch (RuntimeException ignored) { /* Media provider may have disconnected. */ }
        }
        if (legacyFile != null && legacyFile.exists() && !legacyFile.delete()) {
            legacyFile.deleteOnExit();
        }
    }

    private void closeDescriptor() throws IOException {
        if (descriptor != null) {
            ParcelFileDescriptor previous = descriptor;
            descriptor = null;
            previous.close();
        }
    }
}
