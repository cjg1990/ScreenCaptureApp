package com.example.screencaptureapp;

import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.screencaptureapp.utils.PermissionHelper;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CODE_SCREEN_CAPTURE = 1001;
    private static final int REQUEST_OVERLAY_PERMISSION = 1002;
    private static final int REQUEST_PERMISSIONS = 1003;
    private static final int REQUEST_MEDIA_PROJECTION_PERMISSION = 1004;  // 新增

    private MediaProjectionManager mediaProjectionManager;
    private Button startButton, stopButton;
    private TextView statusText;
    private ScreenCaptureService screenCaptureService;
    private boolean isServiceBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ScreenCaptureService.LocalBinder binder = (ScreenCaptureService.LocalBinder) service;
            screenCaptureService = binder.getService();
            isServiceBound = true;
            updateUI();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isServiceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        /*ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });*/

        initViews();
        checkPermissions();

        mediaProjectionManager = (MediaProjectionManager)
                getSystemService(MEDIA_PROJECTION_SERVICE);

        updateUI();
    }

    private void initViews() {
        startButton = findViewById(R.id.btn_start);
        stopButton = findViewById(R.id.btn_stop);
        statusText = findViewById(R.id.tv_status);

        startButton.setOnClickListener(v -> {
            // 先更新UI状态
            startButton.setEnabled(false);
            startButton.setText("启动中...");
            // 检查并请求媒体投影权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION)
                        != PackageManager.PERMISSION_GRANTED) {

                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION},
                            REQUEST_MEDIA_PROJECTION_PERMISSION);
                } else {
                    startScreenCapture();
                }
            } else {
                startScreenCapture();
            }
        });
        stopButton.setOnClickListener(v -> {
            stopButton.setEnabled(false);
            stopButton.setText("停止中...");
            stopScreenCapture();
        });
    }

    private void checkPermissions() {
        String[] permissions = {
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE
        };

        if (!PermissionHelper.hasPermissions(this, permissions)) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS);
        }

        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
            }
        }
    }

    private void startScreenCapture() {
        /*if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForegroundService(new Intent(this, ScreenCaptureService.class));
        } else {
            startService(new Intent(this, ScreenCaptureService.class));
        }
        bindService(new Intent(this, ScreenCaptureService.class),
                serviceConnection, BIND_AUTO_CREATE);

        Intent captureIntent = mediaProjectionManager.createScreenCaptureIntent();
        startActivityForResult(captureIntent, REQUEST_CODE_SCREEN_CAPTURE);*/
        // 检查是否已授予媒体投影权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION)
                    != PackageManager.PERMISSION_GRANTED) {

                Toast.makeText(this, "需要媒体投影权限", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        Intent intent = new Intent(this, ScreenCaptureService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        bindService(new Intent(this, ScreenCaptureService.class),
                serviceConnection, BIND_AUTO_CREATE);

        Intent captureIntent = mediaProjectionManager.createScreenCaptureIntent();
        startActivityForResult(captureIntent, REQUEST_CODE_SCREEN_CAPTURE);
    }

    private void stopScreenCapture() {
        if (isServiceBound && screenCaptureService != null) {
            screenCaptureService.stopRecording();
            unbindService(serviceConnection);
            isServiceBound = false;
        }
        stopService(new Intent(this, ScreenCaptureService.class));
        updateUI();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && isServiceBound) {
                screenCaptureService.startRecording(resultCode, data);
                statusText.setText("录制中...");
                updateUI();
            } else {
                Toast.makeText(this, "屏幕录制权限被拒绝", Toast.LENGTH_SHORT).show();
                stopScreenCapture();
            }
        } else if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "悬浮窗权限已获取", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                Toast.makeText(this, "所有权限已获取", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_MEDIA_PROJECTION_PERMISSION) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "媒体投影权限已授予", Toast.LENGTH_SHORT).show();
                startScreenCapture();
            } else {
                Toast.makeText(this, "需要媒体投影权限才能继续", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void updateUI() {
        // 确保在主线程更新UI
        runOnUiThread(() -> {
            boolean isRecording = false;

            // 检查服务是否已绑定且正在录制
            if (isServiceBound && screenCaptureService != null) {
                isRecording = screenCaptureService.isRecording();
            }

            // 重置按钮文本
            startButton.setText("开始录制");
            stopButton.setText("停止录制");

            startButton.setEnabled(!isRecording);
            stopButton.setEnabled(isRecording);
            statusText.setText(isRecording ? "正在录制和传输" : "准备就绪");
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isServiceBound) {
            unbindService(serviceConnection);
        }
    }
}