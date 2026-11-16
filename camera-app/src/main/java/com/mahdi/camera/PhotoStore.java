package com.mahdi.camera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes one JPEG still to DCIM/MahdiCamera, with Orientation copied from the camera.
 *
 * <p>The bitmap handed in is already the graded frame from the GPU pipeline, so the file
 * cannot differ from the preview because of a second, CPU-side re-application of the look.
 */
final class PhotoStore {
    private static final int QUALITY = 95;

    private PhotoStore() { }

    /** Save an encoder-produced JPEG (the plain, unfiltered sensor path). */
    static Uri saveJpegBytes(Context context, byte[] jpeg) throws IOException {
        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            throw new IOException("The camera returned an invalid JPEG");
        }
        String name = "Mahdi_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                .format(new Date()) + ".jpg";
        ContentResolver resolver = context.getContentResolver();
        Uri uri = null;
        File legacy = null;
        try {
            java.io.OutputStream output;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/MahdiCamera");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                uri = resolver.insert(
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
                if (uri == null) throw new IOException("Could not create a gallery entry");
                output = resolver.openOutputStream(uri);
            } else {
                File directory = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DCIM), "MahdiCamera");
                if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                    throw new IOException("Could not create DCIM/MahdiCamera");
                }
                legacy = new File(directory, name);
                output = new FileOutputStream(legacy);
            }
            if (output == null) throw new IOException("Could not open the photo output");
            try {
                output.write(jpeg);
                output.flush();
            } finally {
                output.close();
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
            } else {
                android.media.MediaScannerConnection.scanFile(context,
                        new String[]{legacy.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
            }
            return uri != null ? uri : Uri.fromFile(legacy);
        } catch (IOException | RuntimeException error) {
            if (uri != null) {
                try { resolver.delete(uri, null, null); } catch (RuntimeException ignored) { /* Gone. */ }
            }
            if (legacy != null && legacy.exists() && !legacy.delete()) legacy.deleteOnExit();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Could not save the photo: " + error.getMessage());
        }
    }

    static Uri saveJpeg(Context context, Bitmap bitmap, int unusedOrientation) throws IOException {
        if (bitmap == null || bitmap.isRecycled()) throw new IOException("No photo to save");
        String name = "Mahdi_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                .format(new Date()) + ".jpg";
        ContentResolver resolver = context.getContentResolver();
        Uri uri = null;
        OutputStream output = null;
        File legacy = null;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/MahdiCamera");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                uri = resolver.insert(
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
                if (uri == null) throw new IOException("Could not create a gallery entry");
                output = resolver.openOutputStream(uri);
            } else {
                File directory = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DCIM), "MahdiCamera");
                if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                    throw new IOException("Could not create DCIM/MahdiCamera");
                }
                legacy = new File(directory, name);
                output = new FileOutputStream(legacy);
            }
            if (output == null) throw new IOException("Could not open the photo output");
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, output)) {
                throw new IOException("The JPEG encoder rejected the frame");
            }
            output.close();
            output = null;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
            } else {
                android.media.MediaScannerConnection.scanFile(context,
                        new String[]{legacy.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
            }
            return uri != null ? uri : Uri.fromFile(legacy);
        } catch (IOException | RuntimeException | OutOfMemoryError error) {
            if (output != null) {
                try { output.close(); } catch (IOException ignored) { /* Best effort. */ }
            }
            if (uri != null) {
                try { resolver.delete(uri, null, null); } catch (RuntimeException ignored) { /* Gone. */ }
            }
            if (legacy != null && legacy.exists() && !legacy.delete()) legacy.deleteOnExit();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Could not save the photo: " + error.getMessage());
        }
    }
}
