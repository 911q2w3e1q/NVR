# NVR

安卓端视觉 AR Demo：摄像头实时画面 + 手机运动跟踪（VI-SLAM），把 3D 角色锚定在真实空间里。转手机能看到角色侧面和背面，前后走动有近大远小，脚贴在地面上。

- 基于 **EasyAR Sense 4.11**（Community）的 VIO/SLAM，**不依赖 ARCore / GMS**。
- 渲染用 **GLES 2.0**：相机帧做背景，上面分层绘制带贴图的 3D 模型。
- **模型自动发现**：不用改一行代码，把 `.mesh` 模型丢进指定目录就能用。
- Release 开启 **R8 代码混淆**；`.mesh` 模型属于 assets 资源，不参与代码混淆，且以 `noCompress` 原样打包。

> 本仓库 **不含任何 EasyAR Key，也不含任何模型文件**（版权原因）。需要你自己配置 Key、放入自己有权使用的模型。

---

## 1. 功能

- 摄像头画面实时铺底，角色空间锚定：转手机看侧背、走动近大远小。
- 地面估计让角色脚底贴地，点 HUD 可重新锚定。
- 曝光跟随摄像头画面亮度（不依赖光线传感器），开灯关灯都看得清。
- 右下角两个按钮：
  - **导入模型**：从手机里挑一个 `.mesh`，复制进应用模型目录并自动校验。
  - **切换模型**：列出所有已发现的模型，点选即时切换；「重新扫描」可刷新列表。
- HUD 顶部显示当前加载的模型名。

---

## 2. 快速开始

### 环境
- Android Studio / Gradle 8.5，JDK 17，Android SDK 34。
- 一台支持 OpenGL ES 2.0、有摄像头的安卓手机（minSdk 24 / Android 7.0）。

### 构建
```bash
git clone <你的仓库地址> NVR
cd NVR
./gradlew assembleRelease          # 输出到 app/build/outputs/apk/release/
```
没有配置本地 `nvr.keystore` 时会自动用 Android 标准 debug 签名，仍可安装。

### 配置 EasyAR Key（必做，否则只弹提示、不启动 AR）

1. 到 [www.easyar.cn](https://www.easyar.cn) 注册账号。
2. 在控制台**新建 Sense 个人版 Key**，包名填 `com.nvr.app`（与 `build.gradle` 的 `applicationId` 一致）。
3. 打开 `app/src/main/java/com/example/sensorar/EasyKey.java`，把申请到的 Key 一整串填进 `STR`：
   ```java
   private static final String STR = "这里贴你的Key";
   ```
4. 重新编译安装。

Key 与包名绑定：改了 `applicationId` 就要去 EasyAR 重新申请。个人版有水印、单次运行时长限制，属官方政策，与本项目无关。

> 想彻底不依赖 EasyAR？本项目 EasyAR 只用在 `AREngine.java`（VIO 位姿）与 `EasyKey.java`，替换这两个文件即可换用自己的 SLAM 方案。

---

## 3. 放模型（不用改代码）

App 启动时会**自动扫描**下面两个位置，找出所有合法的 `.mesh`，第一个作为默认，右下角可切换任意一个：

| 位置 | 说明 | 是否需要重新编译 |
|---|---|---|
| `app/src/main/assets/`（含 `models/` 子目录） | 随 APK 打包，适合随 App 分发自己的模型 | 需要 |
| 手机 `Android/data/com.nvr.app/files/models/` | 应用私有外部目录，无需任何存储权限；也可用「导入模型」按钮写进去 | 不需要 |

文件名随意。扫描器**只读文件头 4 字节校验魔数**，不认识的 `.mesh` 会被跳过，不会导致崩溃。

- 想运行时加模型：点「导入模型」从手机选文件，或直接用文件管理器把 `.mesh` 放进上面第二个目录，再点「切换模型 → 重新扫描」。
- 首次安装如果没模型：HUD 显示「模型: 无」，导入一个即可看到角色。

---

## 4. 模型格式（NVR2）

`.mesh` 是本项目的自定义二进制格式，魔数 `0x3252564E`（小端读为 ASCII `NVR2`）。结构：

```
文件头:  magic(4) | version(int32) | parts(int32)
每部件:  vcount(int32) | icount(int32) | texlen(int32) | blend(int32)
         | factor[4](float32: 色调 rgb + 特殊标记 a)
         | 顶点 vcount × (pos3 + nrm3 + uv2) float32
         | 索引 icount × uint16
         | 贴图 texlen 字节（JPEG/PNG，由 BitmapFactory 解码）
```

- `blend`：0=不透明，1=半透明（后画），2=遮罩。
- 顶点 8 个 float：`x,y,z` / `nx,ny,nz` / `u,v`。
- 贴图尺寸 ≤256 自动用最近邻采样（像素风保持硬边），大贴图用线性采样，无需配置。

---

## 5. 混淆说明

Release 构建开启 R8（`minifyEnabled true` + `shrinkResources true`）：

- **Java 代码被混淆压缩**；`cn.easyar.**`（JNI 反射）、模型加载与渲染入口类在 `app/proguard-rules.pro` 中显式 keep，保证功能不受影响。
- **模型文件不会被混淆**：`.mesh` 位于 `assets/`，R8 只处理字节码，从不触碰资源；`build.gradle` 里 `noCompress 'mesh'` 让模型字节原样进包。
- 混淆映射表在 `app/build/outputs/mapping/release/mapping.txt`，排查线上崩溃时可配合它还原堆栈。

---

## 6. 目录结构

```
app/src/main/java/com/example/sensorar/
  MainActivity.java   生命周期、权限、Key 检查、导入/切换 UI
  AREngine.java       EasyAR 相机与位姿（官方管线）
  GLRenderer.java     GLES 渲染、自动加载/切换模型、曝光
  ModelScanner.java   自动扫描 assets 与外部目录、魔数校验
  MeshParts.java      NVR2 分层网格解析
  EasyKey.java        Key 占位（开源版为空）
  Diag.java           屏幕日志 + 落盘
app/src/main/assets/  放 .mesh 模型（仓库默认空，见其中的 README.md）
app/proguard-rules.pro 混淆规则
```

---

## 7. 已知限制

- 个人版 EasyAR 有水印、单次运行时长约 100 秒。
- 需联网首次下载 EasyAR 标定文件，之后离线可用。
- 模型为静态网格（无骨骼动画）；须佐能乎等模型的翅膀会展得很宽，属模型本身比例。
- 若 HUD 一直「未跟踪」且帧数为 0，点 HUD 展开日志查看。

---

## 8. 许可

[MIT](LICENSE) © 2026 NVR contributors

模型、EasyAR SDK 等第三方资源的版权归各自所有者所有；本仓库不包含任何此类资源，请自行获取并遵守其许可。
