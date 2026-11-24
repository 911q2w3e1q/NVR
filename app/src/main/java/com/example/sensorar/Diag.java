package com.example.sensorar;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// 调试日志：实时显示 + 落盘，崩了重开能看到上次走到哪
public final class Diag {
    private static final String TAG = "NVR/Diag";

    // log() 会被绘制线程、UI线程、EasyAR回调线程同时调用，缓冲和文件都得同步
    private static final StringBuilder sb = new StringBuilder();
    private static final Object lock = new Object();

    private static TextView logView;
    private static ScrollView scrollView;
    private static File diagFile;
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private Diag() {}

    public static void init(Context ctx, TextView tv, ScrollView sv) {
        logView = tv;
        scrollView = sv;
        diagFile = new File(ctx.getExternalFilesDir(null), "nvrdiag.txt");
        String last = readAll(diagFile);
        synchronized (lock) {
            sb.setLength(0);
            if (last != null && last.length() > 0) {
                sb.append("===== 上次退出前最后记录 =====\n").append(last);
                sb.append("\n===== 本次启动 =====\n");
            }
        }
        writeAll("");
        log("APP onCreate 诊断初始化完成");
    }

    public static void log(final String msg) {
        final String line = FMT.format(new Date()) + " " + msg;
        synchronized (lock) {
            sb.append(line).append('\n');
        }
        append(line + "\n");
        ui.post(() -> {
            String text;
            synchronized (lock) { text = sb.toString(); }
            if (logView != null) logView.setText(text);
            if (scrollView != null) {
                scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
            }
        });
    }

    public static String stack(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    // 文件写入也加锁，避免多线程同时 FileWriter 造成日志交错
    private static void append(String s) {
        synchronized (lock) {
            FileWriter fw = null;
            try {
                fw = new FileWriter(diagFile, true);
                fw.write(s);
            } catch (Throwable t) {
                Log.w(TAG, "append 失败: " + t.getMessage());
            } finally {
                if (fw != null) {
                    try { fw.close(); }
                    catch (Throwable t) { Log.w(TAG, "append close 失败: " + t.getMessage()); }
                }
            }
        }
    }

    private static String readAll(File f) {
        BufferedReader br = null;
        try {
            if (!f.exists()) return null;
            StringBuilder b = new StringBuilder();
            br = new BufferedReader(new FileReader(f));
            String l;
            int count = 0;
            while ((l = br.readLine()) != null && count++ < 80) b.append(l).append('\n');
            return b.toString();
        } catch (Throwable t) {
            Log.w(TAG, "readAll 失败: " + t.getMessage());
            return null;
        } finally {
            if (br != null) {
                try { br.close(); }
                catch (Throwable t) { Log.w(TAG, "readAll close 失败: " + t.getMessage()); }
            }
        }
    }

    private static void writeAll(String s) {
        synchronized (lock) {
            FileWriter fw = null;
            try {
                fw = new FileWriter(diagFile, false);
                fw.write(s);
            } catch (Throwable t) {
                Log.w(TAG, "writeAll 失败: " + t.getMessage());
            } finally {
                if (fw != null) {
                    try { fw.close(); }
                    catch (Throwable t) { Log.w(TAG, "writeAll close 失败: " + t.getMessage()); }
                }
            }
        }
    }
}
