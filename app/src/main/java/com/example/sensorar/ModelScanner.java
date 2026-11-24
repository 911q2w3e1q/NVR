package com.example.sensorar;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

// 自动发现可用模型：扫 assets 和应用外部 models 目录，读文件头校验 NVR2 魔数。
// 别人拿到源码后，把自己的 .mesh 丢进这两个位置任意一个就能用，不用改代码。
class ModelScanner {

    // NVR2 文件头魔数，小端读出来是 0x3252564E（ASCII "NVR2"）
    static final int MAGIC = 0x3252564E;

    static final int SRC_ASSET = 0;   // 打包在 assets 里
    static final int SRC_FILE = 1;    // 外部 models 目录里的文件

    static class Entry {
        final String file;    // assets 里的相对名，或外部文件的绝对路径
        final String label;   // 显示名
        final int source;
        final long bytes;
        final boolean pixel;  // 是否像素贴图（文件名含像素特征），仅作加载提示

        Entry(String file, String label, int source, long bytes, boolean pixel) {
            this.file = file;
            this.label = label;
            this.source = source;
            this.bytes = bytes;
            this.pixel = pixel;
        }
    }

    // 外部模型目录：应用私有外部空间，Android 上无需任何存储权限，文件管理器也能看到
    static File externalDir(Context ctx) {
        File dir = new File(ctx.getExternalFilesDir(null), "models");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    // 扫描全部来源，返回按名称排序的模型列表（外部目录的排前面，方便用户自己放的模型优先）
    static List<Entry> scan(Context ctx) {
        List<Entry> list = new ArrayList<>();
        scanExternal(ctx, list);
        scanAssets(ctx, list);
        Collections.sort(list, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.source != b.source) return b.source - a.source; // file(1) 在 asset(0) 前
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        Diag.log("模型扫描: 发现 " + list.size() + " 个 -> " + labels(list));
        return list;
    }

    private static String labels(List<Entry> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i).label);
        }
        return sb.toString();
    }

    private static void scanAssets(Context ctx, List<Entry> out) {
        try {
            AssetManager am = ctx.getAssets();
            // assets 根目录，以及 models 子目录都扫
            scanAssetDir(am, "", out);
            scanAssetDir(am, "models", out);
        } catch (Throwable t) {
            Diag.log("!! 扫描 assets 失败: " + t.getMessage());
        }
    }

    private static void scanAssetDir(AssetManager am, String dir, List<Entry> out) {
        try {
            String[] names = am.list(dir);
            if (names == null) return;
            for (String name : names) {
                if (!name.toLowerCase().endsWith(".mesh")) continue;
                String rel = dir.isEmpty() ? name : dir + "/" + name;
                long size;
                try { size = assetSize(am, rel); } catch (Throwable t) { continue; }
                if (!isNvr2Asset(am, rel)) {
                    Diag.log("跳过非模型文件(魔数不符): " + rel);
                    continue;
                }
                out.add(new Entry(rel, labelOf(name), SRC_ASSET, size, isPixelName(name)));
            }
        } catch (Throwable t) {
            Diag.log("!! 扫描目录失败 " + dir + ": " + t.getMessage());
        }
    }

    private static void scanExternal(Context ctx, List<Entry> out) {
        File dir = externalDir(ctx);
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile() || !f.getName().toLowerCase().endsWith(".mesh")) continue;
            if (!isNvr2File(f)) {
                Diag.log("跳过非模型文件(魔数不符): " + f.getName());
                continue;
            }
            out.add(new Entry(f.getAbsolutePath(), labelOf(f.getName()), SRC_FILE,
                    f.length(), isPixelName(f.getName())));
        }
    }

    // 文件名 -> 显示名：去掉 .mesh 和 _nvr2 后缀，下划线转空格
    static String labelOf(String fileName) {
        String s = fileName;
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        if (s.endsWith("_nvr2")) s = s.substring(0, s.length() - 5);
        return s.replace('_', ' ');
    }

    // 像素风模型的贴图放大要用最近邻采样。这里按文件名提示，实际由贴图尺寸决定（见 GLRenderer）
    private static boolean isPixelName(String n) {
        String s = n.toLowerCase();
        return s.contains("mcskin") || s.contains("skin") || s.contains("block") || s.contains("pixel");
    }

    // 只打开文件读前 4 字节校验魔数，不把整个模型读进内存
    static boolean isNvr2File(File f) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            return checkMagic(in);
        } catch (Throwable t) {
            return false;
        } finally {
            close(in);
        }
    }

    static boolean isNvr2Asset(AssetManager am, String rel) {
        InputStream in = null;
        try {
            in = am.open(rel);
            return checkMagic(in);
        } catch (Throwable t) {
            return false;
        } finally {
            close(in);
        }
    }

    private static boolean checkMagic(InputStream in) throws Exception {
        byte[] head = new byte[4];
        int got = 0;
        while (got < 4) {
            int r = in.read(head, got, 4 - got);
            if (r < 0) break;
            got += r;
        }
        if (got < 4) return false;
        int magic = (head[0] & 0xFF) | ((head[1] & 0xFF) << 8)
                | ((head[2] & 0xFF) << 16) | ((head[3] & 0xFF) << 24);
        return magic == MAGIC;
    }

    private static long assetSize(AssetManager am, String rel) throws Exception {
        InputStream in = am.open(rel);
        try {
            long n = 0;
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) n += r;
            return n;
        } finally {
            close(in);
        }
    }

    // 读取完整模型字节，交给 MeshParts 解析
    static byte[] readEntry(Context ctx, Entry e) throws Exception {
        InputStream in = e.source == SRC_ASSET
                ? ctx.getAssets().open(e.file)
                : new FileInputStream(new File(e.file));
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toByteArray();
        } finally {
            close(in);
        }
    }

    private static void close(InputStream in) {
        if (in != null) try { in.close(); } catch (Throwable ignore) { }
    }
}
