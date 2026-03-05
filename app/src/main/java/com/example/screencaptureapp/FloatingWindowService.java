package com.example.screencaptureapp;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import androidx.core.app.NotificationCompat;

public class FloatingWindowService extends Service {

    public static final String ACTION_UPDATE_TEXT = "UPDATE_TEXT";
    public static final String EXTRA_TEXT = "text";

    private static final String TAG = "FloatingWindowService";
    private static final String CHANNEL_ID = "floating_window_channel";
    private static final int NOTIFICATION_ID = 1002;

    private WindowManager windowManager;
    private View floatingView;
    private TextView feedbackText;
    private Handler handler;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        handler = new Handler(getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand");

        // 关键：立即启动前台服务
        Notification notification = createNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, notification);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

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
            Log.d(TAG, "Floating window created");
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "Error creating floating window: " + e.getMessage());
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

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "悬浮窗服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("显示屏幕录制反馈");

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("屏幕录制反馈")
                .setContentText("显示录制状态反馈")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
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
