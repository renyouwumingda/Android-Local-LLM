# chatAI 实施计划

安卓端本地大模型对话 App：应用内下载 GGUF 模型 → llama.cpp 本地推理 → 流式中文对话。
支持多会话历史持久化、本地 `.gguf` 文件导入。全程离线，无云端 API。

---

## 1. 验收标准

| # | 验收项 | 验证方式 |
|---|---|---|
| V1 | `gradlew :app:assembleDebug` 产出 APK | 退出码 0，APK 存在 |
| V2 | `libai-chat.so` 含 x86_64 且无缺失依赖 | `llvm-readelf -d` 检查 `NEEDED` |
| V3 | 模拟器启动，模型页显示可下载模型 | `adb install` + 截图 |
| V4 | 下载完成，sha256 与清单一致 | `adb shell ls -l` + 校验日志 |
| V5 | 加载模型后"你好"，1 分钟内中文流式输出 | 截图 + logcat |
| V6 | 点击"停止"生成立即中断 | 截图 |
| V7 | 杀进程重进，会话与消息完整还原 | 截图对比 |
| V8 | SAF 导入本地 .gguf 后可加载 | 截图 |
| V9 | 1.5B 在真机 ≥5 tok/s | 内置 bench |

---

## 2. 环境现状（实测）

| 项 | 实测值 | 备注 |
|---|---|---|
| 工程 | Kotlin 2.0.21 / AGP 8.11.0 / Gradle 8.13 / compileSdk 36 / minSdk 28 / Compose | 仅模板 `Greeting()` |
| SDK | `local.properties` → `D:\Sdk` | 真实 SDK 在此 |
| NDK | `D:\Sdk\ndk\30.0.16248370`（r30） | 官方示例要 29.0.13113456 |
| CMake | `D:\Sdk\cmake\4.1.2` | 官方示例要 3.31.6 |
| JDK | `jdk-22`；AS 自带 JBR 21.0.6 | 需 JVM target 17 |
| 模拟器 | `Pixel_6` / android-35 / x86_64 / RAM 2048MB | 内存不足，需上调 |
| 真机 | `adb devices` 空 | 暂无 |
| 网络 | GitHub ✅ / HF API ✅ / Maven Central ✅ | HF 大文件曾超时 → 镜像兜底 |
| llama.cpp | 最新 tag `v0.7.0` | 固定版本 |

### 模型清单（实测元数据）

| 模型 | 文件 | 字节 | SHA256 |
|---|---|---|---|
| Qwen2.5-1.5B-Instruct Q4_K_M（主） | `qwen2.5-1.5b-instruct-q4_k_m.gguf` | 1,117,320,736 | `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e` |
| Qwen3-0.6B Q4_K_M（低配） | `Qwen3-0.6B-Q4_K_M.gguf` | 396,705,472 | `ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a` |

> 1.5B 为主模型；0.6B 作为低配档，保证 2GB 模拟器也能验证闭环。

---

## 3. 关键技术决策

### 3.1 工具链：优先复用已装 NDK 30 + CMake 4.1.2

- `ndkVersion = "30.0.16248370"`，`cmake.version = "4.1.2"`
- `CMakeLists.txt` 的 `cmake_minimum_required` 降为 `3.22`
- llama.cpp 自身 `cmake_minimum_required(VERSION 3.14...3.28)` 在 CMake 4.x 下合法

**回退方案**：`sdkmanager "ndk;29.0.13113456" "cmake;3.31.6"`（已验证可获取）。

### 3.2 目录布局（与官方示例不同）

```
chatAI/
├── llama.cpp/                  # git clone --depth 1 --branch v0.7.0
└── app/src/main/cpp/
    ├── CMakeLists.txt          # LLAMA_SRC = ../../../../llama.cpp
    └── jni_bridge.cpp
```
官方是 6 层 `../`（位于 `examples/llama.android/lib/src/main/cpp`），我们 4 层。

### 3.3 JNI 符号名必须重写

官方：`Java_com_arm_aichat_internal_InferenceEngineImpl_*`
本项目：`Java_com_example_chatai_llama_LlamaEngineImpl_*`
不重写 → `UnsatisfiedLinkError`。

### 3.4 官方示例缺陷（必须修）

`InferenceEngineImpl.setSystemPrompt()` 的 `check(_readyForSystemPrompt)` 是一次性标志：
加载后为 true，用一次即 false。**后果：无法"清空对话"或切换会话，只能卸载重载模型。**

**修法**：去掉一次性标志，允许 `ModelReady` 状态下重复调用 `processSystemPrompt()`。
原生侧本就调用 `reset_long_term_states()` 清 KV cache，语义正确。"新对话" = 重新下发 system prompt。

### 3.5 其他必改点

- `minSdk` 28 → **33**
- JVM target 11 → **17**
- `abiFilters` = `arm64-v8a` + `x86_64`
- `GGML_CPU_ALL_VARIANTS=ON` + `GGML_BACKEND_DL=ON`
- Qwen3 需后处理剥离 ` thinking...` 段（Qwen2.5 无此问题）

---

## 4. 实施阶段

### 阶段 0：工具链与骨架

1. clone llama.cpp v0.7.0 → **验证**：`git describe --tags` = `v0.7.0`
2. 改 `app/build.gradle.kts` → **验证**：未加 JNI 前 `assembleDebug` 通过
3. 模拟器 RAM 2048 → 6144 → **验证**：`adb shell cat /proc/meminfo`

### 阶段 1：原生层打通

1. `CMakeLists.txt`（改 LLAMA_SRC 层级 + cmake 版本）
2. `jni_bridge.cpp`（改 JNI 符号前缀），暴露：

| JNI 方法 | 签名 | 作用 |
|---|---|---|
| `init` | `(String): void` | 日志回调、加载 backend、`llama_backend_init` |
| `load` | `(String): Int` | 加载模型，0=成功 |
| `prepare` | `(Int nCtx, Int nThreads): Int` | 建 context/batch/sampler |
| `setSampling` | `(Float, Int, Float, Int): void` | 重建 sampler |
| `systemInfo` | `(): String` | 调试信息 |
| `processSystemPrompt` | `(String): Int` | 重置 KV + 注入 system prompt |
| `processUserPrompt` | `(String, Int): Int` | 注入用户消息 |
| `generateNextToken` | `(): String?` | 增量 UTF-8 片段，null=结束 |
| `unload` / `shutdown` | `(): void` | 释放 |

关键实现（照搬官方）：`is_valid_utf8` 半字缓存、`shift_context()`、`llama_vocab_is_eog` 结束判定。

### 阶段 2：Kotlin 引擎层

`com.example.chatai.llama`：
- `LlamaEngineImpl` 单例，`Dispatchers.IO.limitedParallelism(1)` 串行化原生调用
- `EngineState`：`Uninitialized / Initializing / Initialized / LoadingModel / ModelReady / Generating / Error`
- `loadModel` / `resetConversation` / `sendUserPrompt: Flow<String>` / `stopGeneration` / `unload`
- 取消：volatile 标志 + Flow 取消双保险

### 阶段 3：模型仓库与下载

- `assets/models.json`：id/name/fileName/url/sizeBytes/sha256/minRamMb/params/quant/license
- `ModelRepository`：
  - `download(id): Flow<DownloadProgress>` — WorkManager + setForeground
  - Range 断点续传（`.part` 文件）
  - 完成后校验 SHA256
  - 落盘 `getExternalFilesDir("models")`
  - `importFromUri(uri)` — SAF 复制进 models 目录
  - `delete(id)` / `listInstalled()`

### 阶段 4：持久化

Room：
- `Session(id, title, createdAt, updatedAt, modelId, systemPrompt)`
- `Message(id, sessionId, role, content, tokenCount, createdAt)`
- 首条用户消息自动生成标题（截断 20 字）
- 不存 KV cache（重进需重放历史）

### 阶段 5：UI

Compose + Material3，三屏：
1. **会话列表**（抽屉）：新建/重命名/删除/切换
2. **聊天屏**：气泡、流式打字、输入框、发送/停止、顶部模型与 tok/s
3. **模型管理屏**：卡片列表、下载/暂停/删除/导入、加载遮罩、内存不足禁用

### 阶段 6：打磨与交付

- 返回键处理（生成中先停止）
- 前台服务通知
- `README.md`

---

## 5. 数据流

```
[模型管理屏] --下载--> ModelRepository --WorkManager--> .part
                                     --sha256--> models/*.gguf
[聊天屏] --发送--> ChatViewModel --(IO单线程)--> LlamaEngine
                       processSystemPrompt (首次/新会话)
                       processUserPrompt   (增量)
                       generateNextToken   (循环)
                                     |
                         Flow<String> --逐字--> Compose 气泡
                                     |
                              Room 落库（流结束后整条写）
```

**上下文管理**：`shift_context()` 接近 `n_ctx` 时丢弃系统提示后前半段重算。
1.5B 在 8K 上下文 KV cache 约 0.5-1GB，**模拟器上 n_ctx 降到 2048**。

---

## 6. 边界与失败模式

| 场景 | 处理 |
|---|---|
| 下载中断 / 切后台 | WorkManager + `.part` 续传 + 前台通知 |
| SHA256 不匹配 | 删除，提示重试，切镜像重下 |
| HF 直连超时 | 回退 `hf-mirror.com`（带 Range + sha256） |
| 存储不足 | `StatFs` 检查 `可用 < size * 1.2` 则拒绝 |
| 内存不足 | 比对 `totalMem` 与 `minRamMb`，不足则禁用并提示 |
| 模型文件被外部删除 | 加载时 `exists()` 检查，状态回落"未下载" |
| 加载中按返回 | 禁止，显示不可取消遮罩 |
| 生成中杀进程 | 已生成部分不落库；下次提示"上次生成被中断" |
| 中文半个字截断 | 原生 `is_valid_utf8` 已处理 |
| 1.5B 模拟器 OOM | 明确不支持，UI 按 `totalMem` 拦截 |

---

## 7. 依赖与配置变更

**新增依赖**
```
room = "2.6.1"        # room-runtime, room-ktx, room-compiler(ksp)
work = "2.9.1"        # work-runtime-ktx
okhttp = "4.12.0"     # 下载 + Range
ksp 插件
```
> Room 需 KSP；若引入失败，降级 DataStore + JSON。

**build.gradle.kts**
```kotlin
minSdk = 33
compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = ... }
ndkVersion = "30.0.16248370"
defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
externalNativeBuild { cmake { path("src/main/cpp/CMakeLists.txt"); version = "4.1.2" } }
```

**AndroidManifest**
```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<application android:largeHeap="true" ...>
<service android:name=".model.DownloadService" android:foregroundServiceType="dataSync" .../>
```
> `largeHeap="true"` 必要：1.5B mmap + KV cache 需要超出默认堆的地址空间。

---

## 8. 明确假设

1. 不需要联网对话 — 全本地推理
2. 单模型常驻 — 同一时刻只加载一个，切换需卸载
3. 不做语音/图片多模态
4. 不做模型市场动态拉取 — 清单内置 `assets/models.json`
5. 真机暂不可用 — 先以 x86_64 模拟器验证；arm64 照常编进 APK
6. 不追求首版性能优化 — 纯 CPU，后续可加 `GGML_OPENCL`

---

## 9. 风险与应对

| 风险 | 概率 | 应对 |
|---|---|---|
| NDK 30 + CMake 4.1.2 编译失败 | 中 | 回退 NDK 29 + CMake 3.31.6 |
| 首次 C++ 编译超 30 分钟 | 中 | 只编 x86_64 先跑通，arm64 后补 |
| HF 下载 1GB 过慢 | 中高 | 镜像兜底；调试期先用 0.6B |
| 模拟器 6GB 仍 OOM | 中 | 模拟器只用 0.6B，1.5B 留待真机 |
| Room + KSP 引入失败 | 低 | 降级 DataStore + JSON |
| llama.cpp v0.7.0 API 与示例漂移 | 中 | 以 tag 的 `include/llama.h` 为准修正 |

---

## 10. 交付物

```
chatAI/
├── llama.cpp/                       # v0.7.0（.gitignore）
├── app/src/main/
│   ├── cpp/{CMakeLists.txt, jni_bridge.cpp}
│   ├── assets/models.json
│   └── java/com/example/chatai/
│       ├── llama/{LlamaEngine.kt, LlamaEngineImpl.kt, EngineState.kt}
│       ├── model/{ModelCatalog.kt, ModelRepository.kt, DownloadWorker.kt}
│       ├── data/{AppDatabase.kt, SessionDao.kt, MessageDao.kt, Entities.kt}
│       ├── ui/{ChatScreen.kt, ModelsScreen.kt, SessionsDrawer.kt, ChatViewModel.kt, ModelsViewModel.kt}
│       └── MainActivity.kt
├── README.md
└── docs/PLAN.md                     # 本文件
```

**总预估**：12-14 小时净工作量，6 阶段，每阶段独立验证门，卡住超 1 小时即降级。
