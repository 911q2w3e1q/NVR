package com.example.sensorar;

import android.app.Activity;

import cn.easyar.CalibrationDownloader;
import cn.easyar.CalibrationDownloadStatus;
import cn.easyar.CallbackScheduler;
import cn.easyar.DelayedCallbackScheduler;
import cn.easyar.FunctorOfVoidFromCalibrationDownloadStatusAndOptionalOfString;
import cn.easyar.Image;
import cn.easyar.InputFrame;
import cn.easyar.InputFrameToOutputFrameAdapter;
import cn.easyar.Matrix44F;
import cn.easyar.OutputFrame;
import cn.easyar.OutputFrameBuffer;
import cn.easyar.RefBase;

//  EasyAR 的相机和位姿，按官方 HelloARMotionTracking 的管线接
//  关键：帧不放在回调线程处理，而是缓冲起来，等渲染时主动取
public class AREngine {

    public interface Callback {
        void onReady(boolean ok, String msg);
    }

    private final Activity activity;
    private Callback callback;

    private CalibrationDownloader calibration;
    // 标定回调对象要一直持有，别被 GC
    private FunctorOfVoidFromCalibrationDownloadStatusAndOptionalOfString calibCallback;
    private final CallbackScheduler immediate;

    private final DelayedCallbackScheduler scheduler = new DelayedCallbackScheduler();
    private InputFrameToOutputFrameAdapter adapter;
    private OutputFrameBuffer frameBuffer;
    private cn.easyar.MotionTrackerCameraDevice camera;

    private boolean started = false;
    private int capacity = 0;

    public AREngine(Activity activity) {
        this.activity = activity;
        this.immediate = cn.easyar.ImmediateCallbackScheduler.getDefault();
    }

    // 先下载标定文件再开相机。少了这步 VIO 起不来
    public void prepare(final Callback cb) {
        this.callback = cb;
        Diag.log("下载标定文件...");
        calibration = new CalibrationDownloader();
        calibCallback = new FunctorOfVoidFromCalibrationDownloadStatusAndOptionalOfString() {
            @Override
            public void invoke(int status, String error) {
                final String err = error;
                final boolean ok = (status == CalibrationDownloadStatus.Successful
                        || status == CalibrationDownloadStatus.NotModified);
                Diag.log("标定下载 status=" + status + " ok=" + ok + " err=" + err);
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        disposeDownloader();
                        if (cb != null) cb.onReady(ok, err);
                    }
                });
            }
        };
        calibration.download(10000, immediate, calibCallback);
    }

    public boolean isAvailable() {
        return cn.easyar.MotionTrackerCameraDevice.isAvailable();
    }

    public boolean isStarted() { return started; }

    public int qualityLevel() {
        try { return cn.easyar.MotionTrackerCameraDevice.getQualityLevel(); }
        catch (Throwable t) {
            Diag.log("!! qualityLevel 异常: " + t.getMessage());
            return -1;
        }
    }

    public boolean start() {
        if (started) return true;
        if (!isAvailable()) {
            Diag.log("!! MotionTracker isAvailable=false, quality=" + qualityLevel());
            return false;
        }
        // 之前 stop 过但没 dispose，管线对象还在，重建前先清干净
        releaseCamera();
        try {
            adapter = InputFrameToOutputFrameAdapter.create();
            frameBuffer = OutputFrameBuffer.create();
            capacity = frameBuffer.bufferRequirement() + 3;

            camera = new cn.easyar.MotionTrackerCameraDevice();
            camera.setFrameRateType(cn.easyar.MotionTrackerCameraDeviceFPS.Camera_FPS_30);
            camera.setFrameResolutionType(cn.easyar.MotionTrackerCameraDeviceResolution.Resolution_640);
            // 默认 Anchor 最耗电还会自动改位姿；SLAM 就够点云+平面检测，村民走路用得上
            camera.setTrackingMode(cn.easyar.MotionTrackerCameraDeviceTrackingMode.SLAM);
            camera.setBufferCapacity(capacity);

            camera.inputFrameSource().connect(adapter.input());
            adapter.output().connect(frameBuffer.input());

            boolean ok = camera.start();
            Diag.log("camera.start()=" + ok + " quality=" + qualityLevel()
                    + " buffer=" + capacity);
            started = ok;
            return ok;
        } catch (Throwable t) {
            Diag.log("!! start 异常: " + Diag.stack(t));
            return false;
        }
    }

    // EasyAR 返回的矩阵是行主序，Android Matrix 要列主序，转一下
    private static float[] toColumnMajor(float[] r) {
        float[] c = new float[16];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                c[i * 4 + j] = r[j * 4 + i];
        return c;
    }

    // 行主序矩阵 * 列向量（官方 BGRenderer.mul 的写法）
    private static void mulRow(float[] m, float[] v, float[] out) {
        for (int i = 0; i < 4; i++) {
            float s = 0;
            for (int k = 0; k < 4; k++) s += m[i * 4 + k] * v[k];
            out[i] = s;
        }
    }

    // 在渲染线程调用：驱动调度器，返回最新一帧
    // aspect/screenRotation 用来让 SDK 生成和屏幕匹配的投影矩阵
    public FrameData peek(float aspect, int screenRotation) {
        if (!started) return null;
        try {
            while (scheduler.runOne()) { }
        } catch (Throwable t) {
            Diag.log("!! scheduler 异常: " + Diag.stack(t));
        }
        OutputFrame of = null;
        InputFrame in = null;
        Image img = null;
        cn.easyar.CameraParameters cp = null;
        try {
            of = frameBuffer.peek();
            if (of == null) return null;
            in = of.inputFrame();
            if (in == null) { safeDispose(of); return null; }

            FrameData fd = new FrameData();
            fd.index = in.index();
            fd.tracking = in.trackingStatus();
            fd.hasPose = in.hasSpatialInformation();

            // 相机内参 -> 投影矩阵。官方 projection 是行主序，转成列主序给 Android 用
            cp = in.cameraParameters();
            if (cp != null) {
                try {
                    // 3D 物体用官方 projection，带屏幕旋转，直接把相机坐标投到屏幕 NDC。
                    // 背景才用 imageProjection，两者各走各的，不能串接。
                    Matrix44F pm = cp.projection(0.01f, 500f, aspect, screenRotation, true, false);
                    if (pm != null && pm.data != null && pm.data.length >= 16) {
                        fd.projection = toColumnMajor(pm.data);
                    }
                    // 背景图变换矩阵，保持行主序（按官方 mul 用）
                    Matrix44F im = cp.imageProjection(aspect, screenRotation, true, false);
                    if (im != null && im.data != null && im.data.length >= 16) {
                        fd.imageProjection = im.data.clone();
                    }
                } catch (Throwable t) {
                    Diag.log("!! 投影矩阵生成失败: " + t.getMessage());
                }
            }

            // 已扫描点云 + 地面高度（用本帧内参，过期前取）
            updateSpatial(cp, aspect, screenRotation);
            fd.pointCloud = cachedCloud;
            fd.ground = cachedGround;

            // view = inverse(cameraTransform)。先转列主序再求逆，等价于官方 inverseMatrix 后转置
            if (fd.hasPose) {
                Matrix44F ct = in.cameraTransform();
                if (ct != null && ct.data != null && ct.data.length >= 16) {
                    fd.cameraTransform = ct.data.clone(); // 行主序，算锚点用
                    float[] ctCol = toColumnMajor(ct.data);
                    fd.view = new float[16];
                    // 跟踪没稳时位姿矩阵退化，求逆会失败，属正常阶段，静默跳过
                    if (!android.opengl.Matrix.invertM(fd.view, 0, ctCol, 0)) {
                        fd.hasPose = false;
                    }
                }
            }

            img = in.image();
            if (img != null && img.width() > 0) {
                cn.easyar.Buffer buf = img.buffer();
                if (buf != null) {
                    int size = buf.size();
                    fd.pixels = new byte[size];
                    buf.copyToByteArray(fd.pixels);
                    fd.width = img.width();
                    fd.height = img.height();
                    fd.format = img.format();
                    safeDispose(buf);
                }
            }
            if (firstFrameIndex < 0) firstFrameIndex = fd.index;
            if (fd.index == firstFrameIndex) {
                String ct = fd.cameraTransform != null
                        ? String.format("ct=(%.2f,%.2f,%.2f)", fd.cameraTransform[3],fd.cameraTransform[7],fd.cameraTransform[11])
                        : "ct=null";
                String pv = fd.projection != null
                        ? String.format("proj=[%.2f,%.2f,%.2f,%.2f]", fd.projection[0],fd.projection[5],fd.projection[8],fd.projection[9])
                        : "proj=null";
                String im = fd.imageProjection != null
                        ? String.format("imgP=[%.2f,%.2f,%.2f,%.2f]", fd.imageProjection[0],fd.imageProjection[5],fd.imageProjection[12],fd.imageProjection[13])
                        : "imgP=null";
                Diag.log("首帧矩阵: " + ct + " " + pv + " " + im
                        + " rot=" + screenRotation + " aspect=" + String.format("%.2f", aspect)
                        + " size=" + (fd.width) + "x" + fd.height);
            }
            return fd;
        } catch (Throwable t) {
            Diag.log("!! peek 异常: " + Diag.stack(t));
            return null;
        } finally {
            safeDispose(cp);
            safeDispose(img);
            safeDispose(in);
            safeDispose(of);
        }
    }

    private int firstFrameIndex = -1;

    // 点云/地面探测降频：点云计算重，不必每帧取
    private static final int CLOUD_INTERVAL = 15;
    private int cloudTick = 0;
    private float[] cachedCloud = new float[0];       // 世界坐标 xyz 依次排列
    private float[] cachedGround = null;              // 最近一次地面高度点 (x,y,z)

    // 拿已扫描的点云（世界坐标）和屏幕中心的地面交点。cp 是本帧内参，调用时仍有效
    private void updateSpatial(cn.easyar.CameraParameters cp, float aspect, int rotation) {
        if (camera == null) return;
        cloudTick++;
        if (cloudTick % CLOUD_INTERVAL == 0) {
            try {
                java.util.ArrayList<cn.easyar.Vec3F> pts = camera.getLocalPointsCloud();
                if (pts != null && !pts.isEmpty()) {
                    float[] arr = new float[pts.size() * 3];
                    for (int i = 0; i < pts.size(); i++) {
                        float[] d = pts.get(i).data;
                        arr[i*3] = d[0]; arr[i*3+1] = d[1]; arr[i*3+2] = d[2];
                    }
                    cachedCloud = arr;
                }
            } catch (Throwable t) {
                Diag.log("!! 点云获取异常: " + t.getMessage());
            }
        }
        // 屏幕中心 -> 图像坐标 -> 打水平面，拿地面高度
        try {
            cn.easyar.Vec2F img = null;
            if (cp != null) {
                img = cp.imageCoordinatesFromScreenCoordinates(
                        aspect, rotation, true, false, new cn.easyar.Vec2F(0.5f, 0.5f));
            }
            cn.easyar.Vec2F p = (img != null) ? img : new cn.easyar.Vec2F(0.5f, 0.5f);
            java.util.ArrayList<cn.easyar.Vec3F> hits = camera.hitTestAgainstHorizontalPlane(p);
            if (hits != null && !hits.isEmpty()) {
                float[] d = hits.get(0).data;
                cachedGround = new float[]{d[0], d[1], d[2]};
            }
        } catch (Throwable t) {
            Diag.log("!! 地面探测异常: " + t.getMessage());
        }
    }

    private static void safeDispose(RefBase r) {
        if (r == null) return;
        try { r.dispose(); }
        catch (Throwable t) { Diag.log("!! dispose 异常: " + t.getMessage()); }
    }

    private void disposeDownloader() {
        if (calibration == null) return;
        safeDispose(calibration);
        calibration = null;
    }

    // 停相机并释放这次启动创建的管线对象，供下次 start 重建
    private void releaseCamera() {
        started = false;
        if (camera != null) {
            try { camera.stop(); }
            catch (Throwable t) { Diag.log("!! camera.stop 异常: " + t.getMessage()); }
        }
        safeDispose(camera);
        safeDispose(adapter);
        safeDispose(frameBuffer);
        camera = null;
        adapter = null;
        frameBuffer = null;
    }

    // 切后台用：只停相机省电，不 dispose 整机管线；onResume 会重新 start
    public void stop() {
        releaseCamera();
    }

    public void dispose() {
        releaseCamera();
        safeDispose(scheduler);
        // immediate 是全局单例，不 dispose
        disposeDownloader();
        calibCallback = null;
    }

    public static class FrameData {
        public int index;
        public int tracking;
        public boolean hasPose;
        public float[] cameraTransform; // 行主序，算锚点用
        public float[] view;            // 列主序，inverse(cameraTransform)
        public float[] projection;      // 列主序，来自 SDK 相机内参
        public float[] imageProjection; // 行主序，背景图四角变换
        public float[] pointCloud;      // 世界坐标点云 xyz 依次排列
        public float[] ground;          // 地面交点世界坐标 (x,y,z)
        public byte[] pixels;
        public int width;
        public int height;
        public int format;
    }
}
