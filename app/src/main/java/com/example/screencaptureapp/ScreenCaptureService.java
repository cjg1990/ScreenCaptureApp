package com.example.screencaptureapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenCaptureService extends Service {

    private static final String TAG = "ScreenCaptureService";
    private static final String CHANNEL_ID = "screen_capture_channel";
    private static final int NOTIFICATION_ID = 1001;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread handlerThread;
    private Handler handler;
    private ExecutorService executorService;

    private int screenDensity;
    private int screenWidth;
    private int screenHeight;

    private boolean isRecording = false;
    private int frameRate = 1; // 每秒1帧，可调整
    private long lastCaptureTime = 0;

    // 使用原子布尔值确保线程安全
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private final AtomicBoolean isStopping = new AtomicBoolean(false);

    private final IBinder binder = new LocalBinder();
    private NetworkManager networkManager;

    public class LocalBinder extends Binder {
        ScreenCaptureService getService() {
            return ScreenCaptureService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        //executorService = Executors.newSingleThreadExecutor();
        //networkManager = new NetworkManager(this);

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenDensity = metrics.densityDpi;
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        handlerThread = new HandlerThread("ScreenCapture");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = createNotification();

        // 处理不同Android版本的前台服务启动
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ 需要特殊处理
                startForeground(NOTIFICATION_ID, notification);
            } else {
                // Android 10-12
                startForeground(NOTIFICATION_ID, notification);
            }
        } else {
            // Android 9及以下
            startForeground(NOTIFICATION_ID, notification);
        }

        return START_STICKY;
    }

    public void startRecording(int resultCode, Intent data) {
        if (isRecording) return;
        // 修改：在开始录制时初始化线程池
        if (executorService == null || executorService.isShutdown() || executorService.isTerminated()) {
            executorService = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "ImageProcessor");
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
        }

        // 修改：在开始录制时初始化 NetworkManager
        if (networkManager == null) {
            networkManager = new NetworkManager(this);
        }

        MediaProjectionManager projectionManager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        mediaProjection = projectionManager.getMediaProjection(resultCode, data);
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null");
            return;
        }

        // 重置停止标志
        isStopping.set(false);

        // 关键修复：必须在创建 VirtualDisplay 之前注册回调
        mediaProjection.registerCallback(mediaProjectionCallback, handler);

        setupImageReader();
        createVirtualDisplay();

        //mediaProjection.registerCallback(mediaProjectionCallback, handler);
        isRecording = true;
        startImageCaptureLoop();

        //updateNotification("正在录制屏幕");
    }

    private void setupImageReader() {
        imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                android.graphics.PixelFormat.RGBA_8888,
                2
        );

        imageReader.setOnImageAvailableListener(imageAvailableListener, handler);
    }

    private void createVirtualDisplay() {
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                handler
        );
    }

    private final ImageReader.OnImageAvailableListener imageAvailableListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    // 检查是否正在停止
                    if (isStopping.get()) {
                        return;
                    }
                    Image image = reader.acquireLatestImage();
                    if (image != null) {
                        processImage(image);
                        image.close();
                    }
                }
            };

    private void processImage(Image image) {
        // 检查是否正在处理或正在停止
        if (isProcessing.get() || isStopping.get()) {
            return;
        }

        isProcessing.set(true);
        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;

            Bitmap bitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
            );

            bitmap.copyPixelsFromBuffer(buffer);

            // 裁剪掉填充部分
            Bitmap croppedBitmap = Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    screenWidth,
                    screenHeight
            );

            bitmap.recycle();

            // 发送到服务器
            sendImageToServer(croppedBitmap);
        } catch (Exception e)
        {
            Log.e(TAG, "Error processing image: " + e.getMessage());
        } finally {
            isProcessing.set(false);
        }
    }

    private void sendImageToServer(Bitmap bitmap) {
        // 修改：检查线程池是否已关闭
        if (executorService == null || executorService.isShutdown() || executorService.isTerminated()) {
            Log.w(TAG, "ExecutorService is not available, skipping image upload");
            bitmap.recycle();
            return;
        }

        // 修改：延迟初始化 networkManager
        if (networkManager == null) {
            networkManager = new NetworkManager(this);
        }
        try {
            executorService.execute(() -> {
                try {
                    String response = networkManager.uploadImage(bitmap);
                    // 发送反馈到悬浮窗
                    Intent intent = new Intent(this, FloatingWindowService.class);
                    intent.setAction(FloatingWindowService.ACTION_UPDATE_TEXT);
                    intent.putExtra(FloatingWindowService.EXTRA_TEXT, response);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent);
                    } else {
                        startService(intent);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error uploading image: " + e.getMessage());
                } finally {
                    // 确保回收位图
                    if (bitmap != null && !bitmap.isRecycled()) {
                        bitmap.recycle();
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error submitting task to executor: " + e.getMessage());
            // 如果提交失败，直接回收位图
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    private void startImageCaptureLoop() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isRecording) {
                    // 触发图像捕获
                    if (imageReader != null) {
                        // 通过虚拟显示更新来获取新帧
                        if (System.currentTimeMillis() - lastCaptureTime >= 1000 / frameRate) {
                            lastCaptureTime = System.currentTimeMillis();
                            handler.post(() -> {
                                // 强制更新虚拟显示
                                if (virtualDisplay != null) {
                                    // 这里实际是等待OnImageAvailableListener被调用
                                }
                            });
                        }
                    }
                    //handler.postDelayed(this, 1000 / frameRate);
                    // 继续循环
                    if (isRecording && !isStopping.get()) {
                        handler.postDelayed(this, 1000 / frameRate);
                    }
                }
            }
        }, 1000 / frameRate);
    }

    public void stopRecording() {
        if (!isRecording || isStopping.get()) {
            return;
        }
        isStopping.set(true);
        isRecording = false;

        // 移除所有未执行的回调
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }

        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
        // 修改：正确关闭线程池
        if (executorService != null) {
            try {
                // 停止接受新任务
                executorService.shutdown();

                // 等待现有任务完成
                if (!executorService.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    // 强制停止
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "Error shutting down executor: " + e.getMessage());
                executorService.shutdownNow();
            } finally {
                executorService = null;
            }
        }

        // 修改：清理 networkManager
        if (networkManager != null) {
            networkManager = null;
        }

        isStopping.set(false);
        //executorService.shutdown();
        //updateNotification("录制已停止");
    }

    private final MediaProjection.Callback mediaProjectionCallback =
            new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopRecording();
                }
            };

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "屏幕录制服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("后台屏幕录制服务正在运行");

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("屏幕录制服务")
                .setContentText("正在后台录制屏幕")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    /*private void updateNotification(String text) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("屏幕录制服务")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, notification);
    }*/

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public boolean isRecording() {
        return isRecording;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopRecording();
    }
}
