package com.example.screencaptureapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.util.UUID;

public class UUIDManager {
    private static final String TAG = "UUIDManager";
    private static final String PREFS_FILE = "app_uuid_preferences";
    private static final String UUID_KEY = "persistent_uuid";

    private static UUIDManager instance;
    private final Context context;
    private String cachedUUID = null;

    // 私有构造函数
    private UUIDManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 获取单例实例
     */
    public static synchronized UUIDManager getInstance(Context context) {
        if (instance == null) {
            instance = new UUIDManager(context);
        }
        return instance;
    }

    /**
     * 获取或生成持久化的 UUID
     *
     * @return 持久化的 UUID 字符串
     */
    public String getUUID() {
        if (cachedUUID != null) {
            return cachedUUID;
        }

        String uuid = loadUUIDFromStorage();
        if (uuid == null) {
            uuid = generateAndSaveUUID();
        }

        cachedUUID = uuid;
        Log.d(TAG, "获取 UUID: " + uuid);
        return uuid;
    }

    /**
     * 从 SharedPreferences 加载 UUID
     */
    private String loadUUIDFromStorage() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(
                    PREFS_FILE, Context.MODE_PRIVATE
            );
            String uuid = prefs.getString(UUID_KEY, null);

            if (uuid != null && isValidUUID(uuid)) {
                Log.d(TAG, "从存储加载现有 UUID");
                return uuid;
            }
        } catch (Exception e) {
            Log.e(TAG, "加载 UUID 失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 生成新的 UUID 并保存
     */
    private String generateAndSaveUUID() {
        String newUUID = UUID.randomUUID().toString();

        try {
            SharedPreferences prefs = context.getSharedPreferences(
                    PREFS_FILE, Context.MODE_PRIVATE
            );
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(UUID_KEY, newUUID);

            // 使用 apply() 异步保存，不阻塞主线程
            editor.apply();

            Log.d(TAG, "生成并保存新 UUID: " + newUUID);
        } catch (Exception e) {
            Log.e(TAG, "保存 UUID 失败: " + e.getMessage());
        }

        return newUUID;
    }

    /**
     * 验证 UUID 格式是否有效
     */
    private boolean isValidUUID(String uuid) {
        if (uuid == null) {
            return false;
        }

        try {
            // 尝试解析 UUID 验证格式
            UUID.fromString(uuid);
            return uuid.length() == 36; // 标准 UUID 格式长度
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 获取 UUID 的短格式（前8位）
     */
    public String getShortUUID() {
        String uuid = getUUID();
        if (uuid != null && uuid.length() >= 8) {
            return uuid.substring(0, 8);
        }
        return uuid;
    }

    /**
     * 重置 UUID（生成新的并替换旧的）
     *
     * @return 新的 UUID
     */
    public String resetUUID() {
        clearCachedUUID();
        return getUUID(); // 这会触发重新生成
    }

    /**
     * 清除缓存的 UUID
     */
    public void clearCachedUUID() {
        cachedUUID = null;
    }

    /**
     * 强制清除存储的 UUID（开发测试用）
     */
    public void clearStoredUUID() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(
                    PREFS_FILE, Context.MODE_PRIVATE
            );
            SharedPreferences.Editor editor = prefs.edit();
            editor.remove(UUID_KEY);
            editor.apply();

            clearCachedUUID();
            Log.d(TAG, "已清除存储的 UUID");
        } catch (Exception e) {
            Log.e(TAG, "清除 UUID 失败: " + e.getMessage());
        }
    }

    /**
     * 检查是否已存在 UUID
     */
    public boolean hasUUID() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(
                    PREFS_FILE, Context.MODE_PRIVATE
            );
            return prefs.contains(UUID_KEY);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 快速获取 UUID 的静态方法
     */
    public static String get(Context context) {
        return getInstance(context).getUUID();
    }

    /**
     * 获取 UUID 信息（用于调试）
     */
    public String getUUIDInfo() {
        String uuid = getUUID();
        return String.format(
                "UUID: %s\n长度: %d\n格式: %s",
                uuid,
                uuid.length(),
                isValidUUID(uuid) ? "有效" : "无效"
        );
    }
}
