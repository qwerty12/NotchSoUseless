package pk.q12.notchsouseless;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Method;
import java.util.List;

@SuppressLint("AccessibilityPolicy")
public final class NotchTouchAccessibilityService extends AccessibilityService {
    private static final String TAG = "NotchTouchAccSvc";

    private View overlayView;

    private DisplayManager displayManager;
    private WindowManager windowManager;
    private GestureDetector gestureDetector;
    private AudioManager audioManager;
    private CameraManager cameraManager;
    private Vibrator vibrator;

    private Display defaultDisplay;
    private int lastRotation;

    private Method setTorchMode;
    private boolean torchAvailable;
    private String cameraId;
    private boolean torchOn;
    private boolean longPressTriggered;
    private int delayMillisTorchOn;
    private int delayMillisTorchOff;
    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(final String cameraId, final boolean enabled) {
            if (cameraId.equals(NotchTouchAccessibilityService.this.cameraId))
                NotchTouchAccessibilityService.this.torchOn = enabled;
        }
    };
    private final Runnable longPressRunnable = () -> {
        longPressTriggered = true;
        if (!torchAvailable)
            return;

        try {
            final boolean newTorchState = !torchOn;
            if (setTorchMode != null)
                setTorchMode.invoke(cameraManager, cameraId, newTorchState, 5);
            else
                cameraManager.setTorchMode(cameraId, newTorchState);
            if (newTorchState)
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK));
        } catch (final Throwable e) {
            Log.e(TAG, "Torch toggle failed: " + e.getMessage(), e);
        }
    };
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayChanged(final int displayId) {
            if (displayId != Display.DEFAULT_DISPLAY)
                return;

            final int rotation = defaultDisplay.getRotation();
            if (rotation == lastRotation)
                return;

            if (updateOverlayBounds())
                lastRotation = rotation;
        }

        @Override
        public void onDisplayAdded(final int id) {}
        @Override
        public void onDisplayRemoved(final int id) {}
    };

    @Override
    protected void attachBaseContext(final Context newBase) {
        super.attachBaseContext(newBase);
        HiddenApiBypass.addHiddenApiExemptions("Landroid/hardware/camera2/CameraManager;");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        torchAvailable = initCameraStuff();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        setupOverlay();
    }

    private boolean initCameraStuff() {
        final int longPressTimeout = ViewConfiguration.getLongPressTimeout();
        delayMillisTorchOn = Math.max(longPressTimeout, 1000);
        delayMillisTorchOff = Math.max(ViewConfiguration.getDoubleTapTimeout() + 100, Math.min(longPressTimeout, 500));

        if (setTorchMode == null) {
            try {
                // https://github.com/zacharee/SamsungFlashlight
                setTorchMode = CameraManager.class.getDeclaredMethod(Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 ? "setTorchMode" : "semSetTorchMode", String.class, boolean.class, int.class);
            } catch (final NoSuchMethodException e) {
                /*Log.e(TAG, e.getMessage(), e);
                return false;*/
            }
        }

        if (cameraManager == null) {
            final CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (cm == null)
                return false;

            cameraId = null;
            try {
                for (final String id : cm.getCameraIdList()) {
                    if (Boolean.TRUE.equals(cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) {
                        cameraId = id;
                        break;
                    }
                }
            } catch (final CameraAccessException e) {
                return false;
            }
            if (cameraId == null)
                return false;

            cm.registerTorchCallback(torchCallback, null);
            cameraManager = cm;
        }

        if (vibrator == null)
            vibrator = ((VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE)).getDefaultVibrator();

        return true;
    }

    @Override
    public boolean onUnbind(final Intent intent) {
        removeOverlay();
        if (cameraManager != null) {
            cameraManager.unregisterTorchCallback(torchCallback);
            cameraManager = null;
        }
        return super.onUnbind(intent);
    }

    private boolean updateOverlayBounds() {
        if (overlayView == null)
            return true;

        try {
            final Rect cutoutBounds = getNotchBounds();
            final int cutoutBoundsWidth = cutoutBounds.width();
            final int cutoutBoundsHeight = cutoutBounds.height();

            final WindowManager.LayoutParams params = (WindowManager.LayoutParams) overlayView.getLayoutParams();
            if (params.width == cutoutBoundsWidth && params.height == cutoutBoundsHeight &&
                    params.x == cutoutBounds.left && params.y == cutoutBounds.top)
                return true;

            params.width = cutoutBoundsWidth;
            params.height = cutoutBoundsHeight;
            params.x = cutoutBounds.left;
            params.y = cutoutBounds.top;

            windowManager.updateViewLayout(overlayView, params);
            return true;
        } catch (final Throwable e) {
            Log.e(TAG, "Failed to update overlay bounds: " + e.getMessage(), e);
            return false;
        }
    }

    private Rect getNotchBounds() {
        final WindowMetrics metrics = windowManager.getCurrentWindowMetrics();
        final WindowInsets insets = metrics.getWindowInsets();
        final DisplayCutout cutout = insets.getDisplayCutout();
        if (cutout == null)
            return null;

        final List<Rect> boundingRects = cutout.getBoundingRects();
        return !boundingRects.isEmpty() ? boundingRects.get(0) : null;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupOverlay() {
        if (overlayView != null)
            return;

        final Rect cutoutBounds = getNotchBounds();
        if (cutoutBounds == null)
            return;
        lastRotation = defaultDisplay.getRotation();

        final View touchView = new View(this);
        if (!BuildConfig.DEBUG) {
            overlayView = touchView;
        } else {
            overlayView = new FrameLayout(this);
        }

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                cutoutBounds.width(),
                cutoutBounds.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );

        params.x = cutoutBounds.left;
        params.y = cutoutBounds.top;
        params.gravity = Gravity.TOP | Gravity.START;
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(final MotionEvent e) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_SAME,
                        AudioManager.FLAG_SHOW_UI);
                return true;
            }

            @Override
            public boolean onDoubleTap(final MotionEvent e) {
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
                return true;
            }
        });
        gestureDetector.setIsLongpressEnabled(false);

        touchView.setOnTouchListener((v, ev) -> {
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    longPressTriggered = false;
                    v.removeCallbacks(longPressRunnable);
                    v.postDelayed(longPressRunnable, torchOn ? delayMillisTorchOff : delayMillisTorchOn);
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.removeCallbacks(longPressRunnable);
                    if (longPressTriggered)
                        return true;
                    break;
            }

            gestureDetector.onTouchEvent(ev);
            return true;
        });

        if (BuildConfig.DEBUG) {
            final View debugRectView = new View(this) {
                @Override
                protected void onDraw(final Canvas canvas) {
                    super.onDraw(canvas);
                    final Paint paint = new Paint();
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(0x55FF0000);
                    canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
                }
            };

            debugRectView.setOnTouchListener((v, ev) -> {
                Log.d(TAG, "Touch at " + ev.getX() + "," + ev.getY());
                return false;
            });

            ((FrameLayout) overlayView).addView(debugRectView,
                    new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT));

            ((FrameLayout) overlayView).addView(touchView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }

        try {
            windowManager.addView(overlayView, params);
        } catch (final Throwable e) {
            Log.e(TAG, "Failed to add overlay: " + e.getMessage(), e);
            overlayView = null;
            return;
        }

        displayManager.registerDisplayListener(displayListener, null);
    }

    private void removeOverlay() {
        if (overlayView != null) {
            try {
                displayManager.unregisterDisplayListener(displayListener);
            } catch (final Throwable ignored) {}
            try {
                windowManager.removeView(overlayView);
            } catch (final Throwable ignored) {}
            overlayView = null;
        }
    }

    @Override
    public void onAccessibilityEvent(final AccessibilityEvent event) {}
    @Override
    public void onInterrupt() {}
}