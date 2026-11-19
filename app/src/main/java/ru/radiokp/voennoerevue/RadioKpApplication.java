package ru.radiokp.voennoerevue;

import android.app.ActivityManager;
import android.app.Application;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Process-level diagnostics. This logger is intentionally independent of the
 * playback service so the next launch can report why Android says the previous
 * application process exited.
 */
public final class RadioKpApplication extends Application {
    private static final Object LOCK = new Object();
    private static final String FILE_NAME = "radio-kp-process-diagnostics.log";
    private static final long MAX_FILE_BYTES = 768L * 1024L;
    private static final long KEEP_TAIL_BYTES = 512L * 1024L;
    private static final int MAX_EVENT_CHARS = 24000;
    private static volatile Context appContext;
    private static final String PROCESS_INSTANCE_ID = UUID.randomUUID().toString();

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        record(this, "PROCESS_START",
                "processInstance=" + PROCESS_INSTANCE_ID + " · " + deviceSnapshot());
        collectHistoricalProcessExits();

        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            StringWriter stack = new StringWriter();
            try {
                error.printStackTrace(new PrintWriter(stack));
            } catch (Exception ignored) {
            }
            record(this, "UNCAUGHT_EXCEPTION",
                    "thread=" + (thread == null ? "unknown" : thread.getName())
                            + " · type=" + (error == null ? "unknown" : error.getClass().getName())
                            + " · stack=" + stack.toString());
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        record(this, "APP_TRIM_MEMORY", "level=" + level + " · " + memorySnapshot());
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        record(this, "APP_LOW_MEMORY", memorySnapshot());
    }

    @Override
    public void onTerminate() {
        record(this, "APP_TERMINATE", "onTerminate callback · " + memorySnapshot());
        super.onTerminate();
    }

    public static void record(Context context, String stage, String details) {
        Context contextToUse = appContext;
        if (contextToUse == null && context != null) {
            contextToUse = context.getApplicationContext();
            appContext = contextToUse;
        }
        if (contextToUse == null) return;

        String safeDetails = details == null ? "" : details.replace('\r', ' ').replace('\n', ' ');
        if (safeDetails.length() > MAX_EVENT_CHARS) {
            safeDetails = safeDetails.substring(0, MAX_EVENT_CHARS) + "…[truncated]";
        }
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
                .format(new Date());
        String thread = Thread.currentThread().getName();
        String line = stamp + " | elapsedRealtimeMs=" + SystemClock.elapsedRealtime()
                + " | pid=" + Process.myPid()
                + " | processInstance=" + PROCESS_INSTANCE_ID
                + " | thread=" + thread
                + " | " + (stage == null ? "UNKNOWN" : stage)
                + " | " + safeDetails + "\n";

        synchronized (LOCK) {
            try {
                File file = new File(contextToUse.getFilesDir(), FILE_NAME);
                appendWithRotation(file, line.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // Diagnostics must never crash or block normal playback.
            }
        }
    }

    public static String getDiagnosticsSnapshot(Context context) {
        Context contextToUse = appContext;
        if (contextToUse == null && context != null) contextToUse = context.getApplicationContext();
        if (contextToUse == null) return "";
        synchronized (LOCK) {
            File file = new File(contextToUse.getFilesDir(), FILE_NAME);
            if (!file.exists() || file.length() <= 0L) return "";
            try (FileInputStream input = new FileInputStream(file);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            } catch (Exception error) {
                return "PROCESS_DIAGNOSTICS_READ_ERROR | " + error.getClass().getSimpleName()
                        + ": " + String.valueOf(error.getMessage());
            }
        }
    }

    private static void appendWithRotation(File file, byte[] line) throws Exception {
        if (file.exists() && file.length() + line.length > MAX_FILE_BYTES) {
            trimToTail(file);
        }
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write(line);
            output.flush();
        }
    }

    private static void trimToTail(File file) throws Exception {
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try (RandomAccessFile input = new RandomAccessFile(file, "r");
             FileOutputStream output = new FileOutputStream(temporary, false)) {
            long length = input.length();
            long start = Math.max(0L, length - KEEP_TAIL_BYTES);
            input.seek(start);
            if (start > 0L) {
                int value;
                while ((value = input.read()) != -1 && value != '\n') {
                    // Drop the partial first line after seeking into the file.
                }
            }
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
        }
        if (!temporary.renameTo(file)) {
            try (FileInputStream input = new FileInputStream(temporary);
                 FileOutputStream output = new FileOutputStream(file, false)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush();
            }
            temporary.delete();
        }
    }

    private void collectHistoricalProcessExits() {
        if (Build.VERSION.SDK_INT < 30) {
            record(this, "HISTORICAL_EXITS", "ApplicationExitInfo unavailable before Android 11");
            return;
        }
        try {
            ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (manager == null) {
                record(this, "HISTORICAL_EXITS", "ActivityManager unavailable");
                return;
            }
            List<ApplicationExitInfo> exits =
                    manager.getHistoricalProcessExitReasons(getPackageName(), 0, 20);
            if (exits == null || exits.isEmpty()) {
                record(this, "HISTORICAL_EXITS", "no previous process-exit records returned by Android");
                return;
            }
            for (ApplicationExitInfo info : exits) {
                if (info == null) continue;
                record(this, "HISTORICAL_PROCESS_EXIT",
                        "timestamp=" + formatTimestamp(info.getTimestamp())
                                + " · pid=" + info.getPid()
                                + " · process=" + info.getProcessName()
                                + " · reason=" + reasonName(info.getReason()) + "(" + info.getReason() + ")"
                                + " · status=" + info.getStatus()
                                + " · importance=" + info.getImportance()
                                + " · pssKb=" + info.getPss()
                                + " · rssKb=" + info.getRss()
                                + " · description=" + String.valueOf(info.getDescription()));
            }
        } catch (Exception error) {
            record(this, "HISTORICAL_EXITS_ERROR",
                    error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
        }
    }

    private String deviceSnapshot() {
        long versionCode = 0L;
        String versionName = "?";
        try {
            android.content.pm.PackageInfo packageInfo =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            versionName = packageInfo.versionName == null ? "?" : packageInfo.versionName;
            if (Build.VERSION.SDK_INT >= 28) versionCode = packageInfo.getLongVersionCode();
            else versionCode = packageInfo.versionCode;
        } catch (Exception ignored) {
        }
        String processName = Build.VERSION.SDK_INT >= 28
                ? Application.getProcessName() : getPackageName();
        return "device=" + Build.MANUFACTURER + " " + Build.MODEL
                + " · deviceCode=" + Build.DEVICE
                + " · android=" + Build.VERSION.RELEASE
                + " · sdk=" + Build.VERSION.SDK_INT
                + " · package=" + getPackageName()
                + " · version=" + versionName + "(" + versionCode + ")"
                + " · processName=" + processName
                + " · uid=" + Process.myUid()
                + " · targetSdk=" + getApplicationInfo().targetSdkVersion
                + " · timezone=" + TimeZone.getDefault().getID()
                + " · " + memorySnapshot();
    }

    private static String memorySnapshot() {
        Runtime runtime = Runtime.getRuntime();
        Debug.MemoryInfo debugMemory = new Debug.MemoryInfo();
        try {
            Debug.getMemoryInfo(debugMemory);
        } catch (Exception ignored) {
        }
        StringBuilder out = new StringBuilder("javaHeapUsedBytes=")
                .append(runtime.totalMemory() - runtime.freeMemory())
                .append(" · javaHeapTotalBytes=").append(runtime.totalMemory())
                .append(" · javaHeapMaxBytes=").append(runtime.maxMemory())
                .append(" · processPssKb=").append(debugMemory.getTotalPss());
        Context context = appContext;
        if (context != null) {
            try {
                ActivityManager manager = (ActivityManager) context.getSystemService(ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                if (manager != null) {
                    manager.getMemoryInfo(info);
                    out.append(" · deviceAvailMemBytes=").append(info.availMem)
                            .append(" · deviceLowMemory=").append(info.lowMemory)
                            .append(" · deviceThresholdBytes=").append(info.threshold);
                }
            } catch (Exception ignored) {
            }
        }
        return out.toString();
    }

    private static String formatTimestamp(long timestamp) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
                .format(new Date(timestamp));
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case 0: return "UNKNOWN";
            case 1: return "EXIT_SELF";
            case 2: return "SIGNALED";
            case 3: return "LOW_MEMORY";
            case 4: return "CRASH";
            case 5: return "CRASH_NATIVE";
            case 6: return "ANR";
            case 7: return "INITIALIZATION_FAILURE";
            case 8: return "PERMISSION_CHANGE";
            case 9: return "EXCESSIVE_RESOURCE_USAGE";
            case 10: return "USER_REQUESTED";
            case 11: return "USER_STOPPED";
            case 12: return "DEPENDENCY_DIED";
            case 13: return "OTHER";
            case 14: return "FREEZER";
            case 15: return "PACKAGE_STATE_CHANGE";
            case 16: return "PACKAGE_UPDATED";
            default: return "UNKNOWN_OR_NEWER_REASON";
        }
    }
}
