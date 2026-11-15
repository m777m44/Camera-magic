package com.mahdi.camera.hdr;

import android.content.res.AssetManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Original display-referred SDR presets; not a sensor HDR/Log-to-display transform. */
public final class HdrPreset {
    public final String id;
    public final String name;
    public final int lutSize;
    public final int tiles;
    public final float clarity;
    public final float localContrast;

    private HdrPreset(JSONObject json, int size, int tileCount) throws JSONException {
        id = json.getString("id");
        name = json.getString("name");
        if (!id.matches("[a-z][a-z0-9_]*")) {
            throw new JSONException("Invalid preset ID");
        }
        lutSize = size;
        tiles = tileCount;
        clarity = finiteUnit((float) json.getDouble("clarity"));
        localContrast = finiteUnit((float) json.getDouble("local_contrast"));
    }

    static float finiteUnit(float value) {
        // Float.isFinite is not available on all API-23 runtimes.
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IllegalArgumentException("Non-finite filter parameter");
        }
        return Math.max(0f, Math.min(1f, value));
    }

    public String atlasAsset() {
        return "mahdi_hdr/luts/" + id + ".png";
    }

    static String readText(AssetManager assets, String path) throws IOException {
        try (InputStream input = assets.open(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        }
    }

    public static List<HdrPreset> load(AssetManager assets) throws IOException {
        try {
            JSONObject root = new JSONObject(readText(assets, "mahdi_hdr/presets.json"));
            if (root.getInt("schema") != 1) {
                throw new JSONException("Unsupported preset schema");
            }
            int size = root.getInt("lut_size");
            int tiles = root.getInt("tiles");
            if (size < 2 || size > 64 || tiles * tiles != size) {
                throw new JSONException("Invalid atlas dimensions");
            }
            JSONArray array = root.getJSONArray("presets");
            List<HdrPreset> result = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                HdrPreset preset = new HdrPreset(array.getJSONObject(i), size, tiles);
                for (HdrPreset existing : result) {
                    if (existing.id.equals(preset.id)) {
                        throw new JSONException("Duplicate preset ID");
                    }
                }
                result.add(preset);
            }
            if (result.isEmpty()) {
                throw new JSONException("No presets");
            }
            return Collections.unmodifiableList(result);
        } catch (JSONException | IllegalArgumentException error) {
            throw new IOException("Invalid HDR preset configuration", error);
        }
    }
}
