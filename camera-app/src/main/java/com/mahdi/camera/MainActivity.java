package com.mahdi.camera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CaptureRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.ScaleAnimation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Original offline camera app. No Blackmagic code, artwork, login or network services. */
@SuppressLint("SetTextI18n")
public final class MainActivity extends Activity
        implements TextureView.SurfaceTextureListener, CameraEngine.Listener {
    // The palette lives in res/values/colors.xml so the theme and this code cannot drift.
    private int BACKGROUND;
    private int PANEL;
    private int PANEL_HIGH;
    private int ACCENT;
    private int MUTED;
    private int FAINT;
    private int STROKE;
    private int RECORDING;
    private static final int PERMISSIONS = 71;
    private static final int PICK_LOOK = 72;
    private CameraEngine engine;
    private CameraEngine.Capabilities caps;
    private GlPipeline gl;
    private Tuning tuning = Tuning.DEFAULT;
    private List<com.mahdi.camera.lut.LutCatalog.Preset> looks;
    private boolean lookAvailable;
    private String lookNotice = "";
    private CameraEngine.Controls controls = new CameraEngine.Controls();
    private PreviewView preview;
    private GuidesView guides;
    private TextView status;
    private TextView counter;
    private TextView zoomText;
    private TextView hint;
    private Button record;
    private Button lens;
    private Button resolution;
    private Button microphone;
    private Button shutterButton;
    private Button looksButton;
    private SeekBar strength;
    private Button iso;
    private Button shutter;
    private Button whiteBalance;
    private Button focus;
    private Button ev;
    private Button clips;
    private SeekBar zoom;
    private boolean resumed;
    private boolean microphoneWanted;
    private int gridMode;
    private boolean levelOn;
    private boolean histogramOn;
    private boolean safeAreaOn;
    private BracketPlan bracket;
    private HistogramView histogram;
    private TextView exposureInfo;
    private android.widget.ProgressBar progress;
    private Button gridButton;
    private Button levelButton;
    private Button histogramButton;
    private Button bracketButton;

    private boolean permissionsAsked;
    private Uri lastClip;
    private Uri lastPhoto;
    private String photoError;
    private SensorManager sensorManager;
    private Sensor gravitySensor;
    private boolean sensorListening;
    private final SensorEventListener gravityListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            if (guides != null) guides.updateGravity(event.values[0], event.values[1], event.values[2]);
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable clock = new Runnable() {
        @Override public void run() {
            if (engine != null && engine.isRecording() && counter != null) {
                long millis = SystemClock.elapsedRealtime() - engine.recordingSince();
                if (counter.getVisibility() != View.VISIBLE) counter.setVisibility(View.VISIBLE);
                // The dot breathes at 1 Hz; the digits only change once a second, so the
                // pulsing marker is what proves the recording is live.
                float pulse = Easing.pulse(millis, 1000L);
                counter.setAlpha(0.72f + 0.28f * pulse);
                counter.setText((pulse > 0.5f ? "●" : "○") + " REC  " + CameraMath.clock(millis / 1000));
                counter.setTextColor(Easing.lerpArgb(
                        context().getColor(R.color.mahdi_text), RECORDING, 0.55f + 0.45f * pulse));
            }
            handler.postDelayed(this, 200);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        adoptPalette();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        android.content.SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        microphoneWanted = preferences.getBoolean("microphone", true);
        gridMode = preferences.getInt("gridMode",
                preferences.getBoolean("grid", false) ? GuidesView.GRID_THIRDS : GuidesView.GRID_OFF);
        levelOn = preferences.getBoolean("level", false);
        histogramOn = preferences.getBoolean("histogram", false);
        safeAreaOn = preferences.getBoolean("safeArea", false);
        int bracketFrames = preferences.getInt("bracketFrames", 0);
        if (bracketFrames >= 3) {
            bracket = BracketPlan.bracket(bracketFrames,
                    preferences.getFloat("bracketSpread", 2f), 140);
        }
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        gravitySensor = sensorManager == null ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        permissionsAsked = saved != null && saved.getBoolean("permissionsAsked");
        restoreTuning();
        gl = new GlPipeline(this, new GlPipeline.Listener() {
            @Override public void lookChanged(String label, String message) {
                if (isFinishing()) return;
                lookNotice = message == null ? "" : message;
                if (message != null) toast(message);
                refresh();
            }

            @Override public void renderFailed(String message) {
                if (isFinishing()) return;
                lookAvailable = false;
                lookNotice = "Look rendering stopped: " + message;
                status.setText(lookNotice);
                toast("Live look rendering is unavailable; the preview is unfiltered.");
                engine.configure(controls);
                refresh();
            }
        });
        engine = new CameraEngine(this, this, gl);
        engine.setBracket(bracket);
        buildUi();
    }

    /** API 23 provides View#getContext for the palette; this keeps the call sites short. */
    private Context context() { return this; }

    /** Read the single source of truth for colour, with no per-widget hex literals. */
    private void adoptPalette() {
        BACKGROUND = context().getColor(R.color.mahdi_background);
        PANEL = context().getColor(R.color.mahdi_panel);
        PANEL_HIGH = context().getColor(R.color.mahdi_panel_high);
        ACCENT = context().getColor(R.color.mahdi_accent);
        MUTED = context().getColor(R.color.mahdi_text_muted);
        FAINT = context().getColor(R.color.mahdi_text_faint);
        STROKE = context().getColor(R.color.mahdi_stroke);
        RECORDING = context().getColor(R.color.mahdi_recording);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private int dimen(int resource) { return getResources().getDimensionPixelSize(resource); }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    /**
     * A chip. The background is a ripple over a rounded panel, so a press is acknowledged
     * before the action runs, and the state change itself is faded rather than snapped.
     */
    private Button button(String label, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(label); button.setTextSize(11); button.setAllCaps(false);
        button.setTextColor(reallyWhite()); button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setPadding(dp(12), dp(2), dp(12), dp(2));
        button.setMinWidth(dp(74)); button.setMinimumWidth(dp(74));
        button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
        android.graphics.drawable.Drawable background = chipBackground(false);
        button.setBackground(background);
        button.setStateListAnimator(null); // elevation animation fights the ripple on API 23
        button.setOnClickListener(action);
        button.setOnLongClickListener(view -> {
            // Long-press explains a chip instead of doing something different by surprise.
            if (view.getContentDescription() != null) toast(view.getContentDescription().toString());
            return true;
        });
        return button;
    }

    private static int reallyWhite() { return 0xFFFFFFFF; }

    /** Rounded panel with a translucent ripple; `active` gives the accent treatment. */
    private android.graphics.drawable.Drawable chipBackground(boolean active) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(active ? blend(PANEL_HIGH, ACCENT, 0.18f) : PANEL);
        shape.setCornerRadius(dimen(R.dimen.mahdi_chip_radius));
        shape.setStroke(dp(1), active ? blend(STROKE, ACCENT, 0.55f) : STROKE);
        RippleDrawable ripple = new RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.argb(56, 81, 214, 199)),
                shape, null);
        return ripple;
    }

    private static int blend(int from, int to, float amount) {
        return Easing.lerpArgb(from, to, amount);
    }

    /** The shutter: a filled red disc, or a ringed square-in-a-circle while recording. */
    private android.graphics.drawable.Drawable recordBackground(boolean recording) {
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(recording ? blend(PANEL, RECORDING, 0.22f) : RECORDING);
        disc.setStroke(dp(recording ? 3 : 4),
                recording ? RECORDING : context().getColor(R.color.mahdi_recording_rim));
        RippleDrawable ripple = new RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.argb(64, 255, 255, 255)),
                disc, null);
        return ripple;
    }

    /** Repaint a chip's background, animating between the two states. */
    private void setChipActive(Button button, boolean active) {
        if (button == null) return;
        Object tag = button.getTag(R.id.mahdi_chip_active);
        boolean was = Boolean.TRUE.equals(tag);
        button.setTag(R.id.mahdi_chip_active, active);
        if (was == active && tag != null) return;
        button.setBackground(chipBackground(active));
        if (tag == null) return; // first paint: no animation, nothing to animate from
        // A brief scale nudge makes a mode change feel acknowledged without a full animation.
        ScaleAnimation nudge = new ScaleAnimation(0.96f, 1f, 0.96f, 1f,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        nudge.setDuration(160);
        nudge.setInterpolator(new DecelerateInterpolator());
        button.startAnimation(nudge);
    }

    /**
     * Change a label with a short fade-in. The text is set synchronously and only the reveal is
     * animated, so a refresh storm can never leave a stale label waiting on a listener.
     */
    private void animateLabel(TextView view, String value) {
        if (view == null || value == null) return;
        CharSequence current = view.getText();
        if (current != null && current.toString().equals(value)) return;
        view.setText(value);
        AlphaAnimation reveal = new AlphaAnimation(0.35f, 1f);
        reveal.setDuration(130);
        reveal.setInterpolator(new AccelerateDecelerateInterpolator());
        view.startAnimation(reveal);
    }

    private void haptic(View view) {
        if (view == null) return;
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private void addChip(LinearLayout row, Button button) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2,
                dimen(R.dimen.mahdi_chip_height));
        p.setMargins(dp(3), dp(2), dp(3), dp(2)); row.addView(button, p);
    }

    private HorizontalScrollView scrolling(LinearLayout row) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false); scroll.setFillViewport(false);
        scroll.addView(row); return scroll;
    }

    private void buildUi() {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BACKGROUND);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout header = row(); header.setPadding(dimen(R.dimen.mahdi_gutter), 0, dp(14), 0);
        TextView title = text("MAHDI  /  CAMERA", 14, ACCENT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); title.setLetterSpacing(0.10f);
        header.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
        TextView privacy = text("OFFLINE · LOCAL", 9, MUTED);
        header.addView(privacy); root.addView(header, new LinearLayout.LayoutParams(-1, dp(44)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout viewport = new FrameLayout(this); viewport.setBackgroundColor(Color.BLACK);
        preview = new PreviewView(this);
        viewport.addView(preview, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        guides = new GuidesView(this); guides.setGrid(gridMode);
        guides.setLevelEnabled(levelOn); guides.setSafeAreaEnabled(safeAreaOn);
        viewport.addView(guides, new FrameLayout.LayoutParams(-1, -1));
        histogram = new HistogramView(this); histogram.setVisible(histogramOn);
        FrameLayout.LayoutParams histParams = new FrameLayout.LayoutParams(dp(132), dp(58),
                Gravity.TOP | Gravity.START);
        histParams.setMargins(dp(10), dp(10), 0, 0);
        viewport.addView(histogram, histParams);
        exposureInfo = text("", 9, ACCENT);
        exposureInfo.setTypeface(Typeface.MONOSPACE);
        exposureInfo.setGravity(Gravity.CENTER);
        exposureInfo.setBackgroundColor(Color.argb(140, 11, 15, 20));
        exposureInfo.setPadding(dp(6), 0, dp(6), 0);
        exposureInfo.setVisibility(histogramOn ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams infoParams = new FrameLayout.LayoutParams(-2, dp(22),
                Gravity.BOTTOM | Gravity.START);
        infoParams.setMargins(dp(10), 0, 0, dp(10));
        viewport.addView(exposureInfo, infoParams);
        hint = text("Allow camera access to start the live preview", 13, MUTED);
        hint.setGravity(Gravity.CENTER); hint.setPadding(dp(30), dp(15), dp(30), dp(15));
        hint.setOnClickListener(v -> requestAccess());
        viewport.addView(hint, new FrameLayout.LayoutParams(-1, -1));
        counter = text("STANDBY  00:00:00", 11, Color.WHITE);
        counter.setTypeface(Typeface.MONOSPACE); counter.setGravity(Gravity.CENTER);
        counter.setBackgroundColor(Color.argb(150, 11, 15, 20));
        FrameLayout.LayoutParams timerParams = new FrameLayout.LayoutParams(-2,
                dimen(R.dimen.mahdi_timer_height), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        timerParams.topMargin = dp(12); viewport.addView(counter, timerParams);
        if (landscape) body.addView(viewport, new LinearLayout.LayoutParams(0, -1, 1));
        else body.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));
        preview.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            guides.setFrame(preview.getLeft(), preview.getTop(), preview.getRight(), preview.getBottom());
            transform();
        });
        preview.setSurfaceTextureListener(this);

        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(5), dp(5), dp(5), dp(4)); panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setBackgroundColor(BACKGROUND);
        body.addView(panel, landscape ? new LinearLayout.LayoutParams(dp(252), -1)
                : new LinearLayout.LayoutParams(-1, -2));
        LinearLayout quick = row();
        lens = button("LENS", v -> chooseLens()); addChip(quick, lens);
        resolution = button("720p", v -> chooseResolution()); addChip(quick, resolution);
        gridButton = button(getString(GuidesView.gridLabelRes(gridMode)), v -> {
            haptic(v);
            gridMode = guides.nextGridMode();
            guides.setGrid(gridMode);
            animateLabel((Button) v, getString(GuidesView.gridLabelRes(gridMode)));
            setChipActive((Button) v, gridMode != GuidesView.GRID_OFF);
            getPreferences(MODE_PRIVATE).edit().putInt("gridMode", gridMode)
                    .putBoolean("grid", gridMode != GuidesView.GRID_OFF).apply();
        });
        gridButton.setContentDescription("Composition grid: tap to cycle off, thirds, golden ratio,"
                + " centre cross; long-press for the title-safe frame");
        gridButton.setOnLongClickListener(v -> {
            safeAreaOn = !safeAreaOn;
            guides.setSafeAreaEnabled(safeAreaOn);
            getPreferences(MODE_PRIVATE).edit().putBoolean("safeArea", safeAreaOn).apply();
            toast(safeAreaOn ? "Title-safe frame on" : "Title-safe frame off");
            return true;
        });
        setChipActive(gridButton, gridMode != GuidesView.GRID_OFF);
        addChip(quick, gridButton);
        levelButton = button(levelOn ? "LEVEL ON" : "LEVEL OFF", v -> {
            haptic(v);
            levelOn = !levelOn;
            setLevelEnabled(levelOn);
            getPreferences(MODE_PRIVATE).edit().putBoolean("level", levelOn).apply();
        });
        levelButton.setContentDescription("Horizon level: a screen-only tilt indicator for straight frames");
        setChipActive(levelButton, levelOn);
        addChip(quick, levelButton);
        histogramButton = button(histogramOn ? "HISTO ON" : "HISTO OFF", v -> {
            haptic(v);
            histogramOn = !histogramOn;
            histogram.setVisible(histogramOn);
            exposureInfo.setVisibility(histogramOn ? View.VISIBLE : View.GONE);
            if (!histogramOn) histogram.clear();
            setChipActive((Button) v, histogramOn);
            animateLabel((Button) v, histogramOn ? "HISTO ON" : "HISTO OFF");
            getPreferences(MODE_PRIVATE).edit().putBoolean("histogram", histogramOn).apply();
            refreshHistogramLoop();
        });
        histogramButton.setContentDescription(
                "Luminance histogram of the graded frame, with clipping advice; screen only");
        setChipActive(histogramButton, histogramOn);
        addChip(quick, histogramButton);
        microphone = button("MIC", v -> toggleMicrophone()); addChip(quick, microphone);
        panel.addView(scrolling(quick), new LinearLayout.LayoutParams(-1, dp(52)));
        LinearLayout lookRow = row();
        looksButton = button("LOOK", v -> chooseLook()); addChip(lookRow, looksButton);
        strength = new SeekBar(this); strength.setMax(100); strength.setProgress(70);
        strength.setContentDescription("Look strength");
        strength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int progress, boolean user) {
                if (!user) return;
                tuning = tuning.withStrength(progress / 100f);
                applyTuning();
            }
            @Override public void onStartTrackingTouch(SeekBar view) { }
            @Override public void onStopTrackingTouch(SeekBar view) { }
        });
        lookRow.addView(strength, new LinearLayout.LayoutParams(0, dp(48), 1));
        bracketButton = button(bracket == null ? "HDR OFF" : "HDR " + bracket.frames, v -> {
            haptic(v);
            chooseBracket();
        });
        bracketButton.setContentDescription(
                "HDR bracket: captures 3, 5 or 7 exposures and fuses them into one photo");
        setChipActive(bracketButton, bracket != null);
        addChip(lookRow, bracketButton);
        panel.addView(scrolling(lookRow), new LinearLayout.LayoutParams(-1, dp(52)));
        LinearLayout manual = row();
        iso = button("ISO\nAUTO", v -> chooseIso()); addChip(manual, iso);
        shutter = button("SHUTTER\nAUTO", v -> chooseShutter()); addChip(manual, shutter);
        whiteBalance = button("WB\nAUTO", v -> chooseWb()); addChip(manual, whiteBalance);
        focus = button("FOCUS\nAUTO", v -> chooseFocus()); addChip(manual, focus);
        ev = button("EV\n0.0", v -> chooseEv()); addChip(manual, ev);
        panel.addView(scrolling(manual), new LinearLayout.LayoutParams(-1, dp(52)));
        LinearLayout zoomRow = row(); zoomRow.setPadding(dp(8), 0, dp(8), 0);
        zoomText = text("1.0×", 11, ACCENT);
        zoomRow.addView(zoomText, new LinearLayout.LayoutParams(dp(44), dp(32)));
        zoom = new SeekBar(this); zoom.setMax(100);
        zoom.setContentDescription("Digital zoom");
        zoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int progress, boolean user) {
                if (user && caps != null) {
                    controls.zoom = 1 + (caps.maxZoom - 1) * progress / 100f;
                    if (zoomText != null) zoomText.setText(Easing.zoomLabel(controls.zoom));
                    updateControls();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar view) { }
            @Override public void onStopTrackingTouch(SeekBar view) { }
        });
        zoomRow.addView(zoom, new LinearLayout.LayoutParams(0, dp(32), 1));
        panel.addView(zoomRow);
        LinearLayout transport = row(); transport.setGravity(Gravity.CENTER);
        clips = button("LAST CLIP", v -> viewClip());
        clips.setContentDescription("Play the last saved recording");
        transport.addView(clips, new LinearLayout.LayoutParams(0, dp(48), 1));
        shutterButton = button("PHOTO", v -> takePhoto());
        shutterButton.setContentDescription("Take a still photo");
        transport.addView(shutterButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        record = button("REC", v -> toggleRecording());
        record.setTextSize(14); record.setContentDescription("Start video recording");
        record.setBackground(recordBackground(false));
        LinearLayout.LayoutParams recordParams = new LinearLayout.LayoutParams(
                dimen(R.dimen.mahdi_shutter_size), dimen(R.dimen.mahdi_shutter_size));
        recordParams.setMargins(dp(18), dp(3), dp(18), dp(3)); transport.addView(record, recordParams);
        Button about = button("ABOUT", v -> about());
        transport.addView(about, new LinearLayout.LayoutParams(0, dp(48), 1));
        panel.addView(transport, new LinearLayout.LayoutParams(-1, dp(74)));
        progress = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100); progress.setProgress(0); progress.setVisibility(View.GONE);
        progress.setIndeterminate(false);
        progress.setContentDescription("Capture progress");
        panel.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        status = text("Camera access required · tap the preview", 10, FAINT);
        status.setGravity(Gravity.CENTER); status.setMaxLines(2);
        panel.addView(status, new LinearLayout.LayoutParams(-1, getResources()
                .getDimensionPixelSize(R.dimen.mahdi_status_height)));
        setContentView(root); root.requestApplyInsets(); refresh();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        engine.pause();
        buildUi();
        startPreview();
        // buildUi recreated the overlay views, so the sensor and histogram loops must follow.
        requestSensors();
        refreshHistogramLoop();
    }

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAccess() {
        List<String> wanted = new ArrayList<>();
        if (!granted(Manifest.permission.CAMERA)) wanted.add(Manifest.permission.CAMERA);
        if (microphoneWanted && !granted(Manifest.permission.RECORD_AUDIO)) wanted.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT <= 28 && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            wanted.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!wanted.isEmpty()) {
            permissionsAsked = true; requestPermissions(wanted.toArray(new String[0]), PERMISSIONS);
        } else startPreview();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == PERMISSIONS) {
            if (!granted(Manifest.permission.RECORD_AUDIO)) microphoneWanted = false;
            if (!granted(Manifest.permission.CAMERA)) {
                hint.setVisibility(View.VISIBLE);
                hint.setText("Camera access is denied. Enable Camera in this app’s system permissions, then return.");
            }
            refresh(); startPreview();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("permissionsAsked", permissionsAsked); super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true;
        handler.post(clock);
        requestSensors();
        refreshHistogramLoop();
        if (!granted(Manifest.permission.CAMERA) && !permissionsAsked) requestAccess();
        else startPreview();
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(clock);
        handler.removeCallbacks(histogramLoop);
        if (sensorManager != null && sensorListening) {
            sensorManager.unregisterListener(gravityListener);
            sensorListening = false;
        }
        engine.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(histogramLoop);
        engine.shutdown();
        if (gl != null) gl.close();
        super.onDestroy();
    }

    private int displayDegrees() {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        if (rotation == Surface.ROTATION_90) return 90;
        if (rotation == Surface.ROTATION_180) return 180;
        if (rotation == Surface.ROTATION_270) return 270;
        return 0;
    }

    private void startPreview() {
        if (resumed && granted(Manifest.permission.CAMERA) && preview.isAvailable()) {
            engine.setAudio(microphoneWanted && granted(Manifest.permission.RECORD_AUDIO));
            engine.resume(preview, displayDegrees());
        }
    }

    private void transform() {
        if (caps == null || preview.getWidth() == 0 || preview.getHeight() == 0) return;
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        float cx = preview.getWidth() / 2f, cy = preview.getHeight() / 2f;
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            RectF viewRect = new RectF(0, 0, preview.getWidth(), preview.getHeight());
            RectF buffer = new RectF(0, 0, caps.videoSize.getHeight(), caps.videoSize.getWidth());
            buffer.offset(cx - buffer.centerX(), cy - buffer.centerY());
            matrix.setRectToRect(viewRect, buffer, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) preview.getHeight() / caps.videoSize.getHeight(),
                    (float) preview.getWidth() / caps.videoSize.getWidth());
            matrix.postScale(scale, scale, cx, cy);
            matrix.postRotate(90 * (rotation - 2), cx, cy);
        } else if (rotation == Surface.ROTATION_180) matrix.postRotate(180, cx, cy);
        preview.setTransform(matrix);
        engine.displayRotation(displayDegrees());
        if (gl != null && caps != null) {
            gl.setInputTransform(CameraMath.inputTransform(caps.sensorOrientation,
                    displayDegrees(), caps.front));
        }
    }

    private void toggleRecording() {
        if (!granted(Manifest.permission.CAMERA)) { requestAccess(); return; }
        if (Build.VERSION.SDK_INT <= 28 && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            requestAccess(); return;
        }
        if (engine.isBusy()) return;
        if (engine.isRecording()) { record.setEnabled(false); engine.stopRecording(); }
        else if (engine.isPreviewReady()) { record.setEnabled(false); engine.startRecording(); }
        else { startPreview(); toast("Waiting for camera preview…"); }
    }

    private void toggleMicrophone() {
        if (engine.isRecording() || engine.isBusy()) return;
        microphoneWanted = !microphoneWanted;
        if (microphoneWanted && !granted(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS);
        }
        getPreferences(MODE_PRIVATE).edit().putBoolean("microphone", microphoneWanted).apply();
        engine.setAudio(microphoneWanted && granted(Manifest.permission.RECORD_AUDIO)); refresh();
    }

    private void restoreTuning() {
        android.content.SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        float saved = preferences.getFloat("strength", 0.7f);
        String id = preferences.getString("look", null);
        tuning = Tuning.DEFAULT.withStrength(saved);
        if (id == null || id.isEmpty()) return;
        for (com.mahdi.camera.lut.LutCatalog.Preset preset : bundledLooks()) {
            if (preset.id.equals(id)) { tuning = tuning.withLook(preset); return; }
        }
        for (com.mahdi.camera.lut.LutCatalog.Preset preset : com.mahdi.camera.lut.LutCatalog.imported(this)) {
            if (preset.id.equals(id)) { tuning = tuning.withLook(preset); return; }
        }
        // A removed LUT falls back to "None" rather than silently applying another look.
    }

    private String readyStatusText() {
        String base = "MP4 · H.264"
                + (microphoneWanted && granted(Manifest.permission.RECORD_AUDIO) ? " + AAC" : " · silent");
        String look = tuning.look == null ? " · unfiltered"
                : " · " + tuning.look.displayName() + " " + Math.round(tuning.strength * 100) + "%";
        String warning = lookNotice.isEmpty() ? "" : " · " + lookNotice;
        return base + look + warning + " · DCIM/MahdiCamera";
    }

    /** Bundled looks first, then imported LUTs, then import/manage actions. */
    private void chooseLook() {
        if (engine.isRecording()) { toast("Stop the recording before changing the look."); return; }
        List<String> names = new ArrayList<>();
        names.add("None (no colour transform)");
        List<com.mahdi.camera.lut.LutCatalog.Preset> choices = new ArrayList<>();
        choices.add(null);
        for (com.mahdi.camera.lut.LutCatalog.Preset preset : bundledLooks()) {
            names.add(preset.displayName());
            choices.add(preset);
        }
        for (com.mahdi.camera.lut.LutCatalog.Preset preset : com.mahdi.camera.lut.LutCatalog.imported(this)) {
            names.add(preset.displayName() + "  ·  imported");
            choices.add(preset);
        }
        names.add("Import .cube or .png LUT…");
        if (com.mahdi.camera.lut.LutCatalog.imported(this).size() > 0) names.add("Remove imported LUT…");
        int checked = 0;
        for (int i = 1; i < choices.size(); i++) {
            if (tuning.look != null && choices.get(i) != null
                    && choices.get(i).id.equals(tuning.look.id)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Look · applied to preview and recording")
                .setSingleChoiceItems(names.toArray(new String[0]), checked, (dialog, index) -> {
                    dialog.dismiss();
                    if (index == names.size() - 1 && names.get(index).startsWith("Remove")) {
                        removeImportedLuts();
                    } else if (index == choices.size() - 1 && names.get(index).startsWith("Import")) {
                        importLook();
                    } else {
                        selectLook(choices.get(index));
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private List<com.mahdi.camera.lut.LutCatalog.Preset> bundledLooks() {
        if (looks != null) return looks;
        try {
            looks = com.mahdi.camera.lut.LutCatalog.bundled(getAssets());
        } catch (java.io.IOException error) {
            toast("Bundled looks are missing: " + error.getMessage());
            looks = new ArrayList<>();
        }
        return looks;
    }

    private void selectLook(com.mahdi.camera.lut.LutCatalog.Preset preset) {
        tuning = tuning.withLook(preset);
        strength.setProgress(Math.round(tuning.strength * 100));
        applyTuning();
    }

    private void applyTuning() {
        gl.apply(tuning);
        getPreferences(MODE_PRIVATE).edit()
                .putString("look", tuning.look == null ? "" : tuning.look.id)
                .putFloat("strength", tuning.strength).apply();
        refresh();
    }

    private void takePhoto() {
        if (!granted(Manifest.permission.CAMERA)) { requestAccess(); return; }
        if (Build.VERSION.SDK_INT <= 28 && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            requestAccess(); return;
        }
        if (engine.isRecording()) { toast("Stop the recording before taking a photo."); return; }
        if (!engine.isPreviewReady()) { toast("Waiting for camera preview…"); return; }
        shutterButton.setEnabled(false);
        haptic(shutterButton);
        // A quick dip-and-release on the shutter is the acknowledgement that a frame is
        // being captured; the button stays disabled until the save finishes.
        ScaleAnimation dip = new ScaleAnimation(1f, 0.9f, 1f, 0.9f,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        dip.setDuration(110);
        dip.setRepeatMode(Animation.REVERSE);
        dip.setRepeatCount(1);
        shutterButton.startAnimation(dip);
        status.setText(bracket == null ? "Capturing…" : "Capturing " + bracket.frames + " exposures…");
        engine.takePhoto();
    }

    private void importLook() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[]{"application/octet-stream", "text/plain", "image/png"});
        try {
            startActivityForResult(pick, PICK_LOOK);
        } catch (ActivityNotFoundException error) {
            toast("No file picker is available on this device.");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_LOOK || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri source = data.getData();
        String name = displayName(source);
        // Validate before copying: a bad .cube must be rejected, not saved and then render black.
        try {
            if (name.toLowerCase(Locale.US).endsWith(".cube")) {
                java.io.InputStream input = getContentResolver().openInputStream(source);
                if (input == null) throw new java.io.IOException("The file could not be opened.");
                String text;
                try {
                    text = new String(readAll(input), "UTF-8");
                } finally {
                    input.close();
                }
                com.mahdi.camera.lut.CubeLut cube = com.mahdi.camera.lut.CubeLut.parse(text);
                name = (cube.title == null || cube.title.isEmpty() ? name : cube.title + ".cube");
            }
            java.io.InputStream input = getContentResolver().openInputStream(source);
            if (input == null) throw new java.io.IOException("The file could not be opened.");
            java.io.File saved;
            try {
                saved = com.mahdi.camera.lut.LutCatalog.importInto(this, input, name);
            } finally {
                input.close();
            }
            selectLook(new com.mahdi.camera.lut.LutCatalog.Preset("imported_" + saved.lastModified(),
                    saved.getName(), null, saved));
            toast("Imported " + saved.getName());
        } catch (com.mahdi.camera.lut.CubeLut.LutException error) {
            toast("That .cube file was rejected: " + error.getMessage());
        } catch (java.io.IOException | RuntimeException error) {
            toast("Import failed: " + error.getMessage());
        }
    }

    private void removeImportedLuts() {
        List<com.mahdi.camera.lut.LutCatalog.Preset> imported =
                com.mahdi.camera.lut.LutCatalog.imported(this);
        if (imported.isEmpty()) { toast("No imported LUTs to remove."); return; }
        String[] names = new String[imported.size()];
        for (int i = 0; i < imported.size(); i++) names[i] = imported.get(i).displayName();
        new AlertDialog.Builder(this).setTitle("Remove imported LUT")
                .setItems(names, (dialog, index) -> {
                    com.mahdi.camera.lut.LutCatalog.Preset preset = imported.get(index);
                    if (tuning.look != null && tuning.look.file != null
                            && tuning.look.file.equals(preset.file)) {
                        tuning = tuning.withLook(null);
                        strength.setProgress(100);
                    }
                    try {
                        com.mahdi.camera.lut.LutCatalog.delete(preset.file);
                        toast("Removed " + preset.displayName());
                    } catch (java.io.IOException error) {
                        toast(error.getMessage());
                    }
                    applyTuning();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private String displayName(Uri uri) {
        String name = null;
        android.database.Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (RuntimeException ignored) {
            // Fall back to the last path segment below.
        } finally {
            if (cursor != null) cursor.close();
        }
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment() == null ? "look.cube" : uri.getLastPathSegment();
        }
        return name;
    }

    private static byte[] readAll(java.io.InputStream input) throws java.io.IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        long total = 0;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > 8L * 1024L * 1024L) throw new java.io.IOException("File is larger than 8 MB");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void setLevelEnabled(boolean enabled) {
        levelOn = enabled;
        guides.setLevelEnabled(enabled);
        setChipActive(levelButton, enabled);
        animateLabel(levelButton, enabled ? "LEVEL ON" : "LEVEL OFF");
        requestSensors();
        haptic(levelButton);
    }

    /** Start or stop the gravity sensor: it is only registered while the level is visible. */
    private void requestSensors() {
        if (sensorManager == null || gravitySensor == null) return;
        boolean wanted = levelOn && resumed;
        if (wanted == sensorListening) return;
        if (wanted) {
            sensorManager.registerListener(gravityListener, gravitySensor,
                    SensorManager.SENSOR_DELAY_GAME);
        } else {
            sensorManager.unregisterListener(gravityListener);
        }
        sensorListening = wanted;
    }

    /**
     * The histogram is sampled about four times a second from the graded frame, and only while
     * it is on screen: a hidden indicator must not cost a GPU readback per tick.
     */
    private void refreshHistogramLoop() {
        handler.removeCallbacks(histogramLoop);
        if (!histogramOn || !resumed) return;
        handler.postDelayed(histogramLoop, 250);
    }

    private volatile boolean histogramBusy;

    private final Runnable histogramLoop = new Runnable() {
        @Override public void run() {
            if (!histogramOn || !resumed || isFinishing()) return;
            sampleHistogram();
            handler.postDelayed(this, 400);
        }
    };

    private void sampleHistogram() {
        if (histogramBusy) return; // a slow readback must not queue up threads
        if (histogram == null || gl == null || !gl.isUsable() || !gl.hasFrame()) {
            if (exposureInfo != null && histogramOn) exposureInfo.setText("waiting for frame…");
            return;
        }
        histogramBusy = true;
        final Bitmap[] frame = new Bitmap[1];
        Thread reader = new Thread(() -> {
            frame[0] = gl.captureFrame();
            handler.post(() -> {
                histogramBusy = false;
                measureHistogram(frame[0]);
            });
        }, "MahdiHistogram");
        reader.start();
    }

    private void measureHistogram(Bitmap frame) {
        if (frame == null) return;
        try {
            int width = Math.min(96, frame.getWidth());
            int height = Math.max(1, Math.round(width * frame.getHeight() / (float) frame.getWidth()));
            Bitmap small = Bitmap.createScaledBitmap(frame, width, height, false);
            try {
                int[] pixels = new int[width * height];
                small.getPixels(pixels, 0, width, 0, 0, width, height);
                Histogram measurement = Histogram.of(pixels, 1);
                histogram.sample(measurement);
                if (exposureInfo != null && histogramOn) exposureInfo.setText(measurement.advice());
            } finally {
                if (small != frame) small.recycle();
            }
        } catch (OutOfMemoryError | RuntimeException ignored) {
            // A histogram is a convenience: never let it break the preview.
        } finally {
            frame.recycle();
        }
    }

    private void chooseBracket() {
        if (engine.isRecording()) { toast("Stop the recording before changing HDR."); return; }
        final String[] names = {"Off · single frame", "3 frames · ±1 EV (subtle)",
                "5 frames · ±2 EV (standard)", "7 frames · ±3 EV (extreme)"};
        int checked = bracket == null ? 0
                : bracket.frames == 3 ? 1 : bracket.frames == 5 ? 2 : 3;
        new AlertDialog.Builder(this).setTitle("HDR · fuse 3–7 exposures into one photo")
                .setSingleChoiceItems(names, checked, (dialog, index) -> {
                    dialog.dismiss();
                    setBracket(index == 0 ? null
                            : BracketPlan.bracket(index == 1 ? 3 : index == 2 ? 5 : 7,
                                    index == 1 ? 2f : index == 2 ? 4f : 6f, 140));
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void setBracket(BracketPlan plan) {
        bracket = plan;
        engine.setBracket(plan);
        animateLabel(bracketButton, plan == null ? "HDR OFF" : "HDR " + plan.frames);
        setChipActive(bracketButton, plan != null);
        getPreferences(MODE_PRIVATE).edit()
                .putInt("bracketFrames", plan == null ? 0 : plan.frames)
                .putFloat("bracketSpread", plan == null ? 2f
                        : plan.frames == 3 ? 2f : plan.frames == 5 ? 4f : 6f)
                .apply();
        toast(plan == null ? "HDR off · single-exposure photos"
                : "HDR on · " + plan.describe());
        refresh();
    }

    private void chooseLens() {
        if (caps == null || engine.isRecording()) return;
        String[] names = new String[caps.lenses.size()]; int checked = 0;
        for (int i = 0; i < names.length; i++) {
            names[i] = caps.lenses.get(i).name;
            if (caps.lenses.get(i).id.equals(caps.id)) checked = i;
        }
        new AlertDialog.Builder(this).setTitle("Available cameras")
                .setSingleChoiceItems(names, checked, (dialog, index) -> {
                    controls = new CameraEngine.Controls();
                    engine.selectLens(caps.lenses.get(index).id); dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }

    private void chooseResolution() {
        if (caps == null || engine.isRecording()) return;
        String[] names = new String[caps.sizes.size()]; int checked = 0;
        for (int i = 0; i < names.length; i++) {
            Size size = caps.sizes.get(i);
            names[i] = size.getWidth() + " × " + size.getHeight();
            if (size.equals(caps.videoSize)) checked = i;
        }
        new AlertDialog.Builder(this).setTitle("Video resolution · H.264")
                .setSingleChoiceItems(names, checked, (dialog, index) -> {
                    engine.selectSize(caps.sizes.get(index)); dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }

    private void chooseIso() {
        if (caps == null || !caps.manualSensor) return;
        List<Integer> values = new ArrayList<>(); values.add(caps.sensitivity.getLower());
        for (int iso : new int[]{100, 200, 400, 800, 1600, 3200, 6400}) {
            if (caps.sensitivity.contains(iso) && !values.contains(iso)) values.add(iso);
        }
        java.util.Collections.sort(values);
        String[] names = new String[values.size() + 1]; names[0] = "Auto exposure (ISO + shutter)";
        for (int i = 0; i < values.size(); i++) names[i + 1] = "ISO " + values.get(i);
        new AlertDialog.Builder(this).setTitle("Sensor sensitivity")
                .setItems(names, (dialog, index) -> {
                    controls.manualExposure = index != 0;
                    if (index != 0) controls.iso = values.get(index - 1);
                    updateControls();
                }).show();
    }

    private void chooseShutter() {
        if (caps == null || !caps.manualSensor) return;
        List<Long> values = new ArrayList<>(); List<String> names = new ArrayList<>();
        names.add("Auto exposure (ISO + shutter)");
        for (int denominator : new int[]{30, 50, 60, 100, 120, 250, 500, 1000, 2000}) {
            long time = 1_000_000_000L / denominator;
            if (caps.exposure.contains(time) && denominator >= caps.fps) {
                values.add(time); names.add("1/" + denominator + " s");
            }
        }
        new AlertDialog.Builder(this).setTitle("Shutter · limited by sensor and frame rate")
                .setItems(names.toArray(new String[0]), (dialog, index) -> {
                    controls.manualExposure = index != 0;
                    if (index != 0) controls.exposure = values.get(index - 1);
                    updateControls();
                }).show();
    }

    private void chooseWb() {
        if (caps == null) return;
        int[] modes = {CaptureRequest.CONTROL_AWB_MODE_AUTO, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
                CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
                CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT, CaptureRequest.CONTROL_AWB_MODE_SHADE};
        String[] titles = {"Auto", "Daylight", "Cloudy", "Incandescent", "Fluorescent", "Shade"};
        List<Integer> values = new ArrayList<>(); List<String> names = new ArrayList<>();
        for (int i = 0; i < modes.length; i++) if (CameraEngine.contains(caps.whiteBalances, modes[i])) {
            values.add(modes[i]); names.add(titles[i]);
        }
        new AlertDialog.Builder(this).setTitle("White balance presets")
                .setItems(names.toArray(new String[0]), (dialog, index) -> {
                    controls.whiteBalance = values.get(index); updateControls();
                }).show();
    }

    private void chooseFocus() {
        if (caps == null || caps.maxFocus <= 0) return;
        new AlertDialog.Builder(this).setTitle("Focus distance · sensor diopters")
                .setItems(new String[]{"Continuous autofocus", "Infinity", "25%", "50%", "75%", "Nearest"},
                        (dialog, index) -> {
                            controls.focus = index == 0 ? -1 : caps.maxFocus * (index - 1) / 4f;
                            updateControls();
                        }).show();
    }

    private void chooseEv() {
        if (caps == null || caps.compensation == null) return;
        if (controls.manualExposure) { toast("EV compensation needs automatic exposure."); return; }
        int low = caps.compensation.getLower(), high = caps.compensation.getUpper();
        String[] names = new String[high - low + 1];
        android.util.Rational step = caps.info.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        float increment = step == null ? 1 : step.floatValue();
        for (int i = low; i <= high; i++) names[i - low] = String.format(Locale.US, "%+.1f EV", i * increment);
        new AlertDialog.Builder(this).setTitle("Auto-exposure compensation")
                .setItems(names, (dialog, index) -> { controls.compensation = index + low; updateControls(); }).show();
    }

    private void updateControls() { engine.configure(controls); refresh(); }

    private void refresh() {
        if (record == null) return;
        boolean recording = engine != null && engine.isRecording();
        Object wasRecording = record.getTag(R.id.mahdi_chip_active);
        boolean stateChanged = !Boolean.valueOf(recording).equals(wasRecording);
        record.setText(recording ? "STOP" : "REC");
        if (stateChanged) {
            record.setBackground(recordBackground(recording));
            record.setTag(R.id.mahdi_chip_active, recording);
            ScaleAnimation pop = new ScaleAnimation(recording ? 1f : 0.86f, 1f,
                    recording ? 1f : 0.86f, 1f, Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f);
            pop.setDuration(220);
            pop.setInterpolator(new DecelerateInterpolator());
            record.startAnimation(pop);
        }
        if (bracketButton != null) {
            boolean hdr = bracket != null && caps != null && !controls.manualExposure
                    && caps.compensation != null
                    && !caps.compensation.getLower().equals(caps.compensation.getUpper());
            bracketButton.setAlpha(hdr || bracket == null ? 1f : 0.38f);
            bracketButton.setEnabled(!recording);
        }
        if (levelButton != null) levelButton.setEnabled(!recording);
        record.setContentDescription(recording ? "Stop and save video recording" : "Start video recording");
        record.setEnabled(engine == null || !engine.isBusy());
        microphone.setText(microphoneWanted && granted(Manifest.permission.RECORD_AUDIO) ? "MIC ON" : "MIC OFF");
        microphone.setEnabled(!recording); lens.setEnabled(caps != null && !recording);
        resolution.setEnabled(caps != null && !recording);
        iso.setEnabled(caps != null && caps.manualSensor); shutter.setEnabled(caps != null && caps.manualSensor);
        focus.setEnabled(caps != null && caps.maxFocus > 0);
        whiteBalance.setEnabled(caps != null && caps.whiteBalances != null && caps.whiteBalances.length > 1);
        ev.setEnabled(caps != null && caps.compensation != null && !controls.manualExposure
                && !caps.compensation.getLower().equals(caps.compensation.getUpper()));
        iso.setText("ISO\n" + (controls.manualExposure ? controls.iso : "AUTO"));
        shutter.setText("SHUTTER\n" + (controls.manualExposure ? "1/" + Math.round(1_000_000_000.0 / controls.exposure) : "AUTO"));
        whiteBalance.setText("WB\n" + (controls.whiteBalance == CaptureRequest.CONTROL_AWB_MODE_AUTO ? "AUTO" : "PRESET"));
        focus.setText("FOCUS\n" + (controls.focus < 0 ? "AUTO" : String.format(Locale.US, "%.1f D", controls.focus)));
        ev.setText("EV\n" + controls.compensation + " steps");
        zoomText.setText(Easing.zoomLabel(controls.zoom));
        zoom.setEnabled(caps != null && caps.maxZoom > 1);
        clips.setEnabled(lastClip != null);
        looksButton.setEnabled(!recording && !engine.isBusy());
        animateLabel(looksButton, tuning.look == null ? "LOOK OFF" : "LOOK");
        strength.setEnabled(!recording && tuning.look != null);
        if (caps != null) {
            lens.setText(caps.front ? "FRONT LENS" : "REAR LENS");
            resolution.setText(caps.videoSize.getHeight() + "p · " + caps.fps + "fps");
        }
        if (!recording && !engine.isBusy()) {
            // Never leave the PHOTO button disabled after a failure or a finished recording.
            shutterButton.setEnabled(engine.isPreviewReady());
        }
        for (Button button : new Button[]{iso, shutter, whiteBalance, focus, ev, microphone, lens,
                resolution, clips, looksButton, shutterButton}) {
            button.setAlpha(button.isEnabled() ? 1f : 0.38f);
        }
    }

    @Override public void capabilities(CameraEngine.Capabilities capabilities) {
        if (isFinishing()) return;
        caps = capabilities;
        if (!caps.manualSensor) controls.manualExposure = false;
        if (caps.maxFocus <= 0) controls.focus = -1;
        controls.zoom = Math.max(1, Math.min(controls.zoom, caps.maxZoom));
        engine.configure(controls); refresh(); transform();
    }

    @Override public void ready(boolean ready) {
        if (isFinishing()) return;
        if (!ready) {
            hint.setText(granted(Manifest.permission.CAMERA)
                    ? "Opening camera…" : "Tap to allow camera access");
            fadeHint(true);
        } else {
            fadeHint(false);
            status.setText(readyStatusText());
        }
        refresh();
    }

    /** Soft cross-fade for the viewport hint: a camera screen should never blink. */
    private void fadeHint(boolean show) {
        if (hint == null) return;
        if (show) {
            hint.setVisibility(View.VISIBLE);
            hint.animate().cancel();
            hint.animate().alpha(1f).setDuration(180).setInterpolator(
                    new AccelerateDecelerateInterpolator()).start();
        } else {
            hint.animate().cancel();
            hint.animate().alpha(0f).setDuration(140).withEndAction(() -> {
                if (hint != null) hint.setVisibility(View.GONE);
            }).start();
        }
    }

    @Override public void recording(boolean recording) {
        if (isFinishing()) return;
        setRequestedOrientation(recording ? ActivityInfo.SCREEN_ORIENTATION_LOCKED : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        counter.setAlpha(1f);
        counter.setText(recording ? "● REC  00:00:00" : "STANDBY");
        counter.setTextColor(recording ? context().getColor(R.color.mahdi_text) : MUTED);
        // The pill slides in from above when a recording starts, and fades out when it ends.
        counter.setTranslationY(recording ? -dp(6) : 0f);
        counter.animate().cancel();
        counter.animate().translationY(0f).alpha(1f).setDuration(200)
                .setInterpolator(new DecelerateInterpolator()).start();
        if (recording) { lastClip = null; status.setText("Recording locally · keep the app in the foreground"); }
        refresh();
    }

    @Override public void photo(Uri uri, String error) {
        if (isFinishing()) return;
        shutterButton.setAlpha(1f);
        shutterButton.setEnabled(true);
        photoError = error;
        if (error != null) { status.setText(error); refresh(); toast(error); return; }
        lastPhoto = uri;
        // The full GPU state is shown so a user (or a bug report) can tell whether the still
        // was graded or came from the unfiltered path.
        status.setText("Photo saved · " + tuning.describe()
                + (gl == null ? "" : " · " + gl.describeState()));
        toast("Photo saved");
        refresh();
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(view); }
        catch (ActivityNotFoundException ignored) { /* The gallery entry is still saved. */ }
    }

    @Override public void captureProgress(int done, int total, String label) {
        if (isFinishing() || progress == null) return;
        if (done <= 0) {
            progress.setVisibility(View.VISIBLE);
            progress.setProgress(0);
            status.setText("HDR · " + label);
            return;
        }
        int percent = total <= 0 ? 100 : Math.round(done * 100f / total);
        progress.animate().cancel();
        progress.setProgress(percent);
        if (done >= total) {
            status.setText("HDR · " + label);
            progress.animate().alpha(0f).setDuration(400).withEndAction(() -> {
                if (progress != null) {
                    progress.setVisibility(View.GONE);
                    progress.setAlpha(1f);
                }
            }).start();
        } else {
            status.setText("HDR · " + label);
        }
    }

    @Override public void lookActive(boolean active, String reason) {
        if (isFinishing()) return;
        lookAvailable = active;
        lookNotice = active ? "" : reason;
        if (!active && reason != null && !reason.isEmpty()) status.setText(reason);
        refresh();
    }

    @Override public void saved(Uri uri) {
        lastClip = uri; status.setText("Saved to DCIM/MahdiCamera"); refresh();
        toast("Video saved");
    }

    @Override public void error(String message) {
        if (isFinishing()) return;
        status.setText(message); refresh(); toast(message);
    }

    private void viewClip() {
        if (lastClip == null) return;
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(lastClip, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        view.setClipData(ClipData.newRawUri("Mahdi Camera clip", lastClip));
        try { startActivity(view); }
        catch (ActivityNotFoundException e) { toast("No video player installed. Your clip is in DCIM/MahdiCamera."); }
    }

    private void about() {
        new AlertDialog.Builder(this).setTitle("Mahdi Camera · independent & offline")
                .setMessage("Original open-source camera app. Not affiliated with Blackmagic Design and not the Blackmagic Camera app.\n\n"
                        + "Records H.264 MP4, optionally with AAC microphone audio, and saves JPEG stills. "
                        + "Manual ISO/shutter, focus, white-balance presets, EV and digital zoom depend on the selected camera. "
                        + "Grey controls are unsupported.\n\n"
                        + "LOOK applies a 3D colour cube on the GPU to the live preview, the recorded video and "
                        + "the photo, so all three match. Bundled looks plus your own .cube and .png LUTs. "
                        + "These are display-referred SDR looks: they cannot add sensor dynamic range, recover "
                        + "clipped highlights, or produce 10-bit/HLG output. When the GPU path is unavailable the "
                        + "status line says the preview and recording are unfiltered.\n\n"
                        + "NEW · HDR bracket: 3, 5 or 7 exposures are captured and fused on the device "
                        + "into one JPEG, so highlights and shadows both keep detail. Fusion is a "
                        + "multi-frame merge in 8-bit sRGB — it is not sensor HDR, RAW, or 10-bit output, "
                        + "and moving subjects can ghost between frames.\n\n"
                        + "NEW · Guides: thirds, golden-ratio and centre-cross grids, a title-safe frame "
                        + "and a horizon level that reads the gravity sensor only while it is switched on. "
                        + "The luminance histogram measures the graded preview frame, four times a second.\n\n"
                        + "Grid, level and histogram are preview guides only. No ProRes, Blackmagic Cloud or cloud upload.\n\n"
                        + "Photos and videos stay in DCIM/MahdiCamera. No Internet permission, ads, tracking or account. "
                        + "Source and MIT license: github.com/m777m44/Camera-magic")
                .setPositiveButton("OK", null).show();
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        if (preview.getSurfaceTexture() == texture) startPreview();
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        if (preview.getSurfaceTexture() == texture) transform();
    }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        // A detached, obsolete view can finish teardown after a replacement view
        // has become available during a configuration change. Do not pause that new preview.
        if (preview.getSurfaceTexture() == texture) engine.pause();
        return true;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }

    // Instrumentation exercises the same engine and record button as a user, never a fake camera.
    CameraEngine cameraForTest() { return engine; }
    void recordForTest() { toggleRecording(); }
    void photoForTest() { takePhoto(); }
    GlPipeline lookForTest() { return gl; }
    Tuning tuningForTest() { return tuning; }
    Uri lastPhotoForTest() { return lastPhoto; }
    String lookStateForTest() { return gl == null ? "no pipeline" : gl.describeState(); }
    Tuning defaultLookForTest() { return Tuning.DEFAULT; }
    String photoErrorForTest() { return photoError; }

    // Test hooks for the overlay and HDR work; the same paths the chips use.
    void gridForTest(int mode) {
        gridMode = mode;
        guides.setGrid(mode);
        setChipActive(gridButton, mode != GuidesView.GRID_OFF);
    }

    void levelForTest(boolean enabled) {
        setLevelEnabled(enabled);
        guides.setLevelEnabled(enabled);
    }

    void histogramForTest(boolean enabled) {
        histogramOn = enabled;
        histogram.setVisible(enabled);
        refreshHistogramLoop();
    }

    String overlayStateForTest() {
        String gridName = gridMode == GuidesView.GRID_THIRDS ? "thirds"
                : gridMode == GuidesView.GRID_GOLDEN ? "golden"
                : gridMode == GuidesView.GRID_CROSS ? "cross" : "off";
        return "grid=" + gridName + " mode=" + gridMode
                + " level=" + (guides.levelEnabled() ? "on" : "off")
                + " histo=" + (histogram.isShowing() ? "on" : "off")
                + " safe=" + (guides.safeAreaEnabled() ? "on" : "off");
    }

    String histogramAdviceForTest() {
        return histogram == null ? "" : histogram.advice();
    }

    void bracketForTest(BracketPlan plan) {
        setBracket(plan);
    }

    boolean bracketActiveForTest() {
        return engine != null && engine.bracketActive();
    }

    /** Select a look and strength exactly as the LOOK picker does. */
    void applyLookForTest(com.mahdi.camera.lut.LutCatalog.Preset preset, float value) {
        tuning = tuning.withLook(preset).withStrength(value);
        if (strength != null) strength.setProgress(Math.round(value * 100));
        applyTuning();
    }
}
