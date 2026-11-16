package com.mahdi.camera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;

/** Camera2 + MediaRecorder. Preview and recording use the same capture controls. */
@SuppressWarnings("deprecation")
public final class CameraEngine {
    private static final String TAG = "MahdiCamera";
    // Also serialize old/new Activity instances during rapid recreation.
    private static final Semaphore CAMERA_LEASE = new Semaphore(1, true);

    interface Listener {
        void capabilities(Capabilities capabilities);
        void ready(boolean ready);
        void recording(boolean recording);
        void saved(Uri uri);
        void error(String message);
        /** A still photo finished saving, or failed with a message. Called on the main thread. */
        void photo(Uri uri, String error);
        /** The GPU look path is active (false after a fallback to the plain preview). */
        void lookActive(boolean active, String reason);
    }

    static final class Lens {
        final String id;
        final String name;
        Lens(String id, String name) { this.id = id; this.name = name; }
    }

    static final class Controls {
        boolean manualExposure;
        int iso = 200;
        long exposure = 16_666_667L;
        int whiteBalance = CaptureRequest.CONTROL_AWB_MODE_AUTO;
        float focus = -1; // -1 is continuous autofocus; otherwise real diopters.
        int compensation;
        float zoom = 1;

        Controls copy() {
            Controls c = new Controls();
            c.manualExposure = manualExposure; c.iso = iso; c.exposure = exposure;
            c.whiteBalance = whiteBalance; c.focus = focus;
            c.compensation = compensation; c.zoom = zoom;
            return c;
        }
    }

    static final class Capabilities {
        final String id;
        final List<Lens> lenses;
        final List<Size> sizes;
        final CameraCharacteristics info;
        final Range<Integer> sensitivity;
        final Range<Long> exposure;
        final Range<Integer> compensation;
        final int[] whiteBalances;
        final boolean manualSensor;
        final float maxFocus;
        final float maxZoom;
        final Size videoSize;
        final Range<Integer> fpsRange;
        final int fps;
        final int sensorOrientation;
        final boolean front;

        Capabilities(String id, List<Lens> lenses, CameraCharacteristics info, Size requested)
                throws IOException {
            this.id = id; this.lenses = lenses; this.info = info;
            sensitivity = info.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            exposure = info.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            compensation = info.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            whiteBalances = info.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
            manualSensor = contains(info.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
                    && sensitivity != null && exposure != null;
            Float focus = info.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            maxFocus = focus == null || !contains(info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
                    CaptureRequest.CONTROL_AF_MODE_OFF) ? 0 : focus;
            Float zoom = info.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            maxZoom = zoom == null ? 1 : Math.min(zoom, 8);
            Integer orientation = info.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orientation == null ? 90 : orientation;
            Integer facing = info.get(CameraCharacteristics.LENS_FACING);
            front = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
            StreamConfigurationMap map = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] available = map == null ? null : map.getOutputSizes(MediaRecorder.class);
            if (available == null || available.length == 0) throw new IOException("No video outputs on this lens.");
            List<Size> candidates = new ArrayList<>();
            for (Size size : available) {
                if (size.getWidth() <= 1920 && size.getHeight() <= 1080 && size.getWidth() >= 320) {
                    candidates.add(size);
                }
            }
            if (candidates.isEmpty()) throw new IOException("No supported video size at or below 1080p.");
            Collections.sort(candidates, (first, second) -> Long.compare(
                    (long) first.getWidth() * first.getHeight(),
                    (long) second.getWidth() * second.getHeight()));
            sizes = Collections.unmodifiableList(candidates);
            Size best = sizes.get(0);
            for (Size size : sizes) {
                if (size.equals(requested)) { best = size; break; }
                long distance = Math.abs((long) size.getWidth() * size.getHeight()
                        - (long) requested.getWidth() * requested.getHeight());
                long previous = Math.abs((long) best.getWidth() * best.getHeight()
                        - (long) requested.getWidth() * requested.getHeight());
                if (distance < previous) best = size;
            }
            videoSize = best;
            Range<Integer>[] ranges = info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            Range<Integer> chosen = ranges == null || ranges.length == 0 ? null : ranges[0];
            if (ranges != null) {
                for (Range<Integer> range : ranges) {
                    if (range.contains(30) && (chosen == null || !chosen.contains(30)
                            || range.getUpper() - range.getLower() < chosen.getUpper() - chosen.getLower())) {
                        chosen = range;
                    }
                }
            }
            fpsRange = chosen;
            fps = chosen == null ? 30 : Math.max(chosen.getLower(), Math.min(30, chosen.getUpper()));
        }
    }

    private final Context context;
    private final Listener listener;
    private final GlPipeline gl;
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("MahdiCamera2");
    private final Handler worker;
    private final CameraManager manager;
    private volatile boolean active;
    private volatile boolean previewReady;
    private volatile boolean isRecording;
    private volatile boolean busy;
    private volatile Uri lastSavedUri;
    private volatile String lastError = "";
    private volatile long recordingSince;
    private volatile int displayDegrees;
    private volatile boolean audioEnabled = true;
    private PreviewView texture;
    /** Camera2 target that feeds the GPU look pipeline, or null when rendering directly. */
    private Surface lookInputSurface;
    private boolean lookRendering;
    private ImageReader stillReader;
    private String selectedId;
    private Size requestedSize = new Size(1280, 720);
    private CameraDevice camera;
    private CameraCaptureSession session;
    private Surface previewSurface;
    private Surface recordSurface;
    private MediaRecorder recorder;
    private ClipStore output;
    private Capabilities caps;
    private Controls controls = new Controls();
    private int generation;
    private int sessionSerial;
    private boolean opening;
    private CameraDevice closingCamera;
    private boolean shuttingDown;
    private boolean holdsCameraLease;
    private long leaseDeadline;
    private int previewRetries;

    CameraEngine(Context context, Listener listener, GlPipeline lookPipeline) {
        this.context = context.getApplicationContext(); this.listener = listener;
        this.gl = lookPipeline;
        manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        thread.start(); worker = new Handler(thread.getLooper());
    }

    public boolean isPreviewReady() { return previewReady; }
    public boolean isRecording() { return isRecording; }
    public boolean isBusy() { return busy; }
    public Uri lastSavedUri() { return lastSavedUri; }
    public String lastError() { return lastError; }
    public long recordingSince() { return recordingSince; }
    void setAudio(boolean enabled) { audioEnabled = enabled; }
    void displayRotation(int degrees) { displayDegrees = degrees; }

    void resume(PreviewView texture, int degrees) {
        this.texture = texture; displayDegrees = degrees; active = true;
        worker.post(this::openCamera);
    }

    void pause() { active = false; worker.post(this::closeCamera); }

    void shutdown() {
        active = false;
        worker.post(() -> { shuttingDown = true; closeCamera(); finishShutdown(); });
    }

    private void finishShutdown() {
        if (shuttingDown && !opening && closingCamera == null) thread.quitSafely();
    }

    void selectLens(String id) {
        worker.post(() -> {
            if (isRecording || busy) return;
            selectedId = id; controls = new Controls(); closeCamera(); openCamera();
        });
    }

    void selectSize(Size size) {
        worker.post(() -> {
            if (isRecording || busy) return;
            requestedSize = size; closeCamera(); openCamera();
        });
    }

    void configure(Controls requested) {
        Controls copy = requested.copy();
        worker.post(() -> {
            controls = copy;
            if (session != null && previewReady) {
                try { repeat(isRecording); }
                catch (CameraAccessException | RuntimeException e) { fail("Control unavailable: " + e.getMessage()); }
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        // CameraDevice.close() is asynchronous. In particular, legacy HALs reject
        // a reopen before onClosed, even though our preview surface is already gone.
        if (!active || shuttingDown || texture == null || !texture.isAvailable()
                || opening || camera != null || closingCamera != null) return;
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            fail("Camera permission is required."); return;
        }
        if (!CAMERA_LEASE.tryAcquire()) {
            // Legacy HAL shutdown can legitimately take several seconds. Use a
            // bounded wall-clock deadline, not a retry count consumed by queued resumes.
            long now = android.os.SystemClock.elapsedRealtime();
            if (leaseDeadline == 0) leaseDeadline = now + 15000;
            if (now < leaseDeadline) worker.postDelayed(this::openCamera, 100);
            else {
                active = false; leaseDeadline = 0;
                fail("Camera is still closing. Tap the preview to retry.");
            }
            return;
        }
        holdsCameraLease = true; leaseDeadline = 0;
        try {
            String[] ids = manager.getCameraIdList();
            if (ids.length == 0) throw new IOException("No cameras were reported by Android.");
            List<Lens> lenses = new ArrayList<>();
            String preferred = ids[0];
            boolean rearSelected = false;
            for (String id : ids) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                String direction = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT
                        ? "Front" : "Rear";
                if ("Rear".equals(direction) && !rearSelected) { preferred = id; rearSelected = true; }
                float[] focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                String focalText = focal == null || focal.length == 0 ? "" : String.format(
                        java.util.Locale.US, " · %.1f mm", focal[0]);
                lenses.add(new Lens(id, direction + focalText + " [" + id + "]"));
            }
            if (selectedId == null) selectedId = preferred;
            caps = new Capabilities(selectedId, Collections.unmodifiableList(lenses),
                    manager.getCameraCharacteristics(selectedId), requestedSize);
            Capabilities current = caps;
            main.post(() -> listener.capabilities(current));
            opening = true;
            final int requestGeneration = ++generation;
            Log.i(TAG, "Opening camera " + selectedId + " generation " + requestGeneration);
            manager.openCamera(selectedId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    opening = false;
                    if (!active || generation != requestGeneration) { closeDevice(device); return; }
                    camera = device;
                    createPreview();
                }
                @Override public void onDisconnected(CameraDevice device) {
                    opening = false;
                    if (generation == requestGeneration) {
                        active = false; closeCamera();
                        fail("Camera disconnected. Tap the preview or return to the app to retry.");
                    }
                    closeDevice(device);
                }
                @Override public void onError(CameraDevice device, int error) {
                    opening = false;
                    if (generation == requestGeneration) {
                        active = false; closeCamera();
                        fail("Camera error " + error + ". Tap the preview to retry; another app may be using it.");
                    }
                    closeDevice(device);
                }
                @Override public void onClosed(CameraDevice device) {
                    Log.i(TAG, "Camera closed " + device.getId() + " generation " + requestGeneration);
                    if (closingCamera == device) {
                        closingCamera = null;
                        releaseCameraLease();
                        if (android.os.Build.VERSION.SDK_INT < 24) {
                            worker.postDelayed(CameraEngine.this::openCamera, 150);
                        } else {
                            openCamera();
                        }
                    }
                    finishShutdown();
                }
            }, worker);
        } catch (CameraAccessException | IOException | RuntimeException e) {
            opening = false; releaseCameraLease();
            fail("Cannot open camera: " + e.getMessage());
            finishShutdown();
        }
    }

    private Size previewSize() {
        StreamConfigurationMap map = caps.info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] choices = map == null ? null : map.getOutputSizes(SurfaceTexture.class);
        if (choices == null || choices.length == 0) return caps.videoSize;
        Size best = choices[0];
        long score = Long.MAX_VALUE;
        for (Size choice : choices) {
            if (choice.getWidth() > 1920 || choice.getHeight() > 1080) continue;
            long mismatch = Math.abs((long) choice.getWidth() * caps.videoSize.getHeight()
                    - (long) choice.getHeight() * caps.videoSize.getWidth());
            long distance = mismatch * 1_000_000L
                    + Math.abs((long) choice.getWidth() * choice.getHeight()
                    - (long) caps.videoSize.getWidth() * caps.videoSize.getHeight());
            if (distance < score) { best = choice; score = distance; }
        }
        return best;
    }

    /**
     * Some Camera2 implementations reject three concurrent outputs at preview size. Try the
     * full set, then the fallback, then the absolute minimum; only report a failure once no
     * combination is accepted. Never calls a callback with a null session.
     */
    private void configureSession(CameraCaptureSession.StateCallback callback,
                                  List<Surface> preferred, List<Surface> fallback,
                                  boolean look) {
        List<List<Surface>> attempts = new ArrayList<>();
        attempts.add(preferred);
        if (fallback != null) attempts.add(fallback);
        attempts.add(Collections.singletonList(preferred.get(0)));
        String last = null;
        for (List<Surface> attempt : attempts) {
            try {
                camera.createCaptureSession(attempt, callback, worker);
                return;
            } catch (CameraAccessException | RuntimeException error) {
                last = error.getMessage();
                Log.w(TAG, "Capture session rejected (" + attempt.size() + " surfaces): " + last);
            }
        }
        // Every combination failed: let the caller's normal rejection path run.
        sessionRejected(look, last == null ? "Camera rejected every session layout." : last);
    }

    /**
     * A renderer-backed session that never receives a frame would show a black preview.
     * After a short grace period, give up on the look and go back to the plain path so the
     * user always has a working camera, and say why.
     */
    private void watchFirstFrame() {
        if (!lookRendering || gl == null) return;
        worker.postDelayed(() -> {
            if (!active || !lookRendering || gl == null || gl.hasFrame()) return;
            Log.w(TAG, "Look pipeline received no frames; restoring the plain preview");
            lookRendering = false;
            gl.setPreviewSurface(null, 0, 0);
            main.post(() -> listener.lookActive(false,
                    "This device did not deliver frames to the look pipeline; the preview is unfiltered."));
            createPreview();
        }, 2500);
    }

    /** Shared rejection handling for a capture session that could not be created. */
    private void sessionRejected(boolean look, String message) {
        if (look) {
            Log.w(TAG, "Look session unavailable; falling back to the plain preview");
            lookRendering = false;
            if (gl != null) gl.setPreviewSurface(null, 0, 0);
            main.post(() -> listener.lookActive(false,
                    "The look pipeline was rejected by the camera; preview is unfiltered."));
            if (active && camera != null) createPreview();
        } else {
            fail("Preview configuration failed. Try another lens or resolution.");
        }
    }

    private void createPreview() {
        if (!active || camera == null || texture == null || !texture.isAvailable()) return;
        try {
            closeSession();
            if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
            Size size = previewSize();
            SurfaceTexture surfaceTexture = texture.getSurfaceTexture();
            if (surfaceTexture == null) return;
            surfaceTexture.setDefaultBufferSize(size.getWidth(), size.getHeight());
            previewSurface = new Surface(surfaceTexture);
            int sensor = CameraMath.rotation(caps.sensorOrientation, displayDegrees, caps.front);
            main.post(() -> {
                if (sensor == 90 || sensor == 270) texture.setAspectRatio(size.getHeight(), size.getWidth());
                else texture.setAspectRatio(size.getWidth(), size.getHeight());
            });

            // The look pipeline renders at preview resolution: GPU cost does not grow with
            // the recording resolution and the preview keeps the sensor aspect ratio.
            lookRendering = buildLookPlane(size);
            main.post(() -> listener.lookActive(lookRendering, lookRendering ? ""
                    : "Live look rendering is unavailable on this device; recording is unfiltered."));
            final Surface captureSurface = lookRendering ? lookInputSurface : previewSurface;
            if (lookRendering) {
                gl.setPreviewSurface(previewSurface, size.getWidth(), size.getHeight());
                gl.setInputTransform(CameraMath.inputTransform(caps.sensorOrientation, displayDegrees,
                        caps.front));
            }

            final int serial = ++sessionSerial;
            if (lookRendering) {
                // Two targets keep the TextureView satisfied if the renderer ever misses a
                // frame; the fallback is the renderer-only layout.
                configureSession(sessionCallback(serial, captureSurface, true),
                        Arrays.asList(previewSurface, captureSurface),
                        Collections.singletonList(captureSurface), true);
            } else {
                // The plain path has a single output, so it must not be listed twice: a
                // TextureView cannot skip frames and always consumes that one stream.
                configureSession(sessionCallback(serial, captureSurface, false),
                        Collections.singletonList(captureSurface), null, false);
            }
        } catch (RuntimeException e) { fail("Preview unavailable: " + e.getMessage()); }
    }

    /**
     * Try to route frames through the GPU look pipeline.
     *
     * @return true when the renderer owns an input surface and can present the preview
     */
    private boolean buildLookPlane(Size previewSize) {
        if (gl == null || gl.failed()) return false;
        // open() is asynchronous on the GL thread; it publishes the state it reached.
        gl.open(previewSize.getWidth(), previewSize.getHeight());
        Surface input = gl.cameraInput();
        if (input == null) {
            Log.w(TAG, "Look pipeline is not ready yet; using the plain preview");
            return false;
        }
        lookInputSurface = input;
        return true;
    }

    private CameraCaptureSession.StateCallback sessionCallback(final int serial,
                                                               final Surface captureSurface,
                                                               final boolean look) {
        return new CameraCaptureSession.StateCallback() {
            @Override public void onConfigured(CameraCaptureSession configured) {
                if (!active || camera == null || serial != sessionSerial) { configured.close(); return; }
                session = configured;
                try {
                    repeat(false); previewReady = true; lastError = ""; previewRetries = 0;
                    watchFirstFrame();
                    Log.i(TAG, "Preview ready generation " + generation + (look ? " (look)" : " (plain)"));
                    main.post(() -> listener.ready(true));
                } catch (CameraAccessException | RuntimeException e) {
                    configured.close(); fail("Preview failed: " + e.getMessage());
                }
            }
            @Override public void onConfigureFailed(CameraCaptureSession configured) {
                configured.close();
                if (serial != sessionSerial) return;
                if (look) {
                    // Never leave the user without a camera because a GPU path failed.
                    Log.w(TAG, "Look session rejected; falling back to the plain preview");
                    lookRendering = false;
                    if (gl != null) gl.setPreviewSurface(null, 0, 0);
                    main.post(() -> listener.lookActive(false,
                            "The look pipeline was rejected by the camera; preview is unfiltered."));
                    createPreview();
                } else {
                    // Closing the recorder leaves the HAL busy for a moment. One bounded
                    // retry avoids a dead preview without hiding a genuinely broken device.
                    if (previewRetries < 1) {
                        previewRetries++;
                        Log.w(TAG, "Plain preview rejected once; retrying after the camera settles");
                        worker.postDelayed(() -> {
                            if (active && camera != null) createPreview();
                        }, 400);
                    } else {
                        previewRetries = 0;
                        fail("Preview configuration failed. Try another lens or resolution.");
                    }
                }
            }
        };
    }

    private void repeat(boolean recording) throws CameraAccessException {
        if (camera == null || session == null || previewSurface == null) return;
        CaptureRequest.Builder request = camera.createCaptureRequest(
                recording ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
        request.addTarget(lookRendering ? lookInputSurface : previewSurface);
        if (recording && recordSurface != null) request.addTarget(recordSurface);
        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        if (caps.fpsRange != null) request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, caps.fpsRange);
        if (controls.manualExposure && caps.manualSensor) {
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            request.set(CaptureRequest.SENSOR_SENSITIVITY, CameraMath.clamp(controls.iso,
                    caps.sensitivity.getLower(), caps.sensitivity.getUpper()));
            request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, CameraMath.exposure(controls.exposure,
                    caps.exposure.getLower(), caps.exposure.getUpper(), caps.fps));
            request.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / caps.fps);
        } else {
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            if (caps.compensation != null) request.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    CameraMath.clamp(controls.compensation, caps.compensation.getLower(), caps.compensation.getUpper()));
        }
        int wb = contains(caps.whiteBalances, controls.whiteBalance) ? controls.whiteBalance
                : CaptureRequest.CONTROL_AWB_MODE_AUTO;
        request.set(CaptureRequest.CONTROL_AWB_MODE, wb);
        int[] afModes = caps.info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (controls.focus >= 0 && caps.maxFocus > 0
                && contains(afModes, CaptureRequest.CONTROL_AF_MODE_OFF)) {
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            request.set(CaptureRequest.LENS_FOCUS_DISTANCE, Math.min(controls.focus, caps.maxFocus));
        } else {
            int auto = contains(afModes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    : contains(afModes, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    ? CaptureRequest.CONTROL_AF_MODE_AUTO : CaptureRequest.CONTROL_AF_MODE_OFF;
            request.set(CaptureRequest.CONTROL_AF_MODE, auto);
        }
        Rect activeArray = caps.info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (activeArray != null) {
            float zoom = Math.max(1, Math.min(caps.maxZoom, controls.zoom));
            int width = (int) (activeArray.width() / zoom);
            int height = (int) (activeArray.height() / zoom);
            int left = activeArray.left + (activeArray.width() - width) / 2;
            int top = activeArray.top + (activeArray.height() - height) / 2;
            request.set(CaptureRequest.SCALER_CROP_REGION, new Rect(left, top, left + width, top + height));
        }
        session.setRepeatingRequest(request.build(), null, worker);
    }

    void startRecording() { worker.post(this::startRecordingInternal); }

    private void startRecordingInternal() {
        if (isRecording || busy) return;
        if (!active || !previewReady || camera == null) { fail("Wait for the camera preview before recording."); return; }
        busy = true; lastSavedUri = null;
        try {
            output = new ClipStore(context);
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(context) : new MediaRecorder();
            boolean audio = audioEnabled && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            if (audio) recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setOutputFile(output.open().getFileDescriptor());
            recorder.setVideoEncodingBitRate(caps.videoSize.getWidth() >= 1920 ? 12_000_000 : 6_000_000);
            recorder.setVideoFrameRate(caps.fps);
            recorder.setVideoSize(caps.videoSize.getWidth(), caps.videoSize.getHeight());
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            if (audio) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setAudioSamplingRate(44100); recorder.setAudioChannels(1);
                recorder.setAudioEncodingBitRate(128000);
            }
            // The GPU path already draws upright frames; only the plain path needs a hint.
            recorder.setOrientationHint(CameraMath.containerRotation(lookRendering,
                    caps.sensorOrientation, displayDegrees, caps.front));
            recorder.setOnErrorListener((r, what, extra) -> worker.post(() -> {
                stopRecordingInternal(true); fail("Recorder error " + what + ". Check storage space.");
            }));
            recorder.prepare();
            recordSurface = recorder.getSurface();
            if (lookRendering) gl.setEncoderSurface(recordSurface, 0L);
            closeSession();
            final int serial = ++sessionSerial;
            List<Surface> targets = lookRendering
                    ? Arrays.asList(previewSurface, lookInputSurface, recordSurface)
                    : Arrays.asList(previewSurface, recordSurface);
            List<Surface> fallback = lookRendering
                    ? Arrays.asList(lookInputSurface, recordSurface) : null;
            configureSession(
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession configured) {
                            if (!active || camera == null || serial != sessionSerial || recorder == null) {
                                configured.close(); return;
                            }
                            session = configured;
                            try {
                                repeat(true); recorder.start();
                                isRecording = true; busy = false; previewReady = true;
                                recordingSince = android.os.SystemClock.elapsedRealtime();
                                main.post(() -> listener.recording(true));
                            } catch (CameraAccessException | RuntimeException e) { recordingFailed(e); }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession configured) {
                            configured.close();
                            if (serial == sessionSerial) recordingFailed(new IOException("Video session rejected by camera."));
                        }
                    }, targets, fallback, true);
        } catch (IOException | RuntimeException e) { recordingFailed(e); }
    }

    void stopRecording() { worker.post(() -> stopRecordingInternal(true)); }

    /**
     * Capture one JPEG. With the look pipeline active the graded frame is read back from
     * the GPU, so the saved photo matches the preview exactly instead of being re-graded
     * with CPU code; otherwise the sensor still is encoded as-is.
     */
    void takePhoto() {
        worker.post(() -> {
            if (busy || isRecording) { photoFailed("Finish the current recording first."); return; }
            if (!active || !previewReady || camera == null || caps == null) {
                photoFailed("Wait for the camera preview before taking a photo.");
                return;
            }
            if (lookRendering && gl != null && gl.isUsable() && gl.hasFrame()) {
                busy = true;
                final Bitmap[] frame = new Bitmap[1];
                Thread reader = new Thread(() -> {
                    frame[0] = gl.captureFrame();
                    worker.post(() -> saveCapturedFrame(frame[0]));
                }, "MahdiStill");
                reader.start();
                return;
            }
            captureSensorStill();
        });
    }

    private void saveCapturedFrame(Bitmap frame) {
        busy = false;
        if (frame == null) {
            photoFailed("The look pipeline did not return a frame. Try again or turn the look off.");
            return;
        }
        try {
            Uri uri = PhotoStore.saveJpeg(context, frame, -1);
            main.post(() -> listener.photo(uri, null));
        } catch (IOException | RuntimeException error) {
            photoFailed("Could not save the photo: " + error.getMessage());
        } finally {
            frame.recycle();
        }
    }

    private void captureSensorStill() {
        if (stillReader != null) { photoFailed("A photo is already being taken."); return; }
        Size size = stillSize();
        busy = true;
        try {
            stillReader = ImageReader.newInstance(size.getWidth(), size.getHeight(),
                    android.graphics.ImageFormat.JPEG, 2);
        } catch (RuntimeException error) {
            busy = false;
            photoFailed("This camera cannot take a JPEG still: " + error.getMessage());
            return;
        }
        stillReader.setOnImageAvailableListener(reader -> {
            android.media.Image image = null;
            try {
                image = reader.acquireNextImage();
                if (image == null) return;
                java.nio.ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] jpeg = new byte[buffer.remaining()];
                buffer.get(jpeg);
                image.close();
                image = null;
                busy = false;
                Uri uri = PhotoStore.saveJpegBytes(context, jpeg);
                closeStillReader();
                if (active && camera != null) createPreview();
                main.post(() -> listener.photo(uri, null));
            } catch (IOException | RuntimeException error) {
                photoFailed("Could not save the photo: " + error.getMessage());
            } finally {
                if (image != null) image.close();
            }
        }, worker);
        try {
            closeSession();
            final int serial = ++sessionSerial;
            List<Surface> targets = new ArrayList<>();
            targets.add(lookRendering ? lookInputSurface : previewSurface);
            if (lookRendering) targets.add(previewSurface);
            targets.add(stillReader.getSurface());
            configureSession(new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configured) {
                    if (!active || camera == null || serial != sessionSerial) { configured.close(); return; }
                    session = configured;
                    try {
                        repeat(false);
                        CaptureRequest.Builder request = camera.createCaptureRequest(
                                CameraDevice.TEMPLATE_STILL_CAPTURE);
                        request.addTarget(stillReader.getSurface());
                        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        request.set(CaptureRequest.JPEG_ORIENTATION,
                                CameraMath.rotation(caps.sensorOrientation, displayDegrees, caps.front));
                        request.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
                        configured.capture(request.build(), new CameraCaptureSession.CaptureCallback() {
                            @Override public void onCaptureFailed(CameraCaptureSession failed,
                                                                  CaptureRequest failedRequest,
                                                                  android.hardware.camera2.CaptureFailure failure) {
                                photoFailed("The camera rejected the still capture.");
                            }
                        }, worker);
                    } catch (CameraAccessException | RuntimeException error) {
                        busy = false;
                        closeSession();
                        photoFailed("Still capture failed: " + error.getMessage());
                        if (active && camera != null) createPreview();
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession configured) {
                    configured.close();
                    if (serial != sessionSerial) return;
                    busy = false;
                    photoFailed("This camera cannot combine the preview with a JPEG still.");
                    closeStillReader();
                    if (active && camera != null) createPreview();
                }
            }, targets, Arrays.asList(stillReader.getSurface()), false);
            // `targets` above is the preferred layout; the JPEG reader alone is the fallback.
        } catch (RuntimeException error) {
            busy = false;
            photoFailed("Still capture failed: " + error.getMessage());
            closeStillReader();
            if (active && camera != null) createPreview();
        }
    }

    private void photoFailed(String message) {
        busy = false;
        closeStillReader();
        if (active && camera != null && session == null) createPreview();
        main.post(() -> listener.photo(null, message));
    }

    /** Close the JPEG reader only; rebuilding the preview is the caller's decision. */
    private void closeStillReader() {
        if (stillReader != null) {
            stillReader.close();
            stillReader = null;
        }
    }

    private Size stillSize() {
        StreamConfigurationMap map = caps.info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(android.graphics.ImageFormat.JPEG);
        Size best = null;
        if (sizes != null) {
            for (Size size : sizes) {
                if (!CameraMath.validPhotoSize(size.getWidth(), size.getHeight())) continue;
                long pixels = (long) size.getWidth() * size.getHeight();
                // Stay at or below 12 MP: larger buffers are slow and can exhaust RAM.
                if (pixels > 12_000_000L) continue;
                if (best == null || pixels > (long) best.getWidth() * best.getHeight()) best = size;
            }
            if (best == null) {
                for (Size size : sizes) {
                    if (CameraMath.validPhotoSize(size.getWidth(), size.getHeight())
                            && (best == null || (long) size.getWidth() * size.getHeight()
                            < (long) best.getWidth() * best.getHeight())) best = size;
                }
            }
        }
        if (best == null) throw new IllegalStateException("No JPEG output size reported");
        return best;
    }

    private long lastFrameNanos() { return gl == null ? 0L : gl.lastFrameNanos(); }

    private void recordingFailed(Exception e) {
        closeSession();
        if (lookRendering && gl != null) gl.setEncoderSurface(null, 0L);
        releaseRecorder();
        if (output != null) { output.discard(); output = null; }
        isRecording = false; busy = false;
        main.post(() -> listener.recording(false));
        fail("Recording failed: " + e.getMessage());
        createPreview();
    }

    private void stopRecordingInternal(boolean restart) {
        if (!isRecording && !busy) return;
        boolean success = isRecording;
        closeSession();
        if (lookRendering && gl != null) {
            // Repeat the final frame so MediaCodec sees monotonic timestamps, then detach
            // before MediaRecorder.stop() drains the surface on API 29+.
            gl.setEncoderSurface(null, lastFrameNanos());
            gl.flushEncoderDetach();
        }
        if (isRecording && recorder != null) {
            try { recorder.stop(); }
            catch (RuntimeException e) { success = false; fail("Clip was too short or could not be finalized. It was discarded."); }
        }
        isRecording = false; busy = false;
        releaseRecorder();
        ClipStore finished = output; output = null;
        if (finished != null) {
            if (success) {
                try {
                    finished.finish(uri -> {
                        lastSavedUri = uri;
                        main.post(() -> listener.saved(uri));
                    });
                } catch (IOException | RuntimeException e) {
                    finished.discard(); fail("Could not save video: " + e.getMessage());
                }
            } else finished.discard();
        }
        main.post(() -> listener.recording(false));
        if (restart && active && camera != null) {
            // Legacy HAL1-over-Camera2 wrappers on API 23 can enter an error state when a
            // session is reconfigured after MediaRecorder.stop(), and a renderer-backed
            // session can be closed while the GL thread is still detaching its encoder
            // surface. Both need a clean reopen rather than an immediate reconfigure.
            if (Build.VERSION.SDK_INT < 24 || lookRendering) {
                if (gl != null) gl.setEncoderSurface(null, 0L);
                closeDevice(camera); camera = null;
            } else {
                createPreview();
            }
        }
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try { recorder.reset(); } catch (RuntimeException ignored) { /* Already failed. */ }
            recorder.release(); recorder = null;
        }
        if (recordSurface != null) { recordSurface.release(); recordSurface = null; }
    }

    private void closeSession() {
        ++sessionSerial; previewReady = false;
        if (session != null) {
            try { session.stopRepeating(); session.abortCaptures(); }
            catch (CameraAccessException | RuntimeException ignored) { /* Device can already be closing. */ }
            session.close(); session = null;
        }
    }

    private void releaseCameraLease() {
        if (holdsCameraLease) { holdsCameraLease = false; CAMERA_LEASE.release(); }
    }

    private void closeDevice(CameraDevice device) {
        closingCamera = device;
        device.close();
    }

    private void closeCamera() {
        ++generation; leaseDeadline = 0;
        // Do not clear opening: an outstanding open must return and be closed
        // before the next resume/lens/size request may open another device.
        stopRecordingInternal(false);
        closeSession();
        if (camera != null) { closeDevice(camera); camera = null; }
        if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
        main.post(() -> listener.ready(false));
    }

    private void fail(String message) {
        Log.e(TAG, message);
        lastError = message;
        main.post(() -> listener.error(message));
    }

    static boolean contains(int[] values, int wanted) {
        if (values != null) for (int value : values) if (value == wanted) return true;
        return false;
    }
}
