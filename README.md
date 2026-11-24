# NVR

安卓端视觉 AR Demo：摄像头实时画面 + 手机运动跟踪（VI-SLAM），把 3D 角色锚定在真实空间里。转手机能看到角色侧面和背面，前后走动有近大远小，脚贴在地面上。

- 基于 **EasyAR Sense 4.11**（Community）的 VIO/SLAM，**不依赖 ARCore / GMS**。
- 渲染用 **GLES 2.0**：相机帧做背景，上面分层绘制带贴图的 3D 模型。
- **模型自动发现**：不用改一行代码，把 `.mesh / .glb / .vrm` 模型丢进指定目录就能用。
- **两级加载**：标准 GLB/VRM 直接实时解析（拿来即用）；模型太重时会询问是否**一键烘焙**成本项目私有格式缓存，之后每次打开都很快。
- Release 开启 **R8 代码混淆**；模型属于 assets 资源，不参与代码混淆，且以 `noCompress` 原样打包。

> 本仓库 **不含任何 EasyAR Key，也不含任何模型文件**（版权原因）。需要你自己配置 Key、放入自己有权使用的模型。

---

## 1. 功能

- 摄像头画面实时铺底，角色空间锚定：转手机看侧背、走动近大远小。
- 地面估计让角色脚底贴地，点 HUD 可重新锚定。
- 曝光跟随摄像头画面亮度（不依赖光线传感器），开灯关灯都看得清。
- 右下角两个按钮：
  - **导入模型**：从手机里挑 `.mesh / .glb / .vrm`，复制进应用模型目录并自动校验魔数。
  - **切换模型**：列出所有已发现的模型，点选即时切换；「重新扫描」可刷新列表。
- HUD 顶部显示当前加载的模型名。
- 实时解析的模型若较重，弹窗询问是否烘焙成缓存；点确定后台转换，完成后自动切换，原文件不动。

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

App 启动时会**自动扫描**下面两个位置，找出所有合法模型（`.mesh / .glb / .vrm`），第一个作为默认，右下角可切换任意一个：

| 位置 | 说明 | 是否需要重新编译 |
|---|---|---|
| `app/src/main/assets/`（含 `models/` 子目录） | 随 APK 打包，适合随 App 分发自己的模型 | 需要 |
| 手机 `Android/data/com.nvr.app/files/models/` | 应用私有外部目录，无需任何存储权限；也可用「导入模型」按钮写进去 | 不需要 |

文件名随意。扫描器**只读文件头 4 字节校验魔数**（NVR2 或 glTF），不认识的文件会被跳过，不会导致崩溃。

- 想运行时加模型：点「导入模型」从手机选文件，或直接用文件管理器把模型放进上面第二个目录，再点「切换模型 → 重新扫描」。
- 首次安装如果没模型：HUD 显示「模型: 无」，导入一个即可看到角色。
- 网上下载的 `.glb` / `.vrm`（如 VRoid、Sketchfab 导出的标准 glTF 2.0）可**直接使用**，无需先转换。

---

## 4. 模型格式与两级加载

App 支持两类模型，按文件头魔数自动识别、统一走同一条渲染管线：

| 格式 | 扩展名 | 魔数 | 加载方式 |
|---|---|---|---|
| 本项目私有格式 NVR2 | `.mesh` / `.nvr2` | `0x3252564E`（ASCII `NVR2`） | 直接读取，最快 |
| 标准 glTF 2.0 二进制 | `.glb` / `.vrm` | `0x46546C67`（ASCII `glTF`） | 运行时解析，拿来即用 |

VRM 本质就是带 `VRMC_vrm` 扩展的 GLB，所以同样支持。运行时解析器（`GlbLoader.java`）覆盖：节点变换矩阵、POSITION/NORMAL/TEXCOORD、interleaved 顶点、TRIANGLES/STRIP/FAN、`baseColor` 贴图与 alphaMode，顶点超 65535 自动拆块。

### 两级加载机制

1. **硬加载**：GLB/VRM 直接实时解析，零转换、拿来即用。
2. **一键烘焙**：若模型较重（解析耗时或三角面数超阈值），会弹窗询问。点「转换并加速」后后台把网格烘焙成 NVR2 缓存（慢，但只做一次），写到 `files/models/<同名>.nvr2`，完成后自动切换。之后扫描器会**优先同名缓存**、跳过原 GLB，等于把慢路径换成快路径。原文件始终保留。

> 烘焙在手机上进行，本质是 glTF 解析 + NVR2 序列化，不依赖任何 PC 脚本；贴图带透明通道时用 PNG 编码以保留 alpha。

### NVR2 二进制结构

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
- **源码本身不混淆**：仓库里的 `.java` 全部是可读源码，混淆只发生在 Release APK 的字节码里。
- **模型文件不会被混淆**：`.mesh / .glb / .vrm` 位于 `assets/`，R8 只处理字节码，从不触碰资源；`build.gradle` 里 `noCompress` 让模型字节原样进包（GLB 首 4 字节魔数尤其不能被重压缩破坏）。
- 混淆映射表在 `app/build/outputs/mapping/release/mapping.txt`，排查线上崩溃时可配合它还原堆栈。

---

## 6. 目录结构

```
app/src/main/java/com/example/sensorar/
  MainActivity.java   生命周期、权限、Key 检查、导入/切换 UI
  AREngine.java       EasyAR 相机与位姿（官方管线）
  GLRenderer.java     GLES 渲染、自动加载/切换模型、曝光
  ModelScanner.java   自动扫描 assets 与外部目录、魔数校验、缓存去重
  MeshParts.java      NVR2 分层网格解析 + 烘焙序列化
  GlbLoader.java      GLB/VRM 运行时解析成 MeshParts
  EasyKey.java        Key 占位（开源版为空）
  Diag.java           屏幕日志 + 落盘
app/src/main/assets/  放 .mesh/.glb/.vrm 模型（仓库默认空，见其中的 README.md）
app/proguard-rules.pro 混淆规则
```

---

## 7. 已知限制

- 个人版 EasyAR 有水印、单次运行时长约 100 秒。
- 需联网首次下载 EasyAR 标定文件，之后离线可用。
- 模型为静态网格（无骨骼动画）：GLB/VRM 取 bind/rest pose，VRM 通常是 T 字形站姿，不会播放动作。
- GLB 若含 Draco / EXT_meshopt 压缩的 primitive 会在运行时跳过（同一文件里未压缩的 primitive 仍正常显示）；想完整显示请先转成未压缩 glTF 或用「烘焙」流程。
- 须佐能乎等模型的翅膀会展得很宽，属模型本身比例。
- 若 HUD 一直「未跟踪」且帧数为 0，点 HUD 展开日志查看。

---

## 8. 许可

[MIT](LICENSE) © 2026 NVR contributors

模型、EasyAR SDK 等第三方资源的版权归各自所有者所有；本仓库不包含任何此类资源，请自行获取并遵守其许可。
