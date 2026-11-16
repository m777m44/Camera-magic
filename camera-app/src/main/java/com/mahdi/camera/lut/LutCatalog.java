package com.mahdi.camera.lut;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Loads the bundled Mahdi looks and user .cube / .png LUTs from app storage.
 *
 * <p>The bundled assets are generated deterministically by {@code camera_magic.app_lut} and a
 * Python test re-implements this module's rules to prove both produce identical atlases. A LUT
 * that previews differently from the saved photo would be a silent quality bug.
 */
public final class LutCatalog {
    private static final String ASSET_ROOT = "mahdi_lut";
    private static final String ASSET_INDEX = ASSET_ROOT + "/presets.json";
    private static final long MAX_FILE_BYTES = 8L * 1024L * 1024L;

    /** One selectable look. Exactly one of {@code assetPath} and {@code file} is set. */
    public static final class Preset {
        public final String id;
        public final String name;
        public final String assetPath;
        public final File file;

        public Preset(String id, String name, String assetPath, File file) {
            if ((assetPath == null) == (file == null)) {
                throw new IllegalArgumentException("A preset needs exactly one source");
            }
            if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_.-]*")) {
                throw new IllegalArgumentException("Invalid preset id");
            }
            this.id = id;
            this.name = name == null ? id : name;
            this.assetPath = assetPath;
            this.file = file;
        }

        public String displayName() {
            return name;
        }

        public boolean isCube() {
            return file != null && file.getName().toLowerCase(Locale.US).endsWith(".cube");
        }

        public boolean isBundled() {
            return assetPath != null;
        }
    }

    /** A shader-ready atlas plus the cube geometry the shader needs to sample it. */
    public static final class Atlas {
        public final Bitmap bitmap;
        public final float size;
        public final float tiles;
        public final float[] domainMin;
        public final float[] domainMax;

        Atlas(Bitmap bitmap, int size, float[] domainMin, float[] domainMax) {
            this.bitmap = bitmap;
            this.size = size;
            this.tiles = size;
            this.domainMin = domainMin;
            this.domainMax = domainMax;
        }

        public void recycle() {
            if (!bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private LutCatalog() { }

    /** Bundled looks in curated order, read from the manifest the generator writes. */
    public static List<Preset> bundled(AssetManager assets) throws IOException {
        String index = readAsset(assets, ASSET_INDEX);
        List<Preset> presets = new ArrayList<>();
        int position = 0;
        // A tiny scanner keeps the app free of extra JSON dependencies.
        while (true) {
            int idAt = index.indexOf("\"id\"", position);
            if (idAt < 0) break;
            String id = jsonString(index, idAt + 4);
            int nameAt = index.indexOf("\"name\"", idAt);
            String name = nameAt < 0 ? id : jsonString(index, nameAt + 6);
            if (id != null && id.matches("[a-z][a-z0-9_]*")) {
                presets.add(new Preset(id, name == null ? id : name,
                        ASSET_ROOT + "/luts/" + id + ".png", null));
            }
            position = idAt + 4;
        }
        if (presets.isEmpty()) throw new IOException("No bundled LUT presets found");
        return Collections.unmodifiableList(presets);
    }

    /** User LUTs previously imported into app-private storage, newest first. */
    public static List<Preset> imported(Context context) {
        File[] files = userDirectory(context).listFiles();
        List<Preset> result = new ArrayList<>();
        if (files == null) return result;
        for (File file : files) {
            String lower = file.getName().toLowerCase(Locale.US);
            if (!file.isFile() || !(lower.endsWith(".cube") || lower.endsWith(".png"))) continue;
            String label = file.getName().substring(0,
                    file.getName().length() - (lower.endsWith(".cube") ? 5 : 4));
            result.add(new Preset("user_" + file.lastModified(), label, null, file));
        }
        Collections.sort(result, (a, b) -> Long.compare(b.file.lastModified(), a.file.lastModified()));
        return result;
    }

    public static File userDirectory(Context context) {
        File directory = new File(context.getFilesDir(), "luts");
        if (!directory.isDirectory()) directory.mkdirs();
        return directory;
    }

    /** Copy a picked document into app storage under a collision-free, sanitised name. */
    public static File importInto(Context context, InputStream input, String displayName)
            throws IOException {
        File directory = userDirectory(context);
        if (!directory.isDirectory()) throw new IOException("App storage is unavailable");
        String safe = sanitise(displayName);
        File destination = new File(directory, safe);
        if (destination.exists()) {
            int dot = safe.lastIndexOf('.');
            String stem = dot > 0 ? safe.substring(0, dot) : safe;
            String suffix = dot > 0 ? safe.substring(dot) : "";
            destination = new File(directory, stem + "_" + System.currentTimeMillis() + suffix);
        }
        try (InputStream source = input;
             java.io.OutputStream output = new java.io.FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = source.read(buffer)) != -1) {
                total += read;
                if (total > MAX_FILE_BYTES) throw new IOException("LUT file is larger than 8 MB");
                output.write(buffer, 0, read);
            }
        } catch (IOException error) {
            // Never leave a partial LUT that the picker would then offer.
            if (destination.exists() && !destination.delete()) destination.deleteOnExit();
            throw error;
        }
        return destination;
    }

    public static void delete(File file) throws IOException {
        if (file != null && file.exists() && !file.delete() && file.exists()) {
            throw new IOException("Could not delete the LUT file");
        }
    }

    /**
     * Decode a preset into a shader-ready atlas.
     *
     * @throws CubeLut.LutException for a rejected .cube file (message is user-facing)
     * @throws IOException for an unreadable asset or a malformed .png atlas
     */
    public static Atlas load(AssetManager assets, Preset preset) throws IOException,
            CubeLut.LutException {
        if (preset == null) throw new IOException("No look selected");
        if (preset.isBundled()) {
            Bitmap bitmap = decodeBitmap(readAssetBytes(assets, preset.assetPath));
            int size = (int) Math.round(Math.sqrt(bitmap.getWidth()));
            return new Atlas(bitmap, size, new float[]{0f, 0f, 0f}, new float[]{1f, 1f, 1f});
        }
        if (preset.isCube()) {
            CubeLut cube = CubeLut.parse(new String(readFile(preset.file), "UTF-8"));
            return new Atlas(cube.toAtlas(), cube.size,
                    new float[]{cube.domainMin(0), cube.domainMin(1), cube.domainMin(2)},
                    new float[]{cube.domainMax(0), cube.domainMax(1), cube.domainMax(2)});
        }
        Bitmap bitmap = decodeBitmap(readFile(preset.file));
        int size = (int) Math.round(Math.sqrt(bitmap.getWidth()));
        return new Atlas(bitmap, size, new float[]{0f, 0f, 0f}, new float[]{1f, 1f, 1f});
    }

    static Bitmap decodeBitmap(byte[] bytes) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPremultiplied = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) throw new IOException("The LUT image could not be decoded");
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        // The shader only samples square atlases whose edge is a whole number of tiles.
        int size = (int) Math.round(Math.sqrt(width));
        if (width != height || size * size != width
                || size < CubeLut.MIN_SIZE || size > CubeLut.MAX_SIZE) {
            bitmap.recycle();
            throw new IOException("A LUT atlas must be a square "
                    + (CubeLut.MIN_SIZE * CubeLut.MIN_SIZE) + "–"
                    + (CubeLut.MAX_SIZE * CubeLut.MAX_SIZE) + " px image");
        }
        return bitmap;
    }

    static String readAsset(AssetManager assets, String path) throws IOException {
        return new String(readAssetBytes(assets, path), "UTF-8");
    }

    static byte[] readAssetBytes(AssetManager assets, String path) throws IOException {
        try (InputStream input = assets.open(path)) {
            return readAll(input);
        }
    }

    static byte[] readFile(File file) throws IOException {
        if (file.length() > MAX_FILE_BYTES) throw new IOException("LUT file is larger than 8 MB");
        try (InputStream input = new FileInputStream(file)) {
            return readAll(input);
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_FILE_BYTES) throw new IOException("File is larger than 8 MB");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String jsonString(String json, int from) {
        int quote = json.indexOf('"', from);
        if (quote < 0) return null;
        StringBuilder text = new StringBuilder();
        int end = quote + 1;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '\\' && end + 1 < json.length()) {
                text.append(json.charAt(end + 1));
                end += 2;
                continue;
            }
            if (c == '"') return text.toString();
            text.append(c);
            end++;
        }
        return null;
    }

    private static String sanitise(String name) {
        String base = name == null ? "look.cube" : name;
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.startsWith(".")) base = "look" + base;
        String lower = base.toLowerCase(Locale.US);
        if (!lower.endsWith(".cube") && !lower.endsWith(".png")) base = base + ".cube";
        if (base.length() > 64) {
            int dot = base.lastIndexOf('.');
            base = base.substring(0, Math.min(60, dot > 0 ? dot : base.length()))
                    + (dot > 0 ? base.substring(dot) : "");
        }
        return base;
    }
}
