package com.example.screencaptureapp;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import okhttp3.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class NetworkManager {
    private static final String TAG = "NetworkManager";
    private static final String UPLOAD_URL = "http://ddbaby.site/lyxz/gto_decision";
    private static final int MAX_FILE_COUNT = 50;

    private OkHttpClient client;
    private Context context;
    private String uuid;

    private ByteArrayOutputStream reusableStream = new ByteArrayOutputStream();

    public NetworkManager(Context context) {
        this.context = context;
        this.client = createHttpClient();
        this.uuid = UUIDManager.get(context);
    }

    private OkHttpClient createHttpClient() {
        // 创建简单的 HTTP 客户端，不需要 SSL 配置
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public String uploadImage(Bitmap bitmap, long timestamp) throws IOException {
        //saveToPublicPictures(bitmap);

        /*ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(CompressFormat.JPEG, 50, stream);
        byte[] byteArray = stream.toByteArray();
        String base64Image = android.util.Base64.encodeToString(byteArray, android.util.Base64.DEFAULT);*/
        // 2026/6/4
        reusableStream.reset();
        bitmap.compress(CompressFormat.JPEG, 50, reusableStream);
        byte[] byteArray = reusableStream.toByteArray();
        String base64Image = android.util.Base64.encodeToString(byteArray, android.util.Base64.DEFAULT);
        // 2026/6/4

        Gson gson = new Gson();
        JsonArray imagesArray = new JsonArray();
        imagesArray.add(base64Image);

        JsonObject jsonBody = new JsonObject();
        jsonBody.add("images", imagesArray);
        jsonBody.addProperty("timestamp", timestamp); // 添加当前时间戳参数
        jsonBody.addProperty("uuid", uuid);
        String jsonString = gson.toJson(jsonBody);
        // 创建JSON类型的请求体
        MediaType JSON = MediaType.parse("application/json; charset=utf-8");
        RequestBody requestBody = RequestBody.create(JSON, jsonString);

        Request request = new Request.Builder()
                .url(UPLOAD_URL)
                .post(requestBody)
                .addHeader("User-Agent", "ScreenCaptureApp/1.0")
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return response.body().string();
            } else {
                throw new IOException("Server error: " + response.code());
            }
        }
    }

    private void saveToPublicPictures(Bitmap bitmap) {
        String fileName = "DEBUG_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(new Date()) + ".jpg";

        // 自动清理旧文件
        cleanOldPublicImages();

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");

        // 指定子目录: Pictures/ScreenCaptureDebug
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ScreenCaptureDebug");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri imageUri = resolver.insert(collection, values);

        if (imageUri != null) {
            try (OutputStream os = resolver.openOutputStream(imageUri)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, os);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    resolver.update(imageUri, values, null, null);
                }
                Log.i(TAG, "screen capture success save : " + fileName +", to public store!!!");
            } catch (Exception e) {
                Log.e(TAG, "screen capture save to public store failed", e);
                resolver.delete(imageUri, null, null);
            }
        }
    }

    /**
     * 清理逻辑：删除 MediaStore 中属于本应用的旧调试图
     */
    private void cleanOldPublicImages() {
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

            // 匹配文件名开头为 DEBUG_ 的图片
            String selection = MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?";
            String[] selectionArgs = new String[]{"DEBUG_%"};
            String sortOrder = MediaStore.Images.Media.DATE_ADDED + " ASC";

            try (Cursor cursor = resolver.query(collection, new String[]{MediaStore.Images.Media._ID}, selection, selectionArgs, sortOrder)) {
                if (cursor != null && cursor.getCount() > MAX_FILE_COUNT) {
                    int deleteCount = cursor.getCount() - MAX_FILE_COUNT;
                    int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);

                    for (int i = 0; i < deleteCount && cursor.moveToNext(); i++) {
                        long id = cursor.getLong(idColumn);
                        Uri deleteUri = Uri.withAppendedPath(collection, String.valueOf(id));
                        resolver.delete(deleteUri, null, null);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "screen capture clean public store failed", e);
        }
    }

    private void saveBmpToLocalDebugDir(Bitmap bitmap) {
        try {
            File debugDir = new File(context.getExternalFilesDir(null),"debug_images");
            if(!debugDir.exists() && !debugDir.mkdirs())
            {
                Log.e(TAG, "cannot create debug dir for screen capture app...");
                return;
            }

            cleanOldFiles(debugDir);

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(new Date());
            File file = new File(debugDir, "SCREEN_" + timeStamp + ".jpg");

            try (FileOutputStream fos = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, fos);
                Log.i(TAG, "screen capture success save image: " + file.getName());
            }
        } catch (Exception e)
        {
            Log.e(TAG, "screen capture save image failed...");
        }
    }

    private void cleanOldFiles(File dir) {
        File[] files = dir.listFiles();
        if (files != null && files.length > MAX_FILE_COUNT) {
            // 按修改时间排序（从旧到新）
            Arrays.sort(files, (f1, f2) -> Long.compare(f1.lastModified(), f2.lastModified()));

            int deleteCount = files.length - MAX_FILE_COUNT;
            for (int i = 0; i < deleteCount; i++) {
                if (files[i].delete()) {
                    Log.d(TAG, "screen capture success clean image file: " + files[i].getName());
                }
            }
        }
    }
}
