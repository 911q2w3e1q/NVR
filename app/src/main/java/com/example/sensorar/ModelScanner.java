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

// 自动发现可用模型：扫 assets 和应用外部 models 目录，读文件头校验魔数。
// 支持两种格式：私有 .mesh/.nvr2(NVR2) 和标准 .glb/.vrm(glTF 2.0 容器)。
// 别人拿到源码后，把自己的模型丢进这两个位置任意一个就能用，不用改代码。
class ModelScanner {

    // NVR2 文件头魔数，小端读出来是 0x3252564E（ASCII "NVR2"）
    static final int MAGIC = 0x3252564E;
    // GLB 文件头魔数，小端读出来是 0x46546C67（ASCII "glTF"），VRM 也是这个容器
    static final int GLB_MAGIC = 0x46546C67;

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
        // 同名去重：烘焙生成的 NVR2 缓存优先于它的 GLB/VRM 源文件，避免列表里出现两份
        list = dedupCache(list);
        Diag.log("模型扫描: 发现 " + list.size() + " 个 -> " + labels(list));
        return list;
    }

    // 缓存文件名 = 源文件去掉扩展名 + ".nvr2"（如 skin.vrm -> skin.nvr2）。
    // 若同 label 既有烘焙缓存(NVR2)又有源(GLB/VRM)，只保留 NVR2，让已转化的模型走快路径。
    private static List<Entry> dedupCache(List<Entry> list) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : list) {
            boolean covered = false;
            if (isGlbEntry(e)) {
                for (Entry other : list) {
                    if (other == e || !isNvr2Entry(other)) continue;
                    if (other.label.equalsIgnoreCase(e.label)
                            && other.source == e.source) { covered = true; break; }
                }
            }
            if (!covered) out.add(e);
        }
        return out;
    }

    static boolean isGlbEntry(Entry e) {
        String s = e.file.toLowerCase();
        return s.endsWith(".glb") || s.endsWith(".vrm");
    }

    static boolean isNvr2Entry(Entry e) {
        String s = e.file.toLowerCase();
        return s.endsWith(".mesh") || s.endsWith(".nvr2");
    }

    // 烘焙缓存路径：和源文件同目录、同 basename，扩展名换成 .nvr2
    static String cacheNameFor(String sourceName) {
        int dot = sourceName.lastIndexOf('.');
        String base = dot > 0 ? sourceName.substring(0, dot) : sourceName;
        return base + ".nvr2";
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
                if (!isModelName(name)) continue;
                String rel = dir.isEmpty() ? name : dir + "/" + name;
                long size;
                try { size = assetSize(am, rel); } catch (Throwable t) { continue; }
                if (!isModelAsset(am, rel)) {
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
            if (!f.isFile() || !isModelName(f.getName())) continue;
            if (!isModelFile(f)) {
                Diag.log("跳过非模型文件(魔数不符): " + f.getName());
                continue;
            }
            out.add(new Entry(f.getAbsolutePath(), labelOf(f.getName()), SRC_FILE,
                    f.length(), isPixelName(f.getName())));
        }
    }

    // 只认这四种模型后缀（大小写不敏感）
    static boolean isModelName(String name) {
        String s = name.toLowerCase();
        return s.endsWith(".mesh") || s.endsWith(".nvr2")
                || s.endsWith(".glb") || s.endsWith(".vrm");
    }

    // 文件名 -> 显示名：去掉扩展名和 _nvr2 后缀，下划线转空格
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
    static boolean isModelFile(File f) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            return checkModelMagic(in);
        } catch (Throwable t) {
            return false;
        } finally {
            close(in);
        }
    }

    static boolean isModelAsset(AssetManager am, String rel) {
        InputStream in = null;
        try {
            in = am.open(rel);
            return checkModelMagic(in);
        } catch (Throwable t) {
            return false;
        } finally {
            close(in);
        }
    }

    // NVR2 和 GLB(含 VRM) 两种魔数都算合法模型
    private static boolean checkModelMagic(InputStream in) throws Exception {
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
        return magic == MAGIC || magic == GLB_MAGIC;
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
