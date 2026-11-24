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
    static MeshParts fromBytes(byte[] all, String tag) {
        return parse(all, tag);
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
