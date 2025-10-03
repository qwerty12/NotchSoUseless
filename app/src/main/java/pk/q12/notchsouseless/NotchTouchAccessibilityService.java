package pk.q12.notchsouseless;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
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

@SuppressLint("AccessibilityPolicy")
public final class NotchTouchAccessibilityService extends AccessibilityService {
    private static final String TAG = "NotchTouchAccSvc";

    private View overlayView;

    private WindowManager windowManager;
    private GestureDetector gestureDetector;
    private AudioManager audioManager;
    private CameraManager cameraManager;
    private Vibrator vibrator;

    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String intentAction = intent.getAction();
            if (intentAction == null || overlayView == null)
                return;

            switch (intentAction) {
                case Intent.ACTION_CONFIGURATION_CHANGED:
                    updateOverlayBounds();
                    break;
                case Intent.ACTION_SCREEN_ON:
                    if (overlayView.getWindowToken() == null)
                        windowManager.addView(overlayView, overlayView.getLayoutParams());
                    break;
                case Intent.ACTION_SCREEN_OFF:
                    windowManager.removeViewImmediate(overlayView);
                    break;
            }
        }
    };

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
        } catch (final Exception e) {
            Log.e(TAG, "Torch toggle failed: " + e.getMessage(), e);
        }
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
            } catch (final NoSuchMethodException ignored) {
                /*Log.e(TAG, ignored.getMessage(), ignored);
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

    private void updateOverlayBounds() {
        try {
            final Rect cutoutBounds = getNotchBounds();
            final int cutoutBoundsWidth = cutoutBounds.width();
            final int cutoutBoundsHeight = cutoutBounds.height();

            final WindowManager.LayoutParams params = (WindowManager.LayoutParams) overlayView.getLayoutParams();
            if (params.width == cutoutBoundsWidth && params.height == cutoutBoundsHeight &&
                    params.x == cutoutBounds.left && params.y == cutoutBounds.top)
                return;

            params.width = cutoutBoundsWidth;
            params.height = cutoutBoundsHeight;
            params.x = cutoutBounds.left;
            params.y = cutoutBounds.top;

            windowManager.updateViewLayout(overlayView, params);
        } catch (final Exception e) {
            Log.e(TAG, "Failed to update overlay bounds: " + e.getMessage(), e);
        }
    }

    private Rect getNotchBounds() {
        final WindowMetrics metrics = windowManager.getCurrentWindowMetrics();
        final WindowInsets insets = metrics.getWindowInsets();
        final DisplayCutout cutout = insets.getDisplayCutout();

        return cutout != null ? cutout.getBoundingRects().get(0) : null;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupOverlay() {
        if (overlayView != null)
            return;

        final Rect cutoutBounds = getNotchBounds();
        if (cutoutBounds == null)
            return;

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
                    if (BuildConfig.DEBUG) Log.d(TAG, "Touch at " + ev.getX() + "," + ev.getY());
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

            ((FrameLayout) overlayView).addView(debugRectView,
                    new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT));

            ((FrameLayout) overlayView).addView(touchView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }

        try {
            windowManager.addView(overlayView, params);
        } catch (final Exception e) {
            Log.e(TAG, "Failed to add overlay: " + e.getMessage(), e);
            overlayView = null;
            return;
        }

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(broadcastReceiver, intentFilter);
    }

    private void removeOverlay() {
        if (overlayView != null) {
            try {
                unregisterReceiver(broadcastReceiver);
            } catch (final Exception ignored) {}
            try {
                windowManager.removeView(overlayView);
            } catch (final Exception ignored) {}
            overlayView = null;
        }
    }

    @Override
    public void onAccessibilityEvent(final AccessibilityEvent event) {}
    @Override
    public void onInterrupt() {}
}