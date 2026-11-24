package com.example.sensorar;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 运行时 GLB / VRM 加载器：把标准 glTF 2.0 二进制(GLB)解析成现有 MeshParts 结构，
// 这样网上下载的 .glb / .vrm 丢进模型目录就能直接渲染，无需先转成私有 .mesh。
//
// 支持的特性（覆盖绝大多数从 VRoid / Sketchfab / -common 导出的模型）：
//   - GLB 容器(JSON chunk + BIN chunk)，VRM 只是带 VRMC_vrm 扩展的 GLB
//   - accessor: componentType 5121/5123/5125/5126，type SCALAR/VEC2/VEC3/VEC4/MAT4
//   - bufferView byteStride（interleaved 顶点）
//   - 节点层级 TRS / matrix，累积世界变换（VRM 骨骼在 rest pose 下等价于世界矩阵直乘）
//   - primitive mode 4(TRIANGLES) / 5(TRIANGLE_STRIP) / 6(TRIANGLE_FAN)
//   - baseColorTexture / baseColorFactor / alphaMode(OPAQUE/BLEND/MASK) / KHR_materials_unlit
//   - 顶点数 > 65535 自动拆成多个 part（索引用 u16）
// 不支持：Draco 压缩的 primitive（跳过，同一文件里未压缩 primitive 仍可显示）、
//        骨骼动画（只取 bind/rest pose 静态姿态）、稀疏 accessor、外部 .gltf+.bin 拆分文件。
class GlbLoader {

    static final int MAGIC = 0x46546C67; // ASCII "glTF"

    static MeshParts load(byte[] all, String tag) {
        MeshParts m = new MeshParts();
        try {
            ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
            if (bb.getInt(0) != MAGIC) throw new Exception("不是 GLB 文件");
            bb.getInt(4); // version
            long declared = bb.getInt(8) & 0xFFFFFFFFL;
            if (declared > all.length + 8 || declared < 20) throw new Exception("GLB 长度字段异常");

            byte[] jsonBytes = null, binBytes = null;
            int off = 12;
            while (off + 8 <= all.length) {
                long clenL = bb.getInt(off) & 0xFFFFFFFFL;
                int ctype = bb.getInt(off + 4);
                off += 8;
                if (clenL > all.length || off + clenL > all.length) break;
                int clen = (int) clenL;
                if (ctype == 0x4E4F534A) {            // JSON
                    jsonBytes = new byte[clen];
                    System.arraycopy(all, off, jsonBytes, 0, clen);
                } else if (ctype == 0x004E4942) {     // BIN
                    binBytes = new byte[clen];
                    System.arraycopy(all, off, binBytes, 0, clen);
                }
                off += clen;
            }
            if (jsonBytes == null) throw new Exception("GLB 缺 JSON chunk");
            if (binBytes == null) binBytes = new byte[0];

            String json = new String(jsonBytes, "UTF-8").trim();
            JSONObject root = new JSONObject(json);
            parse(root, binBytes, m, tag);
        } catch (Throwable t) {
            String msg = t.getMessage();
            Diag.log("!! GLB 解析失败 " + tag + ": " + (msg == null ? Diag.stack(t) : msg));
        }
        return m;
    }

    // ---------- 场景 / 节点 ----------

    private static void parse(JSONObject root, byte[] bin, MeshParts out, String tag) throws Exception {
        JSONArray accessors = root.optJSONArray("accessors");
        JSONArray bufferViews = root.optJSONArray("bufferViews");
        JSONArray meshes = root.optJSONArray("meshes");
        JSONArray nodes = root.optJSONArray("nodes");
        JSONArray materials = root.optJSONArray("materials");
        JSONArray images = root.optJSONArray("images");
        JSONArray textures = root.optJSONArray("textures");
        if (meshes == null || nodes == null) throw new Exception("GLB 缺 meshes/nodes");

        int nodeCount = nodes.length();
        float[][] world = new float[nodeCount][];

        // 从每个 scene 的根开始 DFS，算累积世界矩阵
        JSONArray scenes = root.optJSONArray("scenes");
        int sceneIdx = root.optInt("scene", 0);
        List<Integer> roots = new ArrayList<>();
        if (scenes != null && scenes.length() > 0) {
            JSONObject sc = scenes.optJSONObject(Math.min(sceneIdx, scenes.length() - 1));
            JSONArray rn = sc == null ? null : sc.optJSONArray("nodes");
            if (rn != null) for (int i = 0; i < rn.length(); i++) roots.add(rn.optInt(i));
        }
        if (roots.isEmpty()) for (int i = 0; i < nodeCount; i++) roots.add(i); // 没场景就当所有节点都是根

        boolean[] visiting = new boolean[nodeCount];
        for (int r : roots) visitNode(r, IDENTITY, nodes, world, visiting, 0);

        int partTotal = 0;
        int primTotal = 0, dracoSkip = 0;
        for (int i = 0; i < nodeCount; i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null) continue;
            int mi = node.optInt("mesh", -1);
            if (mi < 0 || meshes == null || mi >= meshes.length()) continue;
            float[] wm = world[i] != null ? world[i] : IDENTITY;
            JSONObject mesh = meshes.optJSONObject(mi);
            if (mesh == null) continue;
            JSONArray prims = mesh.optJSONArray("primitives");
            if (prims == null) continue;
            for (int pi = 0; pi < prims.length(); pi++) {
                JSONObject prim = prims.optJSONObject(pi);
                if (prim == null) continue;
                primTotal++;
                // Draco 压缩的 primitive 没法在运行时解，跳过；同文件里常另有未压缩版本
                JSONObject ext = prim.optJSONObject("extensions");
                if (ext != null && (ext.has("KHR_draco_mesh_compression")
                        || ext.has("EXT_meshopt_compression"))) { dracoSkip++; continue; }
                JSONObject attrs = prim.optJSONObject("attributes");
                if (attrs == null || !attrs.has("POSITION")) continue;
                int posAcc = attrs.optInt("POSITION", -1);
                int nrmAcc = attrs.optInt("NORMAL", -1);
                int uvAcc = attrs.optInt("TEXCOORD_0", -1);
                int idxAcc = prim.optInt("indices", -1);
                if (posAcc < 0 || idxAcc < 0) continue;

                float[] pos = readFloats(posAcc, accessors, bufferViews, bin);
                float[] nrm = nrmAcc >= 0 ? readFloats(nrmAcc, accessors, bufferViews, bin) : null;
                float[] uv = uvAcc >= 0 ? readFloats(uvAcc, accessors, bufferViews, bin) : null;
                int[] idx = readIndices(idxAcc, accessors, bufferViews, bin);
                if (pos == null || idx == null || idx.length < 3) continue;
                int vcount = pos.length / 3;

                // mode 转成三角形索引序列
                int mode = prim.optInt("mode", 4);
                int[] tri = toTriangles(idx, mode);
                if (tri == null || tri.length < 3) continue;

                // 应用节点世界矩阵（位置用完整 4x4，法线只取 3x3 旋转部分并归一化）
                transform(pos, wm, true);
                if (nrm != null && nrm.length >= vcount * 3) transform(nrm, wm, false);
                else nrm = computeNormals(pos, vcount, tri);

                // 材质 -> factor / 贴图 / 混合模式
                MeshProto proto = new MeshProto();
                int miMat = prim.optInt("material", -1);
                if (materials != null && miMat >= 0 && miMat < materials.length()) {
                    readMaterial(materials.optJSONObject(miMat), textures, images, bufferViews, bin, proto);
                }

                // 顶点数超过 u16 上限时按三角形分块，每块 <= 65535 顶点
                int before = out.parts.size();
                addParts(out, pos, nrm, uv, vcount, tri, proto);
                partTotal += out.parts.size() - before;
            }
        }
        Diag.log("GLB 解析 " + tag + " primitives=" + primTotal
                + " 跳过压缩=" + dracoSkip + " parts=" + partTotal);
    }

    private static void visitNode(int idx, float[] parent, JSONArray nodes,
                                  float[][] world, boolean[] visiting, int depth) {
        if (idx < 0 || idx >= nodes.length() || depth > 256) return;
        if (visiting[idx] || world[idx] != null) return; // 防环 / 已算过
        visiting[idx] = true;
        JSONObject node = nodes.optJSONObject(idx);
        if (node != null) {
            float[] local = IDENTITY;
            if (node.has("matrix")) {
                JSONArray ma = node.optJSONArray("matrix");
                if (ma != null && ma.length() == 16) {
                    local = new float[16];
                    for (int i = 0; i < 16; i++) local[i] = (float) ma.optDouble(i);
                }
            } else {
                float[] t = jsonVec3(node.optJSONArray("translation"));
                float[] q = jsonVec4(node.optJSONArray("rotation"), new float[]{0, 0, 0, 1});
                float[] s = jsonVec3(node.optJSONArray("scale"));
                if (s == null) s = new float[]{1, 1, 1};
                local = composeTRS(t, q, s);
            }
            world[idx] = mul(parent, local);
            JSONArray children = node.optJSONArray("children");
            if (children != null) for (int i = 0; i < children.length(); i++)
                visitNode(children.optInt(i), world[idx], nodes, world, visiting, depth + 1);
        }
        visiting[idx] = false;
    }

    // ---------- 材质 ----------

    private static class MeshProto {
        int blendMode = 0;                         // OPAQUE
        float[] factor = {1f, 1f, 1f, 1f};
        Bitmap bitmap = null;
    }

    private static void readMaterial(JSONObject mat, JSONArray textures, JSONArray images,
                                     JSONArray bufferViews, byte[] bin, MeshProto out) {
        if (mat == null) return;
        String alpha = mat.optString("alphaMode", "OPAQUE");
        if ("BLEND".equalsIgnoreCase(alpha)) out.blendMode = 1;
        else if ("MASK".equalsIgnoreCase(alpha)) out.blendMode = 2;

        JSONArray f = null;
        JSONObject pbr = mat.optJSONObject("pbrMetallicRoughness");
        if (pbr != null) f = pbr.optJSONArray("baseColorFactor");
        if (f != null && f.length() >= 4) {
            out.factor[0] = (float) f.optDouble(0, 1);
            out.factor[1] = (float) f.optDouble(1, 1);
            out.factor[2] = (float) f.optDouble(2, 1);
            out.factor[3] = (float) f.optDouble(3, 1);
        }
        // unlit(MToon 常见)材质不要被环境光压暗：因子给足，靠贴图本色
        JSONObject ext = mat.optJSONObject("extensions");
        if (ext != null && (ext.has("KHR_materials_unlit") || ext.has("VRMC_materials_mtoon"))) {
            out.factor[0] = out.factor[1] = out.factor[2] = 1f;
        }

        JSONObject texRef = pbr == null ? null : pbr.optJSONObject("baseColorTexture");
        int texIdx = texRef == null ? -1 : texRef.optInt("index", -1);
        if (texIdx < 0 || textures == null || texIdx >= textures.length()) return;
        JSONObject tex = textures.optJSONObject(texIdx);
        if (tex == null) return;
        int imgIdx = tex.optInt("source", -1);
        if (images == null || imgIdx < 0 || imgIdx >= images.length()) return;
        JSONObject img = images.optJSONObject(imgIdx);
        if (img == null) return;
        try {
            byte[] png = null;
            if (img.has("bufferView")) {
                png = readImageBufferView(img.optInt("bufferView"), bufferViews, bin);
            } else if (img.has("uri")) {
                String uri = img.optString("uri");
                if (uri.startsWith("data:")) {
                    int comma = uri.indexOf(',');
                    if (comma > 0) {
                        String meta = uri.substring(0, comma);
                        byte[] raw = android.util.Base64.decode(uri.substring(comma + 1), android.util.Base64.DEFAULT);
                        if (meta.contains("base64")) png = raw;
                    }
                }
            }
            if (png != null) out.bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
        } catch (Throwable t) {
            Diag.log("!! GLB 贴图解码失败: " + t.getMessage());
        }
    }

    // ---------- accessor 读取 ----------

    private static int compSize(int ct) {
        switch (ct) {
            case 5120: case 5121: return 1; // BYTE / UBYTE
            case 5122: case 5123: return 2; // SHORT / USHORT
            case 5125: case 5126: return 4; // UINT / FLOAT
            default: return 4;
        }
    }

    private static int compCount(String type) {
        if (type == null) return 1;
        switch (type) {
            case "SCALAR": return 1;
            case "VEC2": return 2;
            case "VEC3": return 3;
            case "VEC4": return 4;
            case "MAT4": return 16;
            default: return 1;
        }
    }

    // 从 accessor 读浮点数组，处理 bufferView byteStride（interleaved 顶点）
    private static float[] readFloats(int accIdx, JSONArray accessors, JSONArray bufferViews, byte[] bin) {
        if (accessors == null || accIdx < 0 || accIdx >= accessors.length()) return null;
        JSONObject acc = accessors.optJSONObject(accIdx);
        if (acc == null) return null;
        int ct = acc.optInt("componentType", 5126);
        int n = compCount(acc.optString("type"));
        int count = acc.optInt("count", 0);
        int bvIdx = acc.optInt("bufferView", -1);
        if (count <= 0 || bvIdx < 0 || bufferViews == null || bvIdx >= bufferViews.length()) return null;
        JSONObject bv = bufferViews.optJSONObject(bvIdx);
        if (bv == null) return null;
        int bvOff = bv.optInt("byteOffset", 0);
        int stride = bv.optInt("byteStride", compSize(ct) * n);
        int accOff = acc.optInt("byteOffset", 0);
        int cs = compSize(ct);
        if (stride < cs * n) return null;
        ByteBuffer bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[count * n];
        for (int i = 0; i < count; i++) {
            int base = bvOff + accOff + i * stride;
            if (base + cs * n > bin.length) break;
            for (int c = 0; c < n; c++) {
                int p = base + c * cs;
                float v;
                switch (ct) {
                    case 5126: v = bb.getFloat(p); break;
                    case 5123: v = bb.getShort(p) & 0xFFFF; break;
                    case 5125: v = bb.getInt(p) & 0xFFFFFFFFL; break;
                    case 5121: v = bb.get(p) & 0xFF; break;
                    case 5122: v = bb.getShort(p); break;
                    case 5120: v = bb.get(p); break;
                    default: v = 0;
                }
                out[i * n + c] = v;
            }
        }
        return out;
    }

    private static int[] readIndices(int accIdx, JSONArray accessors, JSONArray bufferViews, byte[] bin) {
        if (accessors == null || accIdx < 0 || accIdx >= accessors.length()) return null;
        JSONObject acc = accessors.optJSONObject(accIdx);
        if (acc == null) return null;
        int ct = acc.optInt("componentType", 5123);
        int count = acc.optInt("count", 0);
        int bvIdx = acc.optInt("bufferView", -1);
        if (count <= 0 || bvIdx < 0 || bufferViews == null || bvIdx >= bufferViews.length()) return null;
        JSONObject bv = bufferViews.optJSONObject(bvIdx);
        if (bv == null) return null;
        int bvOff = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0);
        int stride = bv.optInt("byteStride", compSize(ct));
        int cs = compSize(ct);
        if (bvOff + count * cs > bin.length) count = Math.max(0, (bin.length - bvOff) / cs);
        ByteBuffer bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            int p = bvOff + i * stride;
            if (p + cs > bin.length) break;
            switch (ct) {
                case 5121: out[i] = bb.get(p) & 0xFF; break;
                case 5123: out[i] = bb.getShort(p) & 0xFFFF; break;
                case 5125: out[i] = (int) (bb.getInt(p) & 0xFFFFFFFFL); break;
                default: out[i] = bb.getShort(p) & 0xFFFF;
            }
        }
        return out;
    }

    // image.bufferView 切片（不下标越界）
    private static byte[] readImageBufferView(int bvIdx, JSONArray bufferViews, byte[] bin) {
        if (bufferViews == null || bvIdx < 0 || bvIdx >= bufferViews.length() || bin == null) return null;
        JSONObject bv = bufferViews.optJSONObject(bvIdx);
        if (bv == null) return null;
        int off = bv.optInt("byteOffset", 0);
        int len = bv.optInt("byteLength", bin.length - off);
        if (off < 0 || len <= 0 || off + len > bin.length) return null;
        byte[] out = new byte[len];
        System.arraycopy(bin, off, out, 0, len);
        return out;
    }

    // ---------- 索引 / 法线 / 顶点输出 ----------

    private static int[] toTriangles(int[] idx, int mode) {
        if (mode == 4) return idx;
        List<Integer> out = new ArrayList<>();
        if (mode == 5) { // TRIANGLE_STRIP
            for (int i = 2; i < idx.length; i++) {
                if ((i & 1) == 0) { out.add(idx[i - 2]); out.add(idx[i - 1]); out.add(idx[i]); }
                else { out.add(idx[i - 1]); out.add(idx[i - 2]); out.add(idx[i]); }
            }
        } else if (mode == 6) { // TRIANGLE_FAN
            for (int i = 2; i < idx.length; i++) { out.add(idx[0]); out.add(idx[i - 1]); out.add(idx[i]); }
        } else return null;
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    private static float[] computeNormals(float[] pos, int vcount, int[] tri) {
        float[] n = new float[vcount * 3];
        for (int i = 0; i < tri.length; i += 3) {
            int a = tri[i] * 3, b = tri[i + 1] * 3, c = tri[i + 2] * 3;
            if (a < 0 || c + 2 >= pos.length) continue;
            float ux = pos[b] - pos[a], uy = pos[b + 1] - pos[a + 1], uz = pos[b + 2] - pos[a + 2];
            float vx = pos[c] - pos[a], vy = pos[c + 1] - pos[a + 1], vz = pos[c + 2] - pos[a + 2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            n[a] += nx; n[a + 1] += ny; n[a + 2] += nz;
            n[b] += nx; n[b + 1] += ny; n[b + 2] += nz;
            n[c] += nx; n[c + 1] += ny; n[c + 2] += nz;
        }
        for (int i = 0; i < vcount; i++) normalize(n, i * 3);
        return n;
    }

    private static void normalize(float[] v, int off) {
        float l = (float) Math.sqrt(v[off] * v[off] + v[off + 1] * v[off + 1] + v[off + 2] * v[off + 2]);
        if (l > 1e-8f) { v[off] /= l; v[off + 1] /= l; v[off + 2] /= l; }
        else v[off + 1] = 1f;
    }

    // 按三角形累积顶点，单个 part 顶点数逼近 u16 上限就 flush 出新块。
    // 三角形以三元组整体处理，绝不跨块，因此不会丢面。
    private static void addParts(MeshParts out, float[] pos, float[] nrm, float[] uv,
                                 int vcount, int[] tri, MeshProto proto) {
        final int MAX = 65000;
        Map<Integer, Integer> remap = new HashMap<>();
        List<Integer> used = new ArrayList<>();   // 局部槽位 -> 全局顶点索引
        List<Integer> localIdx = new ArrayList<>(); // 三角形引用的局部槽位序列

        for (int t = 0; t + 2 < tri.length; t += 3) {
            int g0 = tri[t], g1 = tri[t + 1], g2 = tri[t + 2];
            if (g0 < 0 || g1 < 0 || g2 < 0 || g0 >= vcount || g1 >= vcount || g2 >= vcount) continue;
            // 本三角形会引入几个新顶点
            int fresh = (remap.containsKey(g0) ? 0 : 1)
                    + (remap.containsKey(g1) ? 0 : 1)
                    + (remap.containsKey(g2) ? 0 : 1);
            if (!used.isEmpty() && used.size() + fresh > MAX) {
                emitPart(out, pos, nrm, uv, used, localIdx, proto);
                remap.clear(); used.clear(); localIdx.clear();
            }
            localIdx.add(localOf(remap, used, g0));
            localIdx.add(localOf(remap, used, g1));
            localIdx.add(localOf(remap, used, g2));
        }
        if (!used.isEmpty()) emitPart(out, pos, nrm, uv, used, localIdx, proto);
    }

    private static int localOf(Map<Integer, Integer> remap, List<Integer> used, int g) {
        Integer li = remap.get(g);
        if (li == null) { li = used.size(); remap.put(g, li); used.add(g); }
        return li;
    }

    private static void emitPart(MeshParts out, float[] pos, float[] nrm, float[] uv,
                                 List<Integer> used, List<Integer> localIdx, MeshProto proto) {
        int cv = used.size(), ci = localIdx.size();
        if (cv == 0 || ci < 3) return;
        MeshParts.Part p = new MeshParts.Part();
        p.vertexCount = cv;
        p.indexCount = ci;
        p.blendMode = proto.blendMode;
        p.factor = proto.factor.clone();
        // 一个 primitive 拆成多块时共用同一贴图对象；渲染链路上传后会 recycle，
        // 故每块给独立副本，避免第二块用到已回收的 Bitmap 崩溃。
        // 材质没有贴图(KTX2/纯色)时给 1x1 白底，保证烘焙与渲染都有合法纹理。
        Bitmap src = proto.bitmap;
        if (src == null || src.isRecycled()) {
            src = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            src.setPixel(0, 0, 0xFFFFFFFF);
        }
        try {
            p.bitmap = src.copy(src.getConfig() != null ? src.getConfig() : Bitmap.Config.ARGB_8888, false);
        } catch (Throwable t) {
            p.bitmap = src;
        }
        p.texW = p.bitmap.getWidth();
        p.texH = p.bitmap.getHeight();

        byte[] vb = new byte[cv * 8 * 4];
        ByteBuffer vbb = ByteBuffer.wrap(vb).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < cv; i++) {
            int g = used.get(i);
            vbb.putFloat(pos[g * 3]); vbb.putFloat(pos[g * 3 + 1]); vbb.putFloat(pos[g * 3 + 2]);
            if (nrm != null && nrm.length >= (g + 1) * 3) {
                vbb.putFloat(nrm[g * 3]); vbb.putFloat(nrm[g * 3 + 1]); vbb.putFloat(nrm[g * 3 + 2]);
            } else { vbb.putFloat(0); vbb.putFloat(1); vbb.putFloat(0); }
            if (uv != null && uv.length >= (g + 1) * 2) {
                vbb.putFloat(uv[g * 2]); vbb.putFloat(uv[g * 2 + 1]);
            } else { vbb.putFloat(0); vbb.putFloat(0); }
        }
        p.vertexBuf = ByteBuffer.wrap(vb).order(ByteOrder.LITTLE_ENDIAN);
        byte[] ib = new byte[ci * 2];
        ByteBuffer ibb = ByteBuffer.wrap(ib).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < ci; i++) ibb.putShort((short) (int) localIdx.get(i));
        p.indexBuf = ByteBuffer.wrap(ib).order(ByteOrder.LITTLE_ENDIAN);
        out.parts.add(p);
    }

    // ---------- 矩阵 ----------

    private static final float[] IDENTITY = {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1
    };

    private static float[] mul(float[] a, float[] b) {
        float[] o = new float[16];
        for (int c = 0; c < 4; c++)
            for (int r = 0; r < 4; r++) {
                float s = 0;
                for (int k = 0; k < 4; k++) s += a[k * 4 + r] * b[c * 4 + k];
                o[c * 4 + r] = s;
            }
        return o;
    }

    private static float[] composeTRS(float[] t, float[] q, float[] s) {
        float x = q == null ? 0 : q[0], y = q == null ? 0 : q[1];
        float z = q == null ? 0 : q[2], w = q == null ? 1 : q[3];
        float x2 = x + x, y2 = y + y, z2 = z + z;
        float xx = x * x2, xy = x * y2, xz = x * z2;
        float yy = y * y2, yz = y * z2, zz = z * z2;
        float wx = w * x2, wy = w * y2, wz = w * z2;
        float[] r = new float[16];
        r[0] = (1 - (yy + zz)) * s[0]; r[1] = (xy + wz) * s[0]; r[2] = (xz - wy) * s[0]; r[3] = 0;
        r[4] = (xy - wz) * s[1]; r[5] = (1 - (xx + zz)) * s[1]; r[6] = (yz + wx) * s[1]; r[7] = 0;
        r[8] = (xz + wy) * s[2]; r[9] = (yz - wx) * s[2]; r[10] = (1 - (xx + yy)) * s[2]; r[11] = 0;
        r[12] = t == null ? 0 : t[0]; r[13] = t == null ? 0 : t[1]; r[14] = t == null ? 0 : t[2]; r[15] = 1;
        return r;
    }

    private static void transform(float[] v, float[] m, boolean isPos) {
        int n = v.length / (isPos ? 3 : 3);
        for (int i = 0; i < n; i++) {
            int o = i * 3;
            float x = v[o], y = v[o + 1], z = v[o + 2];
            v[o] = m[0] * x + m[4] * y + m[8] * z + (isPos ? m[12] : 0);
            v[o + 1] = m[1] * x + m[5] * y + m[9] * z + (isPos ? m[13] : 0);
            v[o + 2] = m[2] * x + m[6] * y + m[10] * z + (isPos ? m[14] : 0);
            if (!isPos) normalize(v, o);
        }
    }

    private static float[] jsonVec3(JSONArray a) {
        if (a == null || a.length() < 3) return null;
        return new float[]{(float) a.optDouble(0), (float) a.optDouble(1), (float) a.optDouble(2)};
    }

    private static float[] jsonVec4(JSONArray a, float[] def) {
        if (a == null || a.length() < 4) return def;
        return new float[]{(float) a.optDouble(0), (float) a.optDouble(1),
                (float) a.optDouble(2), (float) a.optDouble(3)};
    }
}
