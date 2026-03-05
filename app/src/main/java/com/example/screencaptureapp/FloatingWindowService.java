package com.example.screencaptureapp;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.annotation.Nullable;

public class FloatingWindowService extends Service {

    public static final String ACTION_UPDATE_TEXT = "UPDATE_TEXT";
    public static final String EXTRA_TEXT = "text";

    private WindowManager windowManager;
    private View floatingView;
    private TextView feedbackText;
    private Handler handler;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        handler = new Handler(getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_UPDATE_TEXT.equals(action)) {
                String text = intent.getStringExtra(EXTRA_TEXT);
                if (text != null) {
                    updateFeedbackText(text);
                }
            }
        }

        if (floatingView == null) {
            createFloatingWindow();
        }

        return START_STICKY;
    }

    private void createFloatingWindow() {
        LayoutInflater inflater = LayoutInflater.from(this);
        floatingView = inflater.inflate(R.layout.floating_window, null);

        feedbackText = floatingView.findViewById(R.id.tv_feedback);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 20;
        params.y = 100;

        try {
            windowManager.addView(floatingView, params);
        } catch (Exception e) {
            e.printStackTrace();
        }

        floatingView.findViewById(R.id.btn_close).setOnClickListener(v -> stopSelf());
    }

    private void updateFeedbackText(final String text) {
        handler.post(() -> {
            if (feedbackText != null) {
                feedbackText.setText("反馈: " + text);
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingView != null && windowManager != null) {
            windowManager.removeView(floatingView);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
