package com.example.sensorar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.opengl.Matrix;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

// 相机帧铺背景，上面画一个威龙干员皮套（Canvas 软件纹理映射）
public class OverlayView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    private Model3D model3d;             // 真 3D 模型（烘焙成静态三角形）
    // 模型原始高约 1.8 米，缩到差不多真人站地上的大小
    private static final float BODY_SCALE = 0.95f;

    // 皮套在世界里的状态
    private final float[] pos = new float[3];   // 脚底世界坐标
    private volatile boolean anchorSet = false;
    private float yaw = 0f;                      // 朝向（弧度）
    private float gait = 0f;                     // 步态相位
    private float[] target = null;
    private float[] cloud = new float[0];
    private float groundY = 0f;                  // 固定的地面高度，不再乱跟
    private boolean groundYSet = false;
    private float camWorldY = Float.NaN;          // 相机世界高度，从位姿平移拿
    private float groundEstimate = Float.NaN;     // 从点云估出的地面高度
    private long lastTick = 0;
    private static final float WALK_SPEED = 0.55f;

    private static class TriProj {
        float x0,y0, x1,y1, x2,y2;   // 屏幕坐标
        float depth;
        int color;
    }
    // 对象池：三角面复用同一批对象，不再每帧 new，省掉 GC 卡顿
    private TriProj[] triPool = null;
    private static final Comparator<TriProj> TRI_SORT = (a, b) -> Float.compare(b.depth, a.depth);
    private final android.graphics.Path triPath = new android.graphics.Path();
    private final float[] pa = new float[3], pb = new float[3], pc = new float[3];
    private final float[] tvin = new float[4];
    private final float[] tvout = new float[4];

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint triPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] view = new float[16];
    private final float[] projection = new float[16];
    private final float[] imageProjection = new float[16];
    private boolean hasImageProjection = false;
    private final float[] vp = new float[16];
    private final float[] mvp = new float[16];
    private final float[] model = new float[16];
    private final float[] partM = new float[16];
    private final float[] rotM = new float[16];
    private final float[] worldM = new float[16];
    private final float[] vin = new float[4];
    private final float[] vout = new float[4];
    private volatile boolean hasView = false;
    private volatile float ambient = 0.55f;

    private Bitmap camBitmap = null;
    private int[] camPixels = null;          // 复用的 YUV->RGB 像素缓冲
    private int camW = 0, camH = 0;
    private final Object bitmapLock = new Object();
    private final Paint bgPaint = new Paint();

    private AREngine ar;
    private SurfaceHolder holder;
    private Thread drawThread;
    private volatile boolean running = false;
    private long frames = 0;
    private volatile long frameCount = 0;
    private long lastFrameLog = 0;
    private volatile int lastTracking = -1;
    // 真实帧率：只数真正画到屏幕的帧，每秒更新一次
    private volatile int fps = 0;
    private long fpsFrames = 0;
    private long fpsWindowStart = 0;

    public OverlayView(Context c) { this(c, null); }
    public OverlayView(Context c, AttributeSet a) {
        super(c, a);
        holder = getHolder();
        holder.addCallback(this);
        holder.setFormat(PixelFormat.TRANSPARENT);
        setZOrderOnTop(true);
        paint.setStyle(Paint.Style.FILL);
        hudPaint.setColor(Color.WHITE);
        hudPaint.setTextSize(22f);
        triPaint.setStyle(Paint.Style.FILL);
        triPaint.setAntiAlias(false);
        model3d = Model3D.load(c, "model_static.glb");
    }

    public void setAREngine(AREngine engine) { this.ar = engine; }

    private int screenRotation() {
        android.view.Display d = ((android.view.WindowManager) getContext()
                .getSystemService(android.content.Context.WINDOW_SERVICE)).getDefaultDisplay();
        switch (d.getRotation()) {
            case android.view.Surface.ROTATION_90:  return 90;
            case android.view.Surface.ROTATION_180: return 180;
            case android.view.Surface.ROTATION_270: return 270;
            default: return 0;
        }
    }

    public long getFrameCount() { return frameCount; }
    public int getTracking() { return lastTracking; }
    public int getFps() { return fps; }

    // 每秒数一次真正画到屏幕的帧，得到真实 FPS
    private void countFrame() {
        fpsFrames++;
        long now = System.currentTimeMillis();
        if (fpsWindowStart == 0) fpsWindowStart = now;
        long span = now - fpsWindowStart;
        if (span >= 1000) {
            fps = (int) (fpsFrames * 1000L / span);
            fpsFrames = 0;
            fpsWindowStart = now;
        }
    }

    public void clearAnchor() {
        anchorSet = false;
        hasView = false;
        target = null;
        groundYSet = false;
        camWorldY = Float.NaN;
        groundEstimate = Float.NaN;
    }

    public void setLight(float lux) {
        float v = (float) (Math.log10(Math.max(1f, lux) + 1f) / 3.3f);
        ambient = Math.max(0.25f, Math.min(0.95f, v));
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        running = true;
        drawThread = new Thread(this, "draw");
        drawThread.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int width, int height) { }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        running = false;
        try { if (drawThread != null) drawThread.join(); } catch (InterruptedException ignored) { }
        drawThread = null;
    }

    @Override
    public void run() {
        while (running) {
            Canvas canvas = null;
            try {
                if (ar != null) {
                    int w = getWidth(), h = getHeight();
                    float aspect = (h > 0) ? (float) w / h : 1f;
                    AREngine.FrameData fd = ar.peek(aspect, screenRotation());
                    if (fd != null) onFrame(fd);
                }
                canvas = holder.lockCanvas();
                if (canvas != null) {
                    drawFigure(canvas);
                    holder.unlockCanvasAndPost(canvas);
                    countFrame();
                }
            } catch (Throwable t) {
                Diag.log("!! 绘制线程异常: " + Diag.stack(t));
                if (canvas != null) {
                    try { holder.unlockCanvasAndPost(canvas); } catch (Throwable ignored) { }
                }
            }
            try { Thread.sleep(16); } catch (InterruptedException ignored) { }
        }
    }

    private void onFrame(AREngine.FrameData fd) {
        frameCount++;
        if (frameCount == 1) Diag.log(">> 第一帧已取到");
        if (fd.tracking != lastTracking) {
            lastTracking = fd.tracking;
            Diag.log("跟踪状态=" + fd.tracking + " (0未跟/1受限/2正常)");
        }
        if (fd.hasPose && fd.cameraTransform != null && fd.view != null) {
            // 行主序平移在 3,7,11；相机世界高度取 Y
            camWorldY = fd.cameraTransform[7];
            setCameraTransform(fd.cameraTransform, fd.view);
        }
        if (fd.projection != null && fd.projection.length >= 16) {
            System.arraycopy(fd.projection, 0, projection, 0, 16);
        }
        if (fd.imageProjection != null && fd.imageProjection.length >= 16) {
            System.arraycopy(fd.imageProjection, 0, imageProjection, 0, 16);
            hasImageProjection = true;
        }
        if (fd.pointCloud != null) cloud = fd.pointCloud;
        // 地面从点云估，不拿屏幕中心射线（朝上拍会打到天花板，人就飘上去）
        if (!Float.isNaN(camWorldY)) groundEstimate = estimateGround(camWorldY);
        if (fd.pixels != null) setCameraFrame(fd.pixels, fd.width, fd.height, fd.format);
        // 静态皮套：不走路、不摆臂，锚定后原地站着，朝向在锚定时就定死
        long now = System.currentTimeMillis();
        if (now - lastFrameLog > 2000) {
            lastFrameLog = now;
            Diag.log("帧=" + frameCount + " 点云=" + (cloud.length/3)
                    + " 地面=" + String.format("%.2f", groundY)
                    + " 皮套=(" + String.format("%.1f,%.1f,%.1f", pos[0],pos[1],pos[2]) + ")");
        }
    }

    // 从已扫描点云里找地面：相机下方 0.6~2.5 米、且成片的点，取偏低的一档。
    // 天花板在相机上方被排除，桌面/冰箱顶高于相机也基本排除。
    private float estimateGround(float camY) {
        if (cloud == null || cloud.length < 9) return Float.NaN;
        float lo = camY - 2.5f, hi = camY - 0.6f;
        List<Float> ys = new ArrayList<>();
        for (int i = 0; i < cloud.length; i += 3) {
            float y = cloud[i+1];
            if (Float.isNaN(y)) continue;
            if (y > lo && y < hi) ys.add(y);
        }
        if (ys.size() < 8) return Float.NaN;
        Collections.sort(ys);
        return ys.get(ys.size() / 5); // 20% 分位，偏低，代表地面而非台面
    }

    // 第一帧位姿到了就放置皮套，脚底落在地面高度
    private void setCameraTransform(float[] ct, float[] sdkView) {
        if (sdkView == null || sdkView.length < 16) return;
        for (float v : sdkView) if (Float.isNaN(v) || Float.isInfinite(v)) return;
        System.arraycopy(sdkView, 0, view, 0, 16);
        hasView = true;

        if (anchorSet || ct == null || ct.length < 16) return;
        float cx = ct[3], cy = ct[7], cz = ct[11];
        float fx = -ct[2], fy = -ct[6], fz = -ct[10];
        float fl = (float) Math.sqrt(fx*fx + fy*fy + fz*fz);
        if (fl > 0.0001f) { fx/=fl; fy/=fl; fz/=fl; }

        if (!groundYSet) {
            // 优先用点云估的地面；点云还不够就退回相机高度往下 1.5 米
            if (!Float.isNaN(groundEstimate)) groundY = groundEstimate;
            else if (!Float.isNaN(cy)) groundY = cy - 1.5f;
            groundYSet = true;
        }
        float px = cx + fx * 2.2f;
        float pz = cz + fz * 2.2f;
        pos[0] = px; pos[1] = groundY; pos[2] = pz;
        yaw = (float) Math.atan2(cx - px, cz - pz);
        gait = 0f;
        target = null;
        anchorSet = true;
        lastTick = System.currentTimeMillis();
        Diag.log("皮套放置 world=(" + String.format("%.2f,%.2f,%.2f", pos[0],pos[1],pos[2])
                + ") 地面=" + String.format("%.2f", groundY));
    }

    // 从和地面同高的点云点里挑目标，保证只在扫描过、且是地面的地方走
    private float[] pickTarget() {
        if (cloud == null || cloud.length < 6) return null;
        int n = cloud.length / 3;
        Random rnd = new Random();
        float bestX=0, bestZ=0, bestScore=-1f;
        for (int k = 0; k < 20; k++) {
            int i = rnd.nextInt(n);
            float x = cloud[i*3], y = cloud[i*3+1], z = cloud[i*3+2];
            if (Math.abs(y - groundY) > 0.25f) continue;  // 只要地面高度的点，不上桌不上冰箱
            float d = (float) Math.sqrt((x-pos[0])*(x-pos[0]) + (z-pos[2])*(z-pos[2]));
            if (d < 0.8f || d > 2.5f) continue;
            float score = d + rnd.nextFloat();
            if (score > bestScore) { bestScore=score; bestX=x; bestZ=z; }
        }
        return bestScore < 0 ? null : new float[]{bestX, groundY, bestZ};
    }

    private void updateWalk() {
        long now = System.currentTimeMillis();
        float dt = (now - lastTick) / 1000f;
        lastTick = now;
        if (dt <= 0 || dt > 0.1f) dt = 0.016f;

        if (target == null) target = pickTarget();
        boolean moving = false;
        if (target != null) {
            float dx = target[0] - pos[0], dz = target[2] - pos[2];
            float d = (float) Math.sqrt(dx*dx + dz*dz);
            if (d < 0.2f) {
                target = null;
            } else {
                float step = Math.min(WALK_SPEED * dt, d);
                pos[0] += dx / d * step;
                pos[2] += dz / d * step;
                pos[1] = groundY;   // 高度恒定，绝不上浮
                float wantYaw = (float) Math.atan2(dx, dz);
                float diff = wantYaw - yaw;
                while (diff > Math.PI) diff -= 2*Math.PI;
                while (diff < -Math.PI) diff += 2*Math.PI;
                yaw += diff * Math.min(1f, dt * 6f);
                gait += step * 7f;
                moving = true;
            }
        }
        if (!moving) {
            pos[1] = groundY;
            gait += dt * 1.0f;
        }
    }

    private void setCameraFrame(byte[] data, int w, int h, int format) {
        if (data == null || w <= 0 || h <= 0) return;
        try {
            // 尺寸变了才重建 bitmap 和缓冲，平时一直复用，避免每帧分配
            if (camW != w || camH != h || camBitmap == null || camPixels == null
                    || camBitmap.isRecycled()) {
                camW = w; camH = h;
                camPixels = new int[w * h];
                camBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            if (format == 2 || format == 3 || format == 4 || format == 5) {
                // 手写 YUV->RGB，不再走 JPEG 编码再解码那一大圈
                yuvToArgb(data, w, h, format == 4, camPixels);
            } else {
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(data);
                bb.order(java.nio.ByteOrder.nativeOrder());
                bb.asIntBuffer().get(camPixels);
            }
            camBitmap.setPixels(camPixels, 0, w, 0, 0, w, h);
        } catch (Throwable t) {
            Diag.log("!! 相机帧转换失败: " + Diag.stack(t));
        }
    }

    // YUV420 -> ARGB。yv12=true 是 Y/V/U 三平面，否则 NV21 的 Y + VU 交错
    private static void yuvToArgb(byte[] yuv, int w, int h, boolean yv12, int[] out) {
        final int wh = w * h;
        final int halfW = w >> 1, halfH = h >> 1;
        if (yuv.length < wh + halfW * halfH * 2) return;
        int vBase, uBase, uvStep;
        if (yv12) {
            vBase = wh; uBase = wh + halfW * halfH; uvStep = 1;
        } else {
            vBase = wh; uBase = wh + 1; uvStep = 2;
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
                out[p++] = 0xFF000000 | (clip(r) << 16) | (clip(g) << 8) | clip(b);
            }
        }
    }

    private static int clip(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    private static void mulRow(float[] m, float[] v, float[] out) {
        for (int i = 0; i < 4; i++) {
            float s = 0;
            for (int k = 0; k < 4; k++) s += m[i * 4 + k] * v[k];
            out[i] = s;
        }
    }

    private void drawBackground(Canvas canvas, Bitmap bg, int w, int h) {
        if (!hasImageProjection) {
            float scale = Math.max((float) w / bg.getWidth(), (float) h / bg.getHeight());
            int dw = Math.round(bg.getWidth() * scale);
            int dh = Math.round(bg.getHeight() * scale);
            canvas.drawBitmap(bg, new android.graphics.Rect(0,0,bg.getWidth(),bg.getHeight()),
                    new android.graphics.Rect((w-dw)/2,(h-dh)/2,(w-dw)/2+dw,(h-dh)/2+dh), bgPaint);
            return;
        }
        float[][] ndc = {{-1,-1},{1,-1},{1,1},{-1,1}};
        float[] dst = new float[8];
        float[] vec = new float[4], out = new float[4];
        boolean ok = true;
        for (int i = 0; i < 4; i++) {
            vec[0]=ndc[i][0]; vec[1]=ndc[i][1]; vec[2]=0f; vec[3]=1f;
            mulRow(imageProjection, vec, out);
            if (Math.abs(out[3]) < 0.000001f) { ok=false; break; }
            dst[i*2]   = (out[0]/out[3] + 1f)/2f * w;
            dst[i*2+1] = (1f - out[1]/out[3])/2f * h;
        }
        if (!ok) {
            canvas.drawBitmap(bg, new android.graphics.Rect(0,0,bg.getWidth(),bg.getHeight()),
                    new android.graphics.Rect(0,0,w,h), bgPaint);
            return;
        }
        float[] src = {0,bg.getHeight(), bg.getWidth(),bg.getHeight(),
                bg.getWidth(),0, 0,0};
        android.graphics.Matrix m = new android.graphics.Matrix();
        if (m.setPolyToPoly(src,0,dst,0,4)) {
            int saved = canvas.save();
            canvas.concat(m);
            canvas.drawBitmap(bg, 0, 0, bgPaint);
            canvas.restoreToCount(saved);
        }
    }

    private void drawHud(Canvas canvas, int w, int h) {
        if (w == 0 || h == 0) return;
        String s1 = "FPS=" + fps + " 追踪=" + lastTracking + " 锚定=" + anchorSet
                + " 朝向=" + String.format("%.0f", Math.toDegrees(yaw));
        String s2;
        if (projection != null && projection.length >= 16) {
            s2 = String.format("P=[%.2f %.2f %.2f %.2f] V移=[%.2f %.2f %.2f]",
                    projection[0], projection[5], projection[10], projection[14],
                    view.length >= 16 ? view[12] : 0,
                    view.length >= 16 ? view[13] : 0,
                    view.length >= 16 ? view[14] : 0);
        } else {
            s2 = "投影矩阵未就绪";
        }
        String s3 = String.format("地面=%.2f 估计=%.2f 点=%d",
                groundY, groundEstimate, cloud != null ? cloud.length / 3 : 0);
        canvas.drawText(s1, 16, 40, hudPaint);
        canvas.drawText(s2, 16, 70, hudPaint);
        canvas.drawText(s3, 16, 100, hudPaint);
    }

    private void drawFigure(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

        Bitmap bg;
        synchronized (bitmapLock) { bg = camBitmap; }
        if (bg != null && !bg.isRecycled()) {
            bgPaint.setFilterBitmap(true);
            drawBackground(canvas, bg, w, h);
        }
        // 诊断信息直接画在画面上，截图即可读到，不用翻日志
        drawHud(canvas, w, h);
        if (w == 0 || h == 0 || !hasView || !anchorSet || model3d == null) return;

        Matrix.multiplyMM(vp, 0, projection, 0, view, 0);

        // 模型脚底在 y=0，放到锚点位置，缩到合适大小。静态，不动。
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, pos[0], pos[1], pos[2]);
        Matrix.rotateM(model, 0, (float) Math.toDegrees(yaw), 0f, 1f, 0f);
        Matrix.scaleM(model, 0, BODY_SCALE, BODY_SCALE, BODY_SCALE);

        // 光源方向（世界坐标），给三角形上明暗
        float ll = (float) Math.sqrt(0.3f*0.3f + 0.9f*0.9f + 0.6f*0.6f);
        float lx = 0.3f/ll, ly = 0.9f/ll, lz = 0.6f/ll;

        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0);

        // 光照常量和 yaw 的三角函数提到循环外，别每个面重算
        float c = (float)Math.cos(yaw), s = (float)Math.sin(yaw);
        List<Model3D.Tri> tris = model3d.tris;
        int need = tris.size();
        if (triPool == null || triPool.length < need) {
            TriProj[] next = new TriProj[need];
            if (triPool != null) System.arraycopy(triPool, 0, next, 0, triPool.length);
            triPool = next;
        }
        int n = 0;
        for (int i = 0; i < need; i++) {
            Model3D.Tri tr = tris.get(i);
            if (!projectPoint(tr.x0,tr.y0,tr.z0, mvp, w, h, pa)) continue;
            if (!projectPoint(tr.x1,tr.y1,tr.z1, mvp, w, h, pb)) continue;
            if (!projectPoint(tr.x2,tr.y2,tr.z2, mvp, w, h, pc)) continue;
            // 面太小就跳过，省点开销
            float area = Math.abs((pb[0]-pa[0])*(pc[1]-pa[1]) - (pc[0]-pa[0])*(pb[1]-pa[1]));
            if (area < 1.5f) continue;

            // 法线转到世界方向算光照（模型只绕 Y 转，法线跟着转 yaw）
            float nwx = c*tr.nx + s*tr.nz;
            float nwy = tr.ny;
            float nwz = -s*tr.nx + c*tr.nz;
            float diff = Math.max(0f, nwx*lx + nwy*ly + nwz*lz);
            float shade = ambient + (1f - ambient) * diff * 0.75f;

            TriProj tp = triPool[n];
            if (tp == null) { tp = new TriProj(); triPool[n] = tp; }
            tp.x0=pa[0]; tp.y0=pa[1]; tp.x1=pb[0]; tp.y1=pb[1]; tp.x2=pc[0]; tp.y2=pc[1];
            tp.depth = (pa[2]+pb[2]+pc[2])/3f;
            tp.color = shadeColor(tr.color, shade);
            n++;
        }

        // 远的先画，近的盖住远的。只对实际用到的 n 个排序
        Arrays.sort(triPool, 0, n, TRI_SORT);
        for (int i = 0; i < n; i++) {
            TriProj tp = triPool[i];
            triPaint.setColor(tp.color);
            triPath.reset();
            triPath.moveTo(tp.x0, tp.y0);
            triPath.lineTo(tp.x1, tp.y1);
            triPath.lineTo(tp.x2, tp.y2);
            triPath.close();
            canvas.drawPath(triPath, triPaint);
        }

        frames++;
        if (frames % 250 == 0) Diag.log("绘制帧=" + frames + " 三角面=" + n + " FPS=" + fps);
    }

    // 模型顶点 -> 屏幕坐标，结果写进 out，不分配新数组。
    private boolean projectPoint(float x, float y, float z, float[] mvp, int w, int h, float[] out) {
        tvin[0]=x; tvin[1]=y; tvin[2]=z; tvin[3]=1f;
        Matrix.multiplyMV(tvout, 0, mvp, 0, tvin, 0);
        if (tvout[3] <= 0.0001f) return false;
        out[0] = (tvout[0]/tvout[3] + 1f)/2f*w;
        out[1] = (1f - tvout[1]/tvout[3])/2f*h;
        out[2] = tvout[2]/tvout[3];
        return true;
    }

    // 按明暗系数调颜色
    private static int shadeColor(int argb, float shade) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r = Math.max(0, Math.min(255, (int)(r*shade)));
        g = Math.max(0, Math.min(255, (int)(g*shade)));
        b = Math.max(0, Math.min(255, (int)(b*shade)));
        return 0xFF000000 | (r<<16) | (g<<8) | b;
    }
}
