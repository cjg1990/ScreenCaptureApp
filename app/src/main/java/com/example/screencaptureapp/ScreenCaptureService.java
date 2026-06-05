package com.example.screencaptureapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
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
import java.util.concurrent.LinkedBlockingQueue;
import android.view.WindowManager;
import android.view.WindowMetrics;
import java.util.concurrent.Semaphore;

public class ScreenCaptureService extends Service {
    private static final String TAG = "ScreenCaptureService";
    private static final String CHANNEL_ID = "screen_capture_channel";
    private static final int NOTIFICATION_ID = 1001;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread handlerThread;
    private Handler handler;
    //private ExecutorService executorService;

    private int screenDensity;
    private int screenWidth;
    private int screenHeight;

    private volatile boolean isRecording = false;
    private int frameRate = 1; // 每秒1帧，可调整
    private long lastCaptureTime = 0;
    private static final long CAPTURE_INTERVAL_MS = 2000;
    //private LinkedBlockingQueue<Bitmap> frameQueue;
    private LinkedBlockingQueue<CaptureFrame> frameQueue;
    private Thread uploadThread;

    // 使用原子布尔值确保线程安全
    //private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private final AtomicBoolean isStopping = new AtomicBoolean(false);

    private final IBinder binder = new LocalBinder();
    private NetworkManager networkManager;

    // todo 1 start
    private Thread captureControlThread;
    private final Object captureLock = new Object();
    private volatile boolean requestCleanFrame = false;
    //private Bitmap latestCleanBitmap = null; // 暂存采集窗口期内的最新帧
    private CaptureFrame latestCleanFrame = null;
    // todo 1 end

    // 新增：用于缓存纯像素数据的复用数组，避免频繁创建对象
    // 2026/6/4
    private byte[] pixelDataBuffer;
    private int cachedPixelStride;
    private int cachedRowStride;
    private long latestFrameTimestamp;
    private volatile boolean hasNewFrame = false;
    // 2026/6/4
    // 2026/6/5
    private final Semaphore cycleSync = new Semaphore(1);
    // 2026/6/5

    // 2026/6/3 start
    public static class CaptureFrame {
        public final Bitmap bitmap;
        public final long captureTimeMs; // 截取该帧的具体时间戳(毫秒)

        public CaptureFrame(Bitmap bitmap, long captureTimeMs) {
            this.bitmap = bitmap;
            this.captureTimeMs = captureTimeMs;
        }
    }
    // 2026/6/3 end

    public class LocalBinder extends Binder {
        ScreenCaptureService getService() {
            return ScreenCaptureService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        // 2026/6/4
        /*DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenDensity = metrics.densityDpi;
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;*/
        updateScreenDimensions();
        // 2026/6/4

        handlerThread = new HandlerThread("ScreenCapture");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    /**
     * 核心修复方法：获取包含状态栏、导航栏、挖孔区的全屏真实物理分辨率
     */
    private void updateScreenDimensions() {
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        // 【新增校验】如果获取不到 WindowManager，直接抛出异常让 App 崩溃并停止运行
        if (windowManager == null) {
            throw new IllegalStateException("Critical Error: WindowManager is null. Cannot retrieve screen dimensions. Terminating application.");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 标准物理屏幕区域获取
            WindowMetrics windowMetrics = windowManager.getMaximumWindowMetrics();
            Rect bounds = windowMetrics.getBounds();
            screenWidth = bounds.width();
            screenHeight = bounds.height();
            screenDensity = getResources().getConfiguration().densityDpi;
        } else {
            // Android 11 以下利用反射/真实指标接口获取
            DisplayMetrics realMetrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(realMetrics);
            screenWidth = realMetrics.widthPixels;
            screenHeight = realMetrics.heightPixels;
            screenDensity = realMetrics.densityDpi;
        }
        Log.d(TAG, "更新全屏真实分辨率: " + screenWidth + "x" + screenHeight + ", 密度: " + screenDensity);
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
        /*if (executorService == null || executorService.isShutdown() || executorService.isTerminated()) {
            executorService = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "ImageProcessor");
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
        }*/

        // 2026/6/4
        updateScreenDimensions();
        // 2026/6/4

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

        isRecording = true;

        frameQueue = new LinkedBlockingQueue<>(3);
        startUploadConsumerThread();
        //startImageCaptureLoop();

        // todo 2 start
        startCaptureControlThread();
        // todo 2 end
    }

    // 2026/6/4
    /*private void startCaptureControlThread() {
        captureControlThread = new Thread(() -> {
            while(isRecording && !Thread.currentThread().isInterrupted()) {
                try {
                    synchronized (captureLock) {
                        requestCleanFrame = true;
                        //latestCleanBitmap = null;
                        // 2026/6/3 start
                        latestCleanFrame = null;
                        // 2026/6/3 end
                    }

                    sendBroadcast(new Intent(FloatingWindowService.ACTION_HIDE_WINDOW));

                    Thread.sleep(1000);

                    synchronized (captureLock) {
                        requestCleanFrame = false;
                        // 2026/6/3 start
                        //if(latestCleanBitmap != null) {
                       //     frameQueue.clear();
                       //     frameQueue.offer(latestCleanBitmap);
                        //}
                        if(latestCleanFrame != null) {
                            while(!frameQueue.isEmpty()) {
                                CaptureFrame oldFrame = frameQueue.poll();
                                if(oldFrame != null && oldFrame.bitmap != null && !oldFrame.bitmap.isRecycled()) {
                                    oldFrame.bitmap.recycle();
                                }
                            }
                            frameQueue.offer(latestCleanFrame);
                        }
                        // 2026/6/3 end
                    }

                    sendBroadcast(new Intent(FloatingWindowService.ACTION_SHOW_WINDOW));

                    Thread.sleep(1000);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "CaptureControlThread");
        captureControlThread.start();
    }*/
    // 2026/6/4
    private void startCaptureControlThread() {
        captureControlThread = new Thread(() -> {
            while(isRecording && !Thread.currentThread().isInterrupted()) {
                try {
                    //2026/6/5
                    cycleSync.acquire();
                    //2026/6/5

                    // --- 1. 开启采集窗口 ---
                    synchronized (captureLock) {
                        requestCleanFrame = true;
                        hasNewFrame = false; // 重置新帧标记
                    }

                    // 2026/6/5
                    //sendBroadcast(new Intent(FloatingWindowService.ACTION_HIDE_WINDOW));

                    // 悬浮窗隐藏并等待画面采集
                    //Thread.sleep(500);

                    // 【核心修改】：移除所有隐藏悬浮窗的代码
                    // 智能等待新帧到来，最多等待 500ms（10次 * 50ms），防止 ImageReader 卡死导致线程永远阻塞
                    int waitCount = 0;
                    while (!hasNewFrame && waitCount < 10) {
                        Thread.sleep(50);
                        waitCount++;
                    }
                    // 2026/6/5

                    // --- 2. 结束采集窗口，获取最终参数 ---
                    boolean shouldConvert = false;
                    long timestamp = 0;
                    int pStride = 0, rStride = 0;

                    synchronized (captureLock) {
                        requestCleanFrame = false;
                        if (hasNewFrame && pixelDataBuffer != null) {
                            shouldConvert = true;
                            timestamp = latestFrameTimestamp;
                            pStride = cachedPixelStride;
                            rStride = cachedRowStride;
                        }
                    }

                    // --- 3. 在锁外执行唯一一次耗时的 Bitmap 转换 ---
                    if (shouldConvert) {
                        Bitmap bitmap = buildBitmapFromBuffer(pixelDataBuffer, pStride, rStride);
                        if (bitmap != null) {
                            CaptureFrame newFrame = new CaptureFrame(bitmap, timestamp);

                            // 清理队列中的旧帧，防止内存泄漏
                            while(!frameQueue.isEmpty()) {
                                CaptureFrame oldFrame = frameQueue.poll();
                                if(oldFrame != null && oldFrame.bitmap != null && !oldFrame.bitmap.isRecycled()) {
                                    oldFrame.bitmap.recycle();
                                }
                            }
                            frameQueue.offer(newFrame);
                        }
                        else
                        {
                            //2026/6/5
                            cycleSync.release();
                            //2026/6/5
                        }
                    }
                    else {
                        //2026/6/5
                        cycleSync.release();
                        //2026/6/5
                    }

                    // 2026/6/5
                    // --- 4. 恢复悬浮窗并进入休息期 ---
                    //sendBroadcast(new Intent(FloatingWindowService.ACTION_SHOW_WINDOW));
                    //Thread.sleep(500);
                    // 2026/6/5
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "CaptureControlThread");
        captureControlThread.start();
    }
    // 2026/6/4

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
    // 2026/6/4
    /*private final ImageReader.OnImageAvailableListener imageAvailableListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    // 检查是否正在停止
                    if (isStopping.get()) {
                        return;
                    }
                    Image image = reader.acquireLatestImage();
                    if(image != null)
                    {
                        long captureTimeMs = System.currentTimeMillis();

                        synchronized (captureLock) {
                            if(requestCleanFrame) {
                                Bitmap bitmap = convertImageToBitmap(image);
                                if(bitmap != null) {
                                    //if(latestCleanBitmap != null && !latestCleanBitmap.isRecycled())
                                    //{
                                    //    latestCleanBitmap.recycle();
                                    //}
                                    //latestCleanBitmap = bitmap;
                                    if(latestCleanFrame != null && latestCleanFrame.bitmap != null && !latestCleanFrame.bitmap.isRecycled())
                                    {
                                        latestCleanFrame.bitmap.recycle();
                                    }
                                    latestCleanFrame = new CaptureFrame(bitmap, captureTimeMs);
                                }
                            }
                        }
                        image.close();
                    }
                }
            };*/
    // 2026/6/4
    private final ImageReader.OnImageAvailableListener imageAvailableListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    if (isStopping.get()) return;

                    Image image = reader.acquireLatestImage();
                    if (image != null) {
                        synchronized (captureLock) {
                            if (requestCleanFrame) {
                                try {
                                    Image.Plane[] planes = image.getPlanes();
                                    ByteBuffer buffer = planes[0].getBuffer();
                                    int remaining = buffer.remaining();

                                    // 如果数组未初始化或容量变化，才重新分配
                                    if (pixelDataBuffer == null || pixelDataBuffer.length != remaining) {
                                        pixelDataBuffer = new byte[remaining];
                                    }

                                    buffer.position(0);
                                    buffer.get(pixelDataBuffer); // 毫秒级的极速拷贝

                                    cachedPixelStride = planes[0].getPixelStride();
                                    cachedRowStride = planes[0].getRowStride();
                                    latestFrameTimestamp = System.currentTimeMillis();
                                    hasNewFrame = true;
                                } catch (Exception e) {
                                    Log.e(TAG, "Error copying pixels: " + e.getMessage());
                                }
                            }
                        }
                        // 无论是否采纳，必须极速释放 Image，防止底层阻塞
                        image.close();
                    }
                }
            };
    //2026/6/4
    private Bitmap buildBitmapFromBuffer(byte[] pixels, int pixelStride, int rowStride) {
        try {
            int rowPadding = rowStride - pixelStride * screenWidth;
            Bitmap bitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
            );
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));

            Bitmap croppedBitmap = Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    screenWidth,
                    screenHeight
            );
            bitmap.recycle();
            return croppedBitmap;
        } catch (Exception e) {
            Log.e(TAG, "Error building bitmap: " + e.getMessage());
            return null;
        }
    }
    //2026/6/4

    // todo 5 start
    private Bitmap convertImageToBitmap(Image image) {
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

            return croppedBitmap;
        } catch (Exception e)
        {
            Log.e(TAG, "Error convertImageToBitmap: " + e.getMessage());
        }
        return null;
    }
    // todo 5 end

    /*private void processAndQueueImage(Image image) {
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

            if(!frameQueue.offer(croppedBitmap)) {
                croppedBitmap.recycle();
                Log.d(TAG, "Network is slow, queue is full, dropping frame");
            }
        } catch (Exception e)
        {
            Log.e(TAG, "Error processing image: " + e.getMessage());
        }
    }*/
    /*private void processImage(Image image) {
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
    }*/

    private void startUploadConsumerThread() {
        uploadThread = new Thread(() -> {
            Log.e(TAG, "Upload Thread is running..");
            while(isRecording && !Thread.currentThread().isInterrupted())
            {
                try{
                    //Bitmap bitmap = frameQueue.take();
                    CaptureFrame frame = frameQueue.take();

                    if(frame != null && frame.bitmap != null)
                    {
                        Bitmap bitmap = frame.bitmap;
                        long timestamp = frame.captureTimeMs; // 获取截取这帧时的精准时间戳

                        Log.e(TAG, "now send image..");
                        try {
                            String response = networkManager.uploadImage(bitmap,timestamp);
                            updateFloatingWindow(response);
                        } catch (Exception e)
                        {
                            Log.e(TAG, "Error uploading image: " + e.getMessage());
                            updateFloatingWindow("上传失败: " + e.getMessage());
                        } finally {
                            if(!bitmap.isRecycled())
                            {
                                bitmap.recycle();
                            }

                            //2026/6/5
                            Thread.sleep(1000);
                            cycleSync.release();
                            //2026/6/5
                        }
                    }
                } catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
                catch (Throwable t) {
                    // 【核心修改】捕获 Throwable 而不是 Exception！
                    // 如果是缺依赖、OOM 等致命 Error，这里会立刻打印出来！
                    Log.e(TAG, "!!!! FATAL ERROR, Upload Thread !!!!", t);
                    //2026/6/5
                    cycleSync.release();
                    //2026/6/5
                }
            }
            Log.e(TAG, "==== upload thread terminate ====");
        }, "NetworkUploadThread");
        uploadThread.start();
    }

    private void updateFloatingWindow(String text) {
        Intent intent = new Intent(FloatingWindowService.ACTION_UPDATE_TEXT);
        intent.putExtra(FloatingWindowService.EXTRA_TEXT, text);
        sendBroadcast(intent);
    }

    /*private void sendImageToServer(Bitmap bitmap) {
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
    }*/

    /*private void startImageCaptureLoop() {
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
                    // 继续循环
                    if (isRecording && !isStopping.get()) {
                        handler.postDelayed(this, 1000 / frameRate);
                    }
                }
            }
        }, 1000 / frameRate);
    }*/

    public void stopRecording() {
        if (!isRecording || isStopping.get()) {
            return;
        }
        isStopping.set(true);
        isRecording = false;

        updateFloatingWindow("录制已停止，等待开始...");

        if(captureControlThread != null) {
            captureControlThread.interrupt();
            captureControlThread = null;
        }
        // 停止消费者线程
        if (uploadThread != null) {
            uploadThread.interrupt();
            uploadThread = null;
        }

        /*if(latestCleanBitmap != null && !latestCleanBitmap.isRecycled())
        {
            latestCleanBitmap.recycle();
            latestCleanBitmap = null;
        }*/
        // 【修改】清理 latestCleanFrame
        if(latestCleanFrame != null && latestCleanFrame.bitmap != null && !latestCleanFrame.bitmap.isRecycled())
        {
            latestCleanFrame.bitmap.recycle();
            latestCleanFrame = null;
        }
        requestCleanFrame = false;

        // 清空队列，释放未处理的 Bitmap 防止内存泄漏
        /*if (frameQueue != null) {
            while (!frameQueue.isEmpty()) {
                Bitmap b = frameQueue.poll();
                if (b != null && !b.isRecycled()) b.recycle();
            }
            frameQueue.clear();
        }*/
        // 清空队列，释放未处理的 Bitmap 防止内存泄漏
        if (frameQueue != null) {
            while (!frameQueue.isEmpty()) {
                CaptureFrame f = frameQueue.poll();
                if (f != null && f.bitmap != null && !f.bitmap.isRecycled()) {
                    f.bitmap.recycle();
                }
            }
            frameQueue.clear();
        }

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
        /*if (executorService != null) {
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
        }*/

        // 修改：清理 networkManager
        if (networkManager != null) {
            networkManager = null;
        }
        // 2026/6/4
        pixelDataBuffer = null;
        // 2026/6/4
        isStopping.set(false);
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
