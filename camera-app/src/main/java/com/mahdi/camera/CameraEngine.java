package com.mahdi.camera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
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
    private int leaseAttempts;

    CameraEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
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
            if (++leaseAttempts <= 30) worker.postDelayed(this::openCamera, 100);
            else { active = false; fail("Camera is still closing. Tap the preview to retry."); }
            return;
        }
        holdsCameraLease = true; leaseAttempts = 0;
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
                        openCamera();
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
            final int serial = ++sessionSerial;
            camera.createCaptureSession(Collections.singletonList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession configured) {
                            if (!active || camera == null || serial != sessionSerial) { configured.close(); return; }
                            session = configured;
                            try {
                                repeat(false); previewReady = true; lastError = "";
                                Log.i(TAG, "Preview ready generation " + generation);
                                main.post(() -> listener.ready(true));
                            } catch (CameraAccessException | RuntimeException e) {
                                configured.close(); fail("Preview failed: " + e.getMessage());
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession configured) {
                            configured.close();
                            if (serial == sessionSerial) fail("Preview configuration failed. Try another lens or resolution.");
                        }
                    }, worker);
        } catch (CameraAccessException | RuntimeException e) { fail("Preview unavailable: " + e.getMessage()); }
    }

    private void repeat(boolean recording) throws CameraAccessException {
        if (camera == null || session == null || previewSurface == null) return;
        CaptureRequest.Builder request = camera.createCaptureRequest(
                recording ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
        request.addTarget(previewSurface);
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
            recorder.setOrientationHint(CameraMath.rotation(caps.sensorOrientation, displayDegrees, caps.front));
            recorder.setOnErrorListener((r, what, extra) -> worker.post(() -> {
                stopRecordingInternal(true); fail("Recorder error " + what + ". Check storage space.");
            }));
            recorder.prepare();
            recordSurface = recorder.getSurface();
            closeSession();
            final int serial = ++sessionSerial;
            camera.createCaptureSession(Arrays.asList(previewSurface, recordSurface),
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
                    }, worker);
        } catch (CameraAccessException | IOException | RuntimeException e) { recordingFailed(e); }
    }

    void stopRecording() { worker.post(() -> stopRecordingInternal(true)); }

    private void recordingFailed(Exception e) {
        closeSession(); releaseRecorder();
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
        if (restart && active) createPreview();
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
        ++generation; leaseAttempts = 0;
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
