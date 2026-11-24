package com.example.sensorar;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

// 读静态 GLB（已经烘焙好、无骨骼），摊平成三角形列表给 Canvas 画。
// 顶点是真实模型坐标，脚底在 y=0，整体约 1.8 米高。
class Model3D {

    static class Tri {
        float x0,y0,z0, x1,y1,z1, x2,y2,z2;  // 模型局部坐标
        float nx,ny,nz;                        // 面法线
        int color;                             // ARGB
        float depth;                           // 排序用，渲染时填
    }

    final List<Tri> tris = new ArrayList<>();

    static Model3D load(Context ctx, String asset) {
        Model3D m = new Model3D();
        try {
            InputStream in = ctx.getAssets().open(asset);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            byte[] glb = bo.toByteArray();
            m.parse(glb);
            Diag.log("模型加载 " + asset + " 三角面=" + m.tris.size());
        } catch (Throwable t) {
            Diag.log("!! 模型加载失败: " + Diag.stack(t));
        }
        return m;
    }

    private void parse(byte[] glb) throws Exception {
        ByteOrder LE = ByteOrder.LITTLE_ENDIAN;
        ByteBuffer bb = ByteBuffer.wrap(glb).order(LE);
        int magic = bb.getInt(), ver = bb.getInt(), total = bb.getInt();
        if (magic != 0x46546C67) throw new Exception("不是 GLB");

        String json = null;
        byte[] bin = null;
        int off = 12;
        while (off < glb.length) {
            int len = bb.getInt(off);
            int type = bb.getInt(off + 4);
            off += 8;
            if (type == 0x4E4F534A) {
                json = new String(glb, off, len, "UTF-8");
            } else if (type == 0x004E4942) {
                bin = new byte[len];
                System.arraycopy(glb, off, bin, 0, len);
            }
            off += len;
        }
        if (json == null || bin == null) throw new Exception("GLB 缺 chunk");

        JSONObject root = new JSONObject(json);
        JSONArray accessors = root.getJSONArray("accessors");
        JSONArray views = root.getJSONArray("bufferViews");
        JSONArray materials = root.getJSONArray("materials");
        JSONArray meshes = root.getJSONArray("meshes");

        for (int mi = 0; mi < meshes.length(); mi++) {
            JSONArray prims = meshes.getJSONObject(mi).getJSONArray("primitives");
            for (int pi = 0; pi < prims.length(); pi++) {
                JSONObject prim = prims.getJSONObject(pi);
                float[] pos = readVec3(prim.getJSONObject("attributes").getInt("POSITION"), accessors, views, bin);
                float[] nrm = prim.getJSONObject("attributes").has("NORMAL")
                        ? readVec3(prim.getJSONObject("attributes").getInt("NORMAL"), accessors, views, bin)
                        : null;
                int[] idx = readIndices(prim.getInt("indices"), accessors, views, bin);

                float[] bc = {0.8f,0.8f,0.8f,1f};
                if (prim.has("material")) {
                    JSONObject mat = materials.getJSONObject(prim.getInt("material"));
                    if (mat.has("pbrMetallicRoughness")) {
                        JSONArray c = mat.getJSONObject("pbrMetallicRoughness").optJSONArray("baseColorFactor");
                        if (c != null && c.length() >= 3) {
                            bc[0]=(float)c.getDouble(0); bc[1]=(float)c.getDouble(1); bc[2]=(float)c.getDouble(2);
                        }
                    }
                }
                int color = toColor(bc);

                for (int t = 0; t + 2 < idx.length; t += 3) {
                    int a=idx[t], b=idx[t+1], c=idx[t+2];
                    if (a*3+2>=pos.length || b*3+2>=pos.length || c*3+2>=pos.length) continue;
                    Tri tri = new Tri();
                    tri.x0=pos[a*3]; tri.y0=pos[a*3+1]; tri.z0=pos[a*3+2];
                    tri.x1=pos[b*3]; tri.y1=pos[b*3+1]; tri.z1=pos[b*3+2];
                    tri.x2=pos[c*3]; tri.y2=pos[c*3+1]; tri.z2=pos[c*3+2];
                    // 面法线：用顶点 normals 平均，没有就用叉积
                    if (nrm != null && a*3+2<nrm.length && b*3+2<nrm.length && c*3+2<nrm.length) {
                        tri.nx=(nrm[a*3]+nrm[b*3]+nrm[c*3])/3f;
                        tri.ny=(nrm[a*3+1]+nrm[b*3+1]+nrm[c*3+1])/3f;
                        tri.nz=(nrm[a*3+2]+nrm[b*3+2]+nrm[c*3+2])/3f;
                    } else {
                        float ux=tri.x1-tri.x0, uy=tri.y1-tri.y0, uz=tri.z1-tri.z0;
                        float vx=tri.x2-tri.x0, vy=tri.y2-tri.y0, vz=tri.z2-tri.z0;
                        tri.nx=uy*vz-uz*vy; tri.ny=uz*vx-ux*vz; tri.nz=ux*vy-uy*vx;
                    }
                    float nl=(float)Math.sqrt(tri.nx*tri.nx+tri.ny*tri.ny+tri.nz*tri.nz);
                    if (nl>0.00001f) { tri.nx/=nl; tri.ny/=nl; tri.nz/=nl; }
                    tri.color=color;
                    tris.add(tri);
                }
            }
        }
    }

    // 线性 baseColor 提到接近 sRGB，免得太暗
    private static int toColor(float[] c) {
        int r = clamp((int)(Math.pow(Math.max(0,c[0]),1/1.8f)*255*1.15f));
        int g = clamp((int)(Math.pow(Math.max(0,c[1]),1/1.8f)*255*1.15f));
        int b = clamp((int)(Math.pow(Math.max(0,c[2]),1/1.8f)*255*1.15f));
        return 0xFF000000 | (r<<16) | (g<<8) | b;
    }

    private static int clamp(int v){ return Math.max(0,Math.min(255,v)); }

    private static int[] readIndices(int ai, JSONArray accessors, JSONArray views, byte[] bin) throws Exception {
        JSONObject a = accessors.getJSONObject(ai);
        int count = a.getInt("count");
        int comp = a.getInt("componentType");
        JSONObject v = views.getJSONObject(a.getInt("bufferView"));
        int off = v.getInt("byteOffset") + a.optInt("byteOffset",0);
        int[] out = new int[count];
        ByteBuffer bb = ByteBuffer.wrap(bin, off, count*4).order(ByteOrder.LITTLE_ENDIAN);
        if (comp == 5125) {
            for (int i=0;i<count;i++) out[i]=bb.getInt();
        } else if (comp == 5123) {
            for (int i=0;i<count;i++) out[i]=bb.getShort() & 0xFFFF;
        } else {
            for (int i=0;i<count;i++) out[i]=bb.get() & 0xFF;
        }
        return out;
    }

    private static float[] readVec3(int ai, JSONArray accessors, JSONArray views, byte[] bin) throws Exception {
        JSONObject a = accessors.getJSONObject(ai);
        int count = a.getInt("count");
        JSONObject v = views.getJSONObject(a.getInt("bufferView"));
        int off = v.getInt("byteOffset") + a.optInt("byteOffset",0);
        float[] out = new float[count*3];
        ByteBuffer bb = ByteBuffer.wrap(bin, off, count*12).order(ByteOrder.LITTLE_ENDIAN);
        for (int i=0;i<count*3;i++) out[i]=bb.getFloat();
        return out;
    }
}
