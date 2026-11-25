package com.example.sensorar;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.opengl.GLUtils;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

// GLES 渲染：EasyAR 相机帧当背景，上面画高精度模型。
// 锚定/地面估计沿用原来的逻辑，只是把软件画三角形换成 GPU。
public class GLRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "NVR/GL";

    // 背景：一个四边形，用 imageProjection 把相机图铺满
    private static final String BG_VS =
        "attribute vec2 aPos;" +
        "uniform mat4 uProj;" +
        "varying vec2 vUV;" +
        "void main(){" +
        "  vec2 t = (aPos + 1.0) * 0.5;" +
        "  vUV = vec2(t.x, 1.0 - t.y);" +
        "  gl_Position = uProj * vec4(aPos, 0.0, 1.0);" +
        "}";
    private static final String BG_FS =
        "precision mediump float;" +
        "uniform sampler2D uTex;" +
        "varying vec2 vUV;" +
        "void main(){" +
        "  gl_FragColor = texture2D(uTex, vUV);" +
        "}";

    // 模型：pos3 + normal3 + uv2
    private static final String M_VS =
        "attribute vec3 aPos;" +
        "attribute vec3 aNormal;" +
        "attribute vec2 aUV;" +
        "uniform mat4 uMVP;" +
        "uniform mat4 uModel;" +
        "varying vec2 vUV;" +
        "varying vec3 vNormal;" +
        "void main(){" +
        "  vUV = aUV;" +
        "  vNormal = mat3(uModel) * aNormal;" +
        "  gl_Position = uMVP * vec4(aPos, 1.0);" +
        "}";
    private static final String M_FS =
        "precision mediump float;" +
        "uniform sampler2D uTex;" +
        "uniform vec3 uLight;" +
        "uniform float uAmbient;" +
        "uniform float uExposure;" +
        "uniform vec4 uFactor;" +       // rgb=色调, a>1.5 表示眼睛(不受环境压暗,保留高光)
        "varying vec2 vUV;" +
        "varying vec3 vNormal;" +
        "void main(){" +
        "  vec4 c = texture2D(uTex, vUV);" +
        "  if (c.a < 0.02) discard;" +
        "  float d = max(0.0, dot(normalize(vNormal), normalize(uLight)));" +
        "  float shade = uAmbient + (1.0 - uAmbient) * d;" +
        "  vec3 rgb;" +
        "  if (uFactor.a > 1.5) {" +    // 眼睛: 满亮+轻微提亮,高光清晰
        "    rgb = c.rgb * uFactor.rgb * (0.72 + 0.18 * d);" +
        "  } else {" +
        "    rgb = c.rgb * uFactor.rgb * shade * uExposure;" +
        "  }" +
        "  gl_FragColor = vec4(rgb, c.a);" +
        "}";

    private int bgProg, mProg;
    private int bg_aPos, bg_uProj, bg_uTex;
    private int m_aPos, m_aNormal, m_aUV, m_uMVP, m_uModel, m_uTex, m_uLight, m_uAmbient, m_uFactor, m_uExposure;

    private int[] bgBuf = new int[1];       // 背景四边形 VBO
    private int[] camTex = new int[1];      // 相机帧纹理
    private boolean camTexInit = false;
    // 分层网格：每个部件独立的 VBO/索引/纹理句柄
    private int[] partVbo;      // 顶点缓冲, 每部件一个
    private int[] partIbo;      // 索引缓冲
    private int[] partTex;      // 纹理
    private int[] partOrder;    // 绘制顺序(不透明在前, 半透明在后)
    private int partCount = 0;

    private final float[] quad = {
        -1,-1,  1,-1,  -1,1,
        -1,1,   1,-1,   1,1
    };
    private final float[] bgMatrix = new float[16];   // imageProjection 列主序

    private MeshParts mesh;
    private AREngine ar;
    private int viewW = 0, viewH = 0;

    // 自动发现模型：不再写死文件名。启动时扫描 assets 和外部 models 目录，
    // 找到的第一个合法 NVR2 模型即为默认；用户可随时切换。
    private java.util.List<ModelScanner.Entry> models = new java.util.ArrayList<>();
    private volatile ModelScanner.Entry pendingModel = null;  // GL 线程里待加载的模型
    private volatile boolean pendingRescan = false;           // 外部目录增删了文件，需要重扫
    private String curModel = "无";

    // 硬加载(GLB/VRM 运行时解析)的性能观测：解析耗时 + 三角面数。
    // 超过阈值时给 MainActivity 打一个"建议烘焙"标记，让用户决定是否转私有格式缓存。
    private volatile boolean bakeSuggested = false;
    private volatile ModelScanner.Entry bakeEntry = null;
    private volatile long lastLoadCostMs = 0;
    private volatile int lastTriangles = 0;
    private static final long BAKE_COST_THRESHOLD_MS = 900;   // 解析超过 900ms 视为卡
    private static final int BAKE_TRI_THRESHOLD = 120000;     // 或三角面超过 12 万

    public void requestSwitchModel(ModelScanner.Entry e) { this.pendingModel = e; }
    public void requestRescan() { this.pendingRescan = true; }
    public java.util.List<ModelScanner.Entry> availableModels() {
        return new java.util.ArrayList<>(models);
    }
    public String currentModel() { return curModel; }

    // 主线程轮询：是否有模型建议烘焙成缓存。消费后自动清除，避免重复弹窗。
    public boolean consumeBakeSuggestion() {
        if (!bakeSuggested) return false;
        bakeSuggested = false;
        return true;
    }
    public ModelScanner.Entry getBakeEntry() { return bakeEntry; }
    public long getLastLoadCostMs() { return lastLoadCostMs; }
    public int getLastTriangles() { return lastTriangles; }

    private volatile String pendingBakeSwitch = null;  // 烘焙完成后要切到的缓存文件名

    // 烘焙进度回调（UI 线程执行）
    public interface BakeListener {
        void onProgress(int percent, String stage, long elapsedMs, long remainMs);
        void onDone(boolean ok, String msg);
    }
    private volatile BakeListener bakeListener = null;
    private final android.os.Handler bakeHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    public void setBakeListener(BakeListener l) { this.bakeListener = l; }

    // 主线程调用：后台线程把 GLB 烘焙成 NVR2 缓存，成功后自动重扫并切到缓存版。
    // 进度分四段加权：读文件 0-5%、解析 5-60%、序列化 60-95%、写盘 95-100%。
    public void bakeAndSwitch(final ModelScanner.Entry entry) {
        if (entry == null) return;
        final android.content.Context ctx = currentContext;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final long t0 = System.currentTimeMillis();
                // percent/stage 由后台算好，统一 post 回主线程回调 UI
                final int[] lastPct = {0};
                final class Rpt {
                    void p(final int pct, final String stage) {
                        if (pct <= lastPct[0]) return;   // 进度只增不减
                        lastPct[0] = pct;
                        final long elapsed = System.currentTimeMillis() - t0;
                        // 用已完成比例线性外推总时长，剩余 = 总 - 已用；前期样本少时偏保守
                        final long remain = pct >= 3 ? (long) (elapsed * (100.0 - pct) / pct) : -1;
                        bakeHandler.post(new Runnable() {
                            @Override public void run() {
                                BakeListener l = bakeListener;
                                if (l != null) l.onProgress(pct, stage, elapsed, remain);
                            }
                        });
                    }
                    void done(final boolean ok, final String msg) {
                        bakeHandler.post(new Runnable() {
                            @Override public void run() {
                                BakeListener l = bakeListener;
                                if (l != null) l.onDone(ok, msg);
                            }
                        });
                    }
                }
                final Rpt rpt = new Rpt();
                try {
                    Diag.log("开始烘焙缓存: " + entry.label);
                    rpt.p(2, "读取文件");
                    byte[] bytes = ModelScanner.readEntry(ctx, entry);

                    // 解析 GLB：5% -> 60%
                    Progress parseP = new Progress() {
                        @Override public void onProgress(int done, int total, String stage) {
                            int pct = 5 + (int) (55L * done / Math.max(1, total));
                            rpt.p(pct, "解析模型 " + done + "/" + total);
                        }
                    };
                    MeshParts mp = (ModelScanner.isGlbEntry(entry))
                            ? MeshParts.fromBytes(bytes, entry.label, parseP)
                            : MeshParts.fromBytes(bytes, entry.label);
                    if (mp == null || mp.parts.isEmpty()) throw new Exception("解析出的网格为空");

                    // 序列化：60% -> 95%
                    Progress saveP = new Progress() {
                        @Override public void onProgress(int done, int total, String stage) {
                            int pct = 60 + (int) (35L * done / Math.max(1, total));
                            rpt.p(pct, "写入缓存 " + done + "/" + total);
                        }
                    };
                    byte[] nvr2 = MeshParts.toNvr2Bytes(mp, saveP);

                    rpt.p(96, "保存文件");
                    String cacheName = ModelScanner.cacheNameFor(new File(entry.file).getName());
                    File dest = new File(ModelScanner.externalDir(ctx), cacheName);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(dest);
                    try { fos.write(nvr2); fos.flush(); } finally { fos.close(); }
                    Diag.log("烘焙完成 " + cacheName + " " + nvr2.length + "B -> " + dest.getAbsolutePath());
                    pendingBakeSwitch = cacheName;
                    pendingRescan = true;
                    rpt.p(100, "完成");
                    rpt.done(true, cacheName);
                } catch (Throwable t) {
                    Diag.log("!! 烘焙失败: " + Diag.stack(t));
                    String why;
                    if (t instanceof OutOfMemoryError) {
                        why = "内存不足，请先关闭其它应用重试";
                    } else {
                        String m = t.getMessage();
                        why = (m == null || m.isEmpty())
                                ? t.getClass().getSimpleName()   // 别再只显示"未知错误"
                                : m;
                    }
                    rpt.done(false, why);
                }
            }
        }, "nvr-bake").start();
    }

    // 锚定 / 地面
    private final float[] pos = new float[3];
    private volatile boolean anchorSet = false;
    private float yaw = 0f;
    private float[] cloud = new float[0];
    private float groundY = 0f;
    private boolean groundYSet = false;
    private float camWorldY = Float.NaN;
    private float groundEstimate = Float.NaN;

    private final float[] view = new float[16];
    private final float[] projection = new float[16];
    private boolean hasView = false;
    private final float[] mvp = new float[16];
    private final float[] model = new float[16];

    private volatile int fps = 0;
    private long fpsFrames = 0, fpsStart = 0;
    private long totalFrames = 0;

    public void setAREngine(AREngine engine) { this.ar = engine; }
    public int getFps() { return fps; }
    public long getFrameCount() { return totalFrames; }
    public int getTracking() { return lastTracking; }
    private volatile int lastTracking = -1;

    public void clearAnchor() {
        anchorSet = false; hasView = false;
        groundYSet = false; camWorldY = Float.NaN; groundEstimate = Float.NaN;
    }

    // 光线传感器容易被手掌挡住、读数忽高忽低(开着灯也可能只有20lux),
    // 不能拿它压暗模型。曝光改由摄像头画面的真实亮度驱动,见 updateExposureFromFrame。
    public void setLight(float lux) {
        // 保留接口供 HUD 显示读数; 曝光不再由 lux 决定
    }
    private volatile float ambient = 0.42f;
    private volatile float exposure = 0.95f;

    // 用相机画面 Y(亮度)均值定曝光: 场景亮模型就亮, 场景暗模型略暗但仍看得清。
    // 范围收窄到 0.85~1.05, 不再出现传感器误判导致的死黑。
    private void updateExposureFromFrame(float luma) {
        float target = 0.85f + Math.min(1f, Math.max(0f, luma / 255f)) * 0.20f;
        exposure += (target - exposure) * 0.08f;   // 平滑,防闪烁
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        bgProg = bgProg(BG_VS, BG_FS);
        mProg = bgProg(M_VS, M_FS);
        bg_aPos = GLES20.glGetAttribLocation(bgProg, "aPos");
        bg_uProj = GLES20.glGetUniformLocation(bgProg, "uProj");
        bg_uTex = GLES20.glGetUniformLocation(bgProg, "uTex");
        m_aPos = GLES20.glGetAttribLocation(mProg, "aPos");
        m_aNormal = GLES20.glGetAttribLocation(mProg, "aNormal");
        m_aUV = GLES20.glGetAttribLocation(mProg, "aUV");
        m_uMVP = GLES20.glGetUniformLocation(mProg, "uMVP");
        m_uModel = GLES20.glGetUniformLocation(mProg, "uModel");
        m_uTex = GLES20.glGetUniformLocation(mProg, "uTex");
        m_uLight = GLES20.glGetUniformLocation(mProg, "uLight");
        m_uAmbient = GLES20.glGetUniformLocation(mProg, "uAmbient");
        m_uFactor = GLES20.glGetUniformLocation(mProg, "uFactor");
        m_uExposure = GLES20.glGetUniformLocation(mProg, "uExposure");

        GLES20.glGenBuffers(1, bgBuf, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bgBuf[0]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.length * 4,
                floatBuf(quad), GLES20.GL_STATIC_DRAW);

        // 自动扫描并加载第一个找到的模型
        models = ModelScanner.scan(currentContext);
        if (!models.isEmpty()) {
            loadModel(models.get(0));
        } else {
            Diag.log("!! 没找到任何合法模型(.mesh/.glb/.vrm)，请把模型放进 assets 或外部 models 目录");
        }
        GLES20.glGenTextures(1, camTex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, camTex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        camTexInit = true;
        Diag.log("GLES 初始化完成");
    }

    private android.content.Context currentContext;
    public void setContext(android.content.Context c) { this.currentContext = c; }

    // 在 GL 线程里调用：释放旧模型的 GPU 资源，再加载新的
    private void loadModel(ModelScanner.Entry entry) {
        if (entry == null) return;
        if (partVbo != null) GLES20.glDeleteBuffers(partVbo.length, partVbo, 0);
        if (partIbo != null) GLES20.glDeleteBuffers(partIbo.length, partIbo, 0);
        if (partTex != null) GLES20.glDeleteTextures(partTex.length, partTex, 0);
        partCount = 0;
        byte[] bytes;
        try {
            bytes = ModelScanner.readEntry(currentContext, entry);
        } catch (Throwable t) {
            Diag.log("!! 读取模型失败 " + entry.label + ": " + t.getMessage());
            return;
        }
        long t0 = System.currentTimeMillis();
        mesh = MeshParts.fromBytes(bytes, entry.label);
        lastLoadCostMs = System.currentTimeMillis() - t0;
        if (mesh != null && !mesh.parts.isEmpty()) {
            partCount = mesh.parts.size();
            // 统计三角面数，配合解析耗时判断是否需要烘焙成私有缓存
            int tris = 0;
            for (MeshParts.Part pp : mesh.parts) tris += pp.indexCount / 3;
            lastTriangles = tris;
            boolean glb = ModelScanner.isGlbEntry(entry);
            boolean cached = new File(entry.file).getName().toLowerCase().endsWith(".nvr2");
            if (glb && !cached
                    && (lastLoadCostMs >= BAKE_COST_THRESHOLD_MS || tris >= BAKE_TRI_THRESHOLD)) {
                bakeSuggested = true;
                bakeEntry = entry;
                Diag.log("模型较重(解析" + lastLoadCostMs + "ms, 三角" + tris + ")，可考虑烘焙缓存");
            }
            partVbo = new int[partCount];
            partIbo = new int[partCount];
            partTex = new int[partCount];
            partOrder = new int[partCount];
            GLES20.glGenBuffers(partCount, partVbo, 0);
            GLES20.glGenBuffers(partCount, partIbo, 0);
            GLES20.glGenTextures(partCount, partTex, 0);
            int opaqueN = 0;
            for (int i = 0; i < partCount; i++) {
                MeshParts.Part p = mesh.parts.get(i);
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, partVbo[i]);
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,
                        p.vertexCount * 8 * 4, p.vertexBuf, GLES20.GL_STATIC_DRAW);
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, partIbo[i]);
                GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER,
                        p.indexCount * 2, p.indexBuf, GLES20.GL_STATIC_DRAW);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, partTex[i]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                // 按贴图实际尺寸决定采样方式：小贴图(<=256)多为像素画，用最近邻保持硬边；
                // 大贴图是精细绘制，用线性插值更平滑。不再依赖写死的文件名判断。
                int texFilter = (p.texW > 0 && p.texW <= 256 && p.texH <= 256)
                        ? GLES20.GL_NEAREST : GLES20.GL_LINEAR;
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, texFilter);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, texFilter);
                Bitmap bm = p.bitmap;
                if (bm != null && !bm.isRecycled()) {
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bm, 0);
                    // 不 recycle：同一张贴图被多个 part 共享（readMaterial 去重），
                    // 且白底图是全局共享的。手动回收会让后续 part 拿到已回收 bitmap 变黑。
                    // 去重后贴图总量很小（<=10 张 512²），交给 GC 即可。
                    p.bitmap = null;
                }
            }
            for (int i = 0; i < partCount; i++) {
                if (mesh.parts.get(i).blendMode != 1) partOrder[opaqueN++] = i;
            }
            for (int i = 0; i < partCount; i++) {
                if (mesh.parts.get(i).blendMode == 1) partOrder[opaqueN++] = i;
            }
            curModel = entry.label;
            Diag.log("模型加载完成 " + entry.label + " 部件=" + partCount + " 采样="
                    + (mesh.parts.get(0).texW <= 256 ? "最近邻" : "线性"));
        }
    }

    // GL 线程里处理切换/重扫请求（必须和渲染在同一线程，否则 GL 资源会错乱）
    private void applyPendingModel() {
        if (pendingRescan) {
            pendingRescan = false;
            models = ModelScanner.scan(currentContext);
            Diag.log("已重新扫描模型，当前 " + models.size() + " 个");
        }
        // 烘焙完成后自动切到新生成的缓存版
        if (pendingBakeSwitch != null) {
            String name = pendingBakeSwitch;
            pendingBakeSwitch = null;
            for (ModelScanner.Entry e : models) {
                if (new File(e.file).getName().equals(name)) {
                    Diag.log("自动切换到烘焙缓存: " + e.label);
                    loadModel(e);
                    break;
                }
            }
        }
        ModelScanner.Entry req = pendingModel;
        if (req != null) {
            pendingModel = null;
            loadModel(req);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        viewW = w; viewH = h;
        GLES20.glViewport(0, 0, w, h);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        applyPendingModel();
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        if (viewW == 0 || viewH == 0) return;

        float aspect = (float) viewW / viewH;
        int rot = screenRotation();
        AREngine.FrameData fd = ar != null ? ar.peek(aspect, rot) : null;
        if (fd != null) onFrame(fd, rot);

        // 背景：关深度写入，用 imageProjection 铺相机帧
        if (camTexInit) {
            GLES20.glDepthMask(false);
            GLES20.glUseProgram(bgProg);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, camTex[0]);
            GLES20.glUniform1i(bg_uTex, 0);
            if (hasImageProjection) {
                System.arraycopy(imageProjection, 0, bgMatrix, 0, 16);
            } else {
                Matrix.setIdentityM(bgMatrix, 0);
            }
            GLES20.glUniformMatrix4fv(bg_uProj, 1, false, bgMatrix, 0);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bgBuf[0]);
            GLES20.glEnableVertexAttribArray(bg_aPos);
            GLES20.glVertexAttribPointer(bg_aPos, 2, GLES20.GL_FLOAT, false, 0, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
            GLES20.glDepthMask(true);
        }

        // 模型
        if (hasView && anchorSet && mesh != null && partCount > 0) {
            drawModel();
        }
        countFrame();
    }

    private void drawModel() {
        GLES20.glUseProgram(mProg);
        // mvp = projection * view * model
        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0);
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, pos[0], pos[1], pos[2]);
        Matrix.rotateM(model, 0, (float) Math.toDegrees(yaw), 0f, 1f, 0f);
        Matrix.multiplyMM(mvp, 0, mvp, 0, model, 0);

        GLES20.glUniformMatrix4fv(m_uMVP, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(m_uModel, 1, false, model, 0);
        GLES20.glUniform3f(m_uLight, 0.3f, 0.9f, 0.6f);
        GLES20.glUniform1f(m_uAmbient, ambient);
        GLES20.glUniform1f(m_uExposure, exposure);
        GLES20.glUniform1i(m_uTex, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

        final int stride = 8 * 4;
        // 不透明部件：写深度；半透明部件：关深度写入避免自遮挡
        for (int k = 0; k < partCount; k++) {
            int i = partOrder[k];
            MeshParts.Part p = mesh.parts.get(i);
            boolean transparent = p.blendMode == 1;
            GLES20.glDepthMask(!transparent);
            if (transparent) {
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            } else {
                GLES20.glDisable(GLES20.GL_BLEND);
            }
            GLES20.glUniform4f(m_uFactor, p.factor[0], p.factor[1], p.factor[2], p.factor[3]);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, partTex[i]);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, partVbo[i]);
            GLES20.glEnableVertexAttribArray(m_aPos);
            GLES20.glVertexAttribPointer(m_aPos, 3, GLES20.GL_FLOAT, false, stride, 0);
            GLES20.glEnableVertexAttribArray(m_aNormal);
            GLES20.glVertexAttribPointer(m_aNormal, 3, GLES20.GL_FLOAT, false, stride, 3 * 4);
            GLES20.glEnableVertexAttribArray(m_aUV);
            GLES20.glVertexAttribPointer(m_aUV, 2, GLES20.GL_FLOAT, false, stride, 6 * 4);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, partIbo[i]);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, p.indexCount, GLES20.GL_UNSIGNED_SHORT, 0);
        }
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private final float[] imageProjection = new float[16];
    private boolean hasImageProjection = false;

    private void onFrame(AREngine.FrameData fd, int rot) {
        totalFrames++;
        if (fd.tracking != lastTracking) {
            lastTracking = fd.tracking;
            Diag.log("跟踪状态=" + fd.tracking);
        }
        if (fd.hasPose && fd.cameraTransform != null && fd.view != null) {
            camWorldY = fd.cameraTransform[7];
            setCameraTransform(fd.cameraTransform, fd.view);
        }
        if (fd.projection != null) System.arraycopy(fd.projection, 0, projection, 0, 16);
        if (fd.imageProjection != null) {
            // EasyAR 给的是行主序，转列主序给 GLES
            float[] r = fd.imageProjection;
            for (int i = 0; i < 4; i++)
                for (int j = 0; j < 4; j++)
                    imageProjection[i * 4 + j] = r[j * 4 + i];
            hasImageProjection = true;
        }
        if (fd.pointCloud != null) cloud = fd.pointCloud;
        if (!Float.isNaN(camWorldY)) groundEstimate = estimateGround(camWorldY);
        if (fd.pixels != null) uploadCamera(fd.pixels, fd.width, fd.height, fd.format);
    }

    private int camW = 0, camH = 0;
    private int[] camPixels = null;
    private java.nio.IntBuffer camDirect = null;   // 复用的 DirectBuffer，不再每帧分配
    private void uploadCamera(byte[] data, int w, int h, int format) {
        if (!camTexInit) return;
        try {
            if (camW != w || camH != h || camPixels == null) {
                camW = w; camH = h;
                camPixels = new int[w * h];
                camDirect = ByteBuffer.allocateDirect(w * h * 4)
                        .order(ByteOrder.nativeOrder()).asIntBuffer();
            }
            // 从 Y 平面抽样场景真实亮度(约4000点,开销极小),驱动模型曝光
            int wh = w * h;
            int step = Math.max(1, wh / 4000);
            long lsum = 0; int lcnt = 0;
            for (int idx = 0; idx < wh; idx += step) { lsum += data[idx] & 0xFF; lcnt++; }
            if (lcnt > 0) updateExposureFromFrame((float) lsum / lcnt);

            // 和之前软件渲染一致：format 4 是 YV12，其余 YUV 是 NV21
            yuvToArgb(data, w, h, format == 4, camPixels);
            camDirect.clear();
            camDirect.put(camPixels);
            camDirect.flip();
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, camTex[0]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, camDirect);
        } catch (Throwable t) {
            Diag.log("!! 相机纹理上传失败: " + t.getMessage());
        }
    }

    private static void yuvToArgb(byte[] yuv, int w, int h, boolean yv12, int[] out) {
        final int wh = w * h, halfW = w >> 1, halfH = h >> 1;
        if (yuv.length < wh + halfW * halfH * 2) return;
        int vBase, uBase, uvStep;
        if (yv12) {
            vBase = wh; uBase = wh + halfW * halfH; uvStep = 1;
        } else {
            vBase = wh; uBase = wh + 1; uvStep = 2;   // NV21
        }
        int p = 0, yp = 0;
        for (int j = 0; j < h; j++) {
            int uvRow = (j >> 1) * halfW;
            for (int i = 0; i < w; i++) {
                int y = yuv[yp++] & 0xFF;
                int uv = uvRow + (i >> 1);
                int v = (yuv[vBase + uv * uvStep] & 0xFF) - 128;
                int u = (yuv[uBase + uv * uvStep] & 0xFF) - 128;
                int c = y - 16;
                int r = (298 * c + 409 * v + 128) >> 8;
                int g = (298 * c - 100 * u - 208 * v + 128) >> 8;
                int b = (298 * c + 516 * u + 128) >> 8;
                // 用 GL_RGBA 上传，小端内存要排成 R,G,B,A，打包时反着放
                out[p++] = 0xFF000000 | (cl(b) << 16) | (cl(g) << 8) | cl(r);
            }
        }
    }
    private static int cl(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    private float estimateGround(float camY) {
        if (cloud == null || cloud.length < 9) return Float.NaN;
        float lo = camY - 2.5f, hi = camY - 0.6f;
        java.util.List<Float> ys = new java.util.ArrayList<>();
        for (int i = 0; i < cloud.length; i += 3) {
            float y = cloud[i + 1];
            if (Float.isNaN(y)) continue;
            if (y > lo && y < hi) ys.add(y);
        }
        if (ys.size() < 8) return Float.NaN;
        java.util.Collections.sort(ys);
        return ys.get(ys.size() / 5);
    }

    private void setCameraTransform(float[] ct, float[] sdkView) {
        if (sdkView == null || sdkView.length < 16) return;
        for (float v : sdkView) if (Float.isNaN(v) || Float.isInfinite(v)) return;
        System.arraycopy(sdkView, 0, view, 0, 16);
        hasView = true;
        if (anchorSet || ct == null || ct.length < 16) return;
        float cx = ct[3], cy = ct[7], cz = ct[11];
        float fx = -ct[2], fy = -ct[6], fz = -ct[10];
        float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (fl > 0.0001f) { fx /= fl; fy /= fl; fz /= fl; }
        if (!groundYSet) {
            if (!Float.isNaN(groundEstimate)) groundY = groundEstimate;
            else if (!Float.isNaN(cy)) groundY = cy - 1.5f;
            groundYSet = true;
        }
        float px = cx + fx * 2.2f;
        float pz = cz + fz * 2.2f;
        pos[0] = px; pos[1] = groundY; pos[2] = pz;
        yaw = (float) Math.atan2(cx - px, cz - pz);
        anchorSet = true;
        Diag.log("模型放置 world=(" + String.format("%.2f,%.2f,%.2f", pos[0], pos[1], pos[2])
                + ") 地面=" + String.format("%.2f", groundY));
    }

    private int screenRotation() {
        android.view.Display d = ((android.view.WindowManager) currentContext
                .getSystemService(android.content.Context.WINDOW_SERVICE)).getDefaultDisplay();
        switch (d.getRotation()) {
            case android.view.Surface.ROTATION_90: return 90;
            case android.view.Surface.ROTATION_180: return 180;
            case android.view.Surface.ROTATION_270: return 270;
            default: return 0;
        }
    }

    private void countFrame() {
        fpsFrames++;
        long now = System.currentTimeMillis();
        if (fpsStart == 0) fpsStart = now;
        long span = now - fpsStart;
        if (span >= 1000) {
            fps = (int) (fpsFrames * 1000L / span);
            fpsFrames = 0; fpsStart = now;
        }
    }

    private static int bgProg(String vs, String fs) {
        int v = shader(GLES20.GL_VERTEX_SHADER, vs);
        int f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) Diag.log("!! 着色器链接失败: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }
    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) Diag.log("!! 着色器编译失败: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private static java.nio.FloatBuffer floatBuf(float[] a) {
        java.nio.FloatBuffer fb = ByteBuffer.allocateDirect(a.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(a); fb.flip();
        return fb;
    }
    private static java.nio.IntBuffer intBuf(int[] a) {
        java.nio.IntBuffer ib = ByteBuffer.allocateDirect(a.length * 4)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        ib.put(a); ib.flip();
        return ib;
    }
}
