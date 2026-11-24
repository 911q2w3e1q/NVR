package com.example.sensorar;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// 读烘焙好的静态网格：顶点(pos3+nrm3+uv2) + 索引 + 内嵌贴图
class MeshGPU {
    int vertexCount, indexCount;
    ByteBuffer vertexBuf;   // float, 交错 pos/nrm/uv，每顶点 8 float
    ByteBuffer indexBuf;    // short 索引
    byte[] jpeg;            // 贴图 jpeg 字节

    static MeshGPU load(Context ctx, String asset) {
        MeshGPU m = new MeshGPU();
        try {
            InputStream in = ctx.getAssets().open(asset);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            byte[] all = bo.toByteArray();
            ByteOrder LE = ByteOrder.LITTLE_ENDIAN;
            ByteBuffer bb = ByteBuffer.wrap(all).order(LE);
            if (bb.getInt(0) != 0x4D52564E) throw new Exception("不是 NVRM 网格"); // 'NVRM' 小端
            bb.getInt(4); // version
            m.vertexCount = bb.getInt(8);
            m.indexCount = bb.getInt(12);
            int texLen = bb.getInt(16);
            int vOff = 20;
            int iOff = vOff + m.vertexCount * 8 * 4;
            int tOff = iOff + m.indexCount * 2;
            m.vertexBuf = ByteBuffer.wrap(all, vOff, m.vertexCount * 8 * 4).order(LE).slice();
            m.indexBuf = ByteBuffer.wrap(all, iOff, m.indexCount * 2).order(LE).slice();
            m.jpeg = new byte[texLen];
            System.arraycopy(all, tOff, m.jpeg, 0, texLen);
            Diag.log("GPU 网格加载 " + asset + " 顶点=" + m.vertexCount
                    + " 三角=" + (m.indexCount / 3) + " 贴图=" + texLen);
        } catch (Throwable t) {
            Diag.log("!! GPU 网格加载失败: " + Diag.stack(t));
        }
        return m;
    }
}
