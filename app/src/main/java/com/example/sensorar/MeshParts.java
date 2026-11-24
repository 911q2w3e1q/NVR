package com.example.sensorar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

// 分层网格：每个部件独立顶点/索引/贴图，按原始顺序绘制。
// 格式 NVR2: magic(4) version(int) parts(int)
//   每部件: vcount(int) icount(int) texlen(int) blend(int) r,g,b,a(float)
//           vertices(pos3+nrm3+uv2) indices(u16) jpeg
class MeshParts {
    static class Part {
        int vertexCount, indexCount;
        ByteBuffer vertexBuf, indexBuf;
        Bitmap bitmap;       // 贴图解码成 bitmap，onSurfaceCreated 里上传纹理
        int blendMode;       // 0=OPAQUE 1=BLEND 2=MASK
        float[] factor = new float[4];
        int texW, texH;      // 贴图实际尺寸，用来决定最近邻/线性采样
    }
    List<Part> parts = new ArrayList<>();

    // 从 assets 加载（保留给旧调用）
    static MeshParts load(Context ctx, String asset) {
        try {
            InputStream in = ctx.getAssets().open(asset);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return parse(bo.toByteArray(), asset);
        } catch (Throwable t) {
            Diag.log("!! 网格读取失败: " + Diag.stack(t));
            return new MeshParts();
        }
    }

    // 从字节数组加载：自动发现机制统一走这里（assets / 外部文件都能处理）
    // 按文件头魔数分派：GLB(含 VRM) 走运行时 glTF 解析器，其余按私有 NVR2 网格解析。
    static MeshParts fromBytes(byte[] all, String tag) {
        return fromBytes(all, tag, null);
    }

    // 带进度回调版本：仅 GLB 路径上报解析进度，NVR2 加载本身很快无需上报
    static MeshParts fromBytes(byte[] all, String tag, Progress progress) {
        if (all != null && all.length >= 4) {
            int magic = (all[0] & 0xFF) | ((all[1] & 0xFF) << 8)
                    | ((all[2] & 0xFF) << 16) | ((all[3] & 0xFF) << 24);
            if (magic == ModelScanner.GLB_MAGIC) return GlbLoader.load(all, tag, progress);
        }
        return parse(all, tag);
    }

    // 把解析好的网格烘焙成 NVR2 字节（运行时 GLB 解析慢时，缓存成私有格式下次秒开）。
    // 布局与 parse() 严格对称：magic/version/parts + 每部件头 + 顶点 + u16 索引 + 贴图。
    static byte[] toNvr2Bytes(MeshParts m) throws Exception {
        return toNvr2Bytes(m, null);
    }

    static byte[] toNvr2Bytes(MeshParts m, Progress progress) throws Exception {
        if (m == null || m.parts.isEmpty()) throw new Exception("空网格无法烘焙");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ByteBuffer head = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        head.putInt(ModelScanner.MAGIC);
        head.putInt(1);                       // version
        head.putInt(m.parts.size());
        bo.write(head.array());

        for (int i = 0; i < m.parts.size(); i++) {
            Part p = m.parts.get(i);
            byte[] tex = encodeTexture(p.bitmap);
            ByteBuffer ph = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            ph.putInt(p.vertexCount);
            ph.putInt(p.indexCount);
            ph.putInt(tex.length);
            ph.putInt(p.blendMode);
            for (int c = 0; c < 4; c++) ph.putFloat(p.factor[c]);
            bo.write(ph.array());

            // 顶点/索引用 duplicate 读，绝不移动原 Buffer 的 position
            ByteBuffer vb = p.vertexBuf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            vb.clear();
            byte[] vbytes = new byte[vb.remaining()];
            vb.get(vbytes);
            bo.write(vbytes);

            ByteBuffer ib = p.indexBuf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            ib.clear();
            byte[] ibytes = new byte[ib.remaining()];
            ib.get(ibytes);
            bo.write(ibytes);

            bo.write(tex);
            if (progress != null) progress.onProgress(i + 1, m.parts.size(), "写入缓存");
        }
        return bo.toByteArray();
    }

    // 贴图编码：带 alpha 用 PNG（保留透明），否则用 JPEG（体积小）。
    private static byte[] encodeTexture(Bitmap bm) throws Exception {
        if (bm == null || bm.isRecycled()) return new byte[0];
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        boolean hasAlpha = bm.hasAlpha();
        Bitmap.CompressFormat fmt = hasAlpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
        if (!bm.compress(fmt, hasAlpha ? 100 : 92, bo)) throw new Exception("贴图编码失败");
        return bo.toByteArray();
    }

    private static MeshParts parse(byte[] all, String tag) {
        MeshParts m = new MeshParts();
        try {
            ByteOrder LE = ByteOrder.LITTLE_ENDIAN;
            ByteBuffer bb = ByteBuffer.wrap(all).order(LE);
            if (bb.getInt(0) != ModelScanner.MAGIC) throw new Exception("不是 NVR2 网格");
            bb.getInt(4); // version
            int partCount = bb.getInt(8);
            int off = 12;
            for (int i = 0; i < partCount; i++) {
                Part p = new Part();
                p.vertexCount = bb.getInt(off); off += 4;
                p.indexCount = bb.getInt(off); off += 4;
                int texLen = bb.getInt(off); off += 4;
                p.blendMode = bb.getInt(off); off += 4;
                for (int c = 0; c < 4; c++) { p.factor[c] = bb.getFloat(off); off += 4; }
                int vBytes = p.vertexCount * 8 * 4;
                int iBytes = p.indexCount * 2;
                p.vertexBuf = ByteBuffer.wrap(all, off, vBytes).order(LE).slice(); off += vBytes;
                p.indexBuf = ByteBuffer.wrap(all, off, iBytes).order(LE).slice(); off += iBytes;
                byte[] jpeg = new byte[texLen];
                System.arraycopy(all, off, jpeg, 0, texLen); off += texLen;
                p.bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                if (p.bitmap != null) {
                    p.texW = p.bitmap.getWidth();
                    p.texH = p.bitmap.getHeight();
                }
                m.parts.add(p);
            }
            Diag.log("网格解析 " + tag + " 部件数=" + m.parts.size());
        } catch (Throwable t) {
            Diag.log("!! 网格解析失败 " + tag + ": " + Diag.stack(t));
        }
        return m;
    }
}
