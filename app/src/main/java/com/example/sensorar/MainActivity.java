package com.example.sensorar;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import cn.easyar.Engine;

import java.io.File;
import java.io.FileOutputStream;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private static final String TAG = "NVR";
    private static final int REQ_CAMERA = 1;
    private static final int REQ_IMPORT_MODEL = 2;

    private GLSurfaceView glView;
    private GLRenderer renderer;
    private TextView hud;
    private ScrollView diagScroll;

    private SensorManager sensorManager;
    private Sensor lightSensor;
    private volatile float lastLux = -1f;
    private String bakeOfferedFor = null;     // 已就哪个模型弹过烘焙建议（按文件路径区分）

    private AREngine ar;
    private final Handler hudHandler = new Handler(Looper.getMainLooper());

    // 启动状态：全部收敛到 onResume，避免 onCreate 和 onResume 重复拉起
    private boolean resumed = false;
    private boolean granted = false;
    private boolean calibrated = false;
    private boolean startInProgress = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(getApplicationContext()));

        setContentView(R.layout.activity_main);
        hud = findViewById(R.id.hud);
        diagScroll = findViewById(R.id.diag_scroll);
        Diag.init(this, (TextView) findViewById(R.id.diag_log), diagScroll);

        hud.setOnClickListener(v -> {
            diagScroll.setVisibility(diagScroll.getVisibility() == View.VISIBLE
                    ? View.GONE : View.VISIBLE);
            renderer.clearAnchor();
            Diag.log("重新锚定");
        });

        // 右下角：导入模型。系统文件选择器挑 .mesh/.glb/.vrm，复制进外部 models 目录后自动生效
        Button btnImport = findViewById(R.id.btn_import);
        btnImport.setOnClickListener(v -> {
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("*/*");
            try {
                startActivityForResult(it, REQ_IMPORT_MODEL);
            } catch (Throwable t) {
                Toast.makeText(this, "本机没有文件选择器", Toast.LENGTH_SHORT).show();
            }
        });

        // 右下角：切换模型。列表来自自动扫描，不写死任何模型名
        Button btnSwitch = findViewById(R.id.btn_switch_model);
        btnSwitch.setOnClickListener(v -> showModelPicker());

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        renderer = new GLRenderer();
        renderer.setContext(this);
        glView.setRenderer(renderer);
        FrameLayout container = findViewById(R.id.overlay_container);
        container.addView(glView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        Diag.log("光线传感器=" + (lightSensor != null));

        // 没配 Key 时直接提示怎么弄，省得对着黑屏猜。Key 与包名绑定。
        if (!EasyKey.CONFIGURED) {
            Diag.log("!! 未配置 EasyAR Key");
            new AlertDialog.Builder(this)
                    .setTitle("需要先配置 EasyAR Key")
                    .setMessage("本开源仓库不含任何 Key。\n\n"
                            + "1) 到 www.easyar.cn 注册账号\n"
                            + "2) 为包名 com.nvr.app 申请 Sense 个人版 Key\n"
                            + "3) 把 Key 填进 EasyKey.java 的 STR 再重新编译")
                    .setPositiveButton("知道了", null)
                    .show();
        }

        // 初始化耗时记下来，真卡启动了能看出来；官方要求在主线程调，不搬后台
        Diag.log("初始化 EasyAR...");
        long t0 = System.currentTimeMillis();
        boolean ok = false;
        if (EasyKey.CONFIGURED) {
            ok = Engine.initialize(this, EasyKey.KEY);
        }
        long cost = System.currentTimeMillis() - t0;
        Diag.log("Engine.initialize=" + ok + " 耗时=" + cost + "ms version=" + safeVersion());
        if (EasyKey.CONFIGURED && !ok) {
            String err = Engine.errorMessage();
            Diag.log("!! 初始化失败: " + err);
            new AlertDialog.Builder(this)
                    .setTitle("EasyAR 初始化失败")
                    .setMessage(err == null ? "请检查 license key 与包名是否匹配" : err)
                    .setPositiveButton("确定", null)
                    .show();
        }

        // 没配 Key 时不要碰 EasyAR 的 native 对象，否则 getDefault() 可能抛异常闪退
        if (EasyKey.CONFIGURED && ok) {
            try {
                ar = new AREngine(this);
                renderer.setAREngine(ar);
            } catch (Throwable t) {
                Diag.log("!! AREngine 创建失败: " + Diag.stack(t));
                ar = null;
            }
        }

        granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
        startHudLoop();
        Diag.log("onCreate 结束");
    }

    // 模型硬加载较慢时征询用户：是否烘焙成私有 NVR2 缓存
    private void offerBake() {
        final ModelScanner.Entry entry = renderer.getBakeEntry();
        if (entry == null) return;
        long ms = renderer.getLastLoadCostMs();
        int tris = renderer.getLastTriangles();
        new AlertDialog.Builder(this)
                .setTitle("模型加载较慢")
                .setMessage("当前模型「" + entry.label + "」采用实时解析（耗时约 " + ms
                        + "ms，" + tris + " 三角面），可能会卡顿。\n\n"
                        + "是否转换为内置格式缓存？转换过程稍慢，但之后每次打开都会很快，且不影响原文件。")
                .setPositiveButton("转换并加速", (dialog, which) -> {
                    Toast.makeText(this, "开始转换，请稍候…", Toast.LENGTH_SHORT).show();
                    renderer.bakeAndSwitch(entry);
                })
                .setNegativeButton("保持实时解析", null)
                .show();
    }

    // 弹出模型选择框，内容由 GLRenderer 自动扫描得到，没有硬编码
    private void showModelPicker() {
        final java.util.List<ModelScanner.Entry> list = renderer.availableModels();
        if (list.isEmpty()) {
            Toast.makeText(this, "没找到模型，请先把 .mesh/.glb/.vrm 放进 assets 或点导入", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            names[i] = list.get(i).label
                    + (list.get(i).source == ModelScanner.SRC_FILE ? " (外部)" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("选择模型 · 共" + list.size() + "个")
                .setSingleChoiceItems(names, -1, (dialog, which) -> {
                    renderer.requestSwitchModel(list.get(which));
                    renderer.clearAnchor();
                    Toast.makeText(this, "已切换：" + list.get(which).label, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                })
                .setNeutralButton("重新扫描", (dialog, which) -> renderer.requestRescan())
                .setNegativeButton("取消", null)
                .show();
    }

    // 接收文件选择器选中的 .mesh，复制进外部 models 目录
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT_MODEL || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        String name = queryDisplayName(uri);
        if (name == null || !ModelScanner.isModelName(name)) {
            // 拿不到合法名字时按 URI 末尾兜底，仍不行就给默认名再靠魔数校验
            String last = uri.getLastPathSegment();
            name = (last != null && ModelScanner.isModelName(last)) ? last : "imported.glb";
        }
        File dest = new File(ModelScanner.externalDir(this), name);
        java.io.InputStream in = null;
        java.io.FileOutputStream out = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) throw new Exception("无法打开所选文件");
            out = new java.io.FileOutputStream(dest);
            byte[] buf = new byte[8192]; int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            out.flush();
            Diag.log("导入模型 " + name + " -> " + dest.getAbsolutePath() + " (" + dest.length() + "B)");
            // 校验魔数，不是合法模型就删掉（NVR2 / GLB 都接受）
            if (!ModelScanner.isModelFile(dest)) {
                boolean del = dest.delete();
                Toast.makeText(this, "不是合法的模型文件(.mesh/.glb/.vrm)" + (del ? "，已删除" : ""), Toast.LENGTH_LONG).show();
            } else {
                renderer.requestRescan();
                Toast.makeText(this, "导入成功：" + name + "，点切换模型选它", Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            Diag.log("!! 导入失败: " + Diag.stack(t));
            Toast.makeText(this, "导入失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignore) { }
            if (out != null) try { out.close(); } catch (Throwable ignore) { }
        }
    }

    // 从 content:// URI 查询文件显示名
    private String queryDisplayName(Uri uri) {
        String name = null;
        android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
        try {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Throwable ignore) {
        } finally {
            if (c != null) c.close();
        }
        return name;
    }

    private String safeVersion() {
        try { return Engine.versionString(); }
        catch (Throwable t) {
            Diag.log("!! versionString 异常: " + t.getMessage());
            return "?";
        }
    }

    // 统一入口：ready(在前台+有权限) 时才拉起相机；标定只下一次
    private void ensureStarted() {
        if (!resumed || !granted || ar == null) return;
        if (ar.isStarted() || startInProgress) return;
        if (!calibrated) {
            startInProgress = true;
            Diag.log("可用检查 isAvailable=" + ar.isAvailable()
                    + " quality=" + ar.qualityLevel());
            ar.prepare((ok, msg) -> {
                startInProgress = false;
                calibrated = true;
                if (!ok) Diag.log("!! 标定下载可能失败, 仍尝试启动: " + msg);
                // 标定下载期间可能已切后台，交给 onResume 再拉起
                ensureStarted();
            });
        } else {
            boolean started = ar.start();
            Diag.log("AR 启动=" + started);
            if (!started && resumed) {
                Toast.makeText(this, "AR 启动失败, 点HUD看日志", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Diag.log("onResume");
        resumed = true;
        Engine.onResume();
        if (glView != null) glView.onResume();
        if (lightSensor != null)
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_UI);
        startHudLoop();
        ensureStarted();
    }

    @Override
    protected void onPause() {
        Diag.log("onPause");
        resumed = false;
        super.onPause();
        sensorManager.unregisterListener(this);
        hudHandler.removeCallbacksAndMessages(null);
        Engine.onPause();
        if (glView != null) glView.onPause();
        // 切后台停相机省电；onResume 会用缓存的标定重建
        if (ar != null) ar.stop();
    }

    @Override
    protected void onDestroy() {
        Diag.log("onDestroy");
        super.onDestroy();
        hudHandler.removeCallbacksAndMessages(null);
        if (ar != null) { ar.dispose(); ar = null; }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
            lastLux = event.values[0];
            renderer.setLight(lastLux);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void startHudLoop() {
        hudHandler.removeCallbacksAndMessages(null);
        hudHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                String lux = lastLux < 0 ? "--" : String.format("%.0f", lastLux);
                int t = renderer != null ? renderer.getTracking() : -1;
                String status = t == 2 ? "正常" : (t == 1 ? "受限" : "未跟踪");
                int fps = renderer != null ? renderer.getFps() : 0;
                String model = renderer != null ? renderer.currentModel() : "--";
                hud.setText("NVR · VIO\n" + lux + "lux · " + status
                        + "\n" + fps + " FPS · 点此重置锚点"
                        + "\n模型: " + model);
                // 硬加载的模型较重时，每个模型问一次是否烘焙成私有缓存（慢但只做一次，之后秒开）
                if (renderer != null && renderer.consumeBakeSuggestion()) {
                    ModelScanner.Entry be = renderer.getBakeEntry();
                    if (be != null && !be.file.equals(bakeOfferedFor)) {
                        bakeOfferedFor = be.file;
                        offerBake();
                    }
                }
                hudHandler.postDelayed(this, 300);
            }
        }, 200);
    }

    // 只持有 applicationContext，不持有 Activity，避免泄漏窗口
    private static class CrashHandler implements Thread.UncaughtExceptionHandler {
        private final Context app;
        private final Handler main = new Handler(Looper.getMainLooper());

        CrashHandler(Context ctx) { app = ctx.getApplicationContext(); }

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            String msg = "==== 崩溃 ====\n线程:" + t.getName() + "\n" + Diag.stack(e);
            Diag.log(msg);
            // 落盘兜底：没有上报服务器，文件就是唯一的现场证据
            try {
                File f = new File(app.getExternalFilesDir(null), "crash.log");
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(msg.getBytes("UTF-8"));
                fos.close();
            } catch (Throwable writeErr) {
                Log.e(TAG, "写 crash.log 失败: " + writeErr.getMessage());
            }
            // 友好提示，让用户知道发生了什么而不是无声闪退
            main.post(() -> Toast.makeText(app, "应用遇到问题已停止，日志已保存",
                    Toast.LENGTH_LONG).show());
            try { Thread.sleep(1500); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Diag.log("权限回调 requestCode=" + requestCode);
        if (requestCode == REQ_CAMERA && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Diag.log("相机权限已授予");
            granted = true;
            ensureStarted();
        } else {
            Diag.log("!! 相机权限被拒绝");
            Toast.makeText(this, "需要摄像头权限", Toast.LENGTH_SHORT).show();
        }
    }
}
