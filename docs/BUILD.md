# 构建说明（面向开发者）

## 环境要求

| 组件 | 版本 | 备注 |
|---|---|---|
| JDK | 17+ | 项目用 `jdk-22` 构建通过 |
| Android SDK | platform 36, build-tools 36.1.0 | `local.properties` 指向 SDK |
| NDK | `30.0.16248370` | 见 `app/build.gradle.kts` 的 `ndkVersion` |
| CMake | `4.1.2` | 见 `externalNativeBuild.cmake.version` |
| Gradle | 8.13 | wrapper 自带 |

## 步骤

```bash
# 1. 拉取 llama.cpp（不入 git）
git clone --depth 1 --branch v0.7.0 https://github.com/ggml-org/llama.cpp.git llama.cpp

# 2. 构建
./gradlew :app:assembleDebug

# 3. 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次构建需编译 llama.cpp，约 2-4 分钟。产物包含 `arm64-v8a` 与 `x86_64` 两个 ABI。

## 模拟器

1.5B 模型在 2GB 内存的默认模拟器上会 OOM。把 AVD 的 `config.ini` 改成：

```ini
hw.ramSize=6144
vm.heapSize=512
hw.cpu.ncore=6
```

## 架构

```
[模型管理屏] --下载--> ModelRepository --OkHttp Range--> .part
                                     --SHA-256--> models/*.gguf
[聊天屏] --发送--> ChatViewModel --(IO 单线程)--> LlamaEngine (JNI)
                       processSystemPrompt  (新会话/恢复)
                       replayTurn           (历史回放)
                       processUserPrompt    (增量)
                       generateNextToken    (循环，返回 String?)
                                     |
                         Flow<String> --逐字--> Compose 气泡
                                     |
                              Room 落库（流结束后整条写）
```

### 关键文件

| 路径 | 作用 |
|---|---|
| `app/src/main/cpp/jni_bridge.cpp` | JNI 桥接，llama.cpp 全部原生调用 |
| `app/src/main/cpp/CMakeLists.txt` | 引入 `llama.cpp` 源码编译 |
| `llama/LlamaEngine.kt` | 引擎接口与状态机 |
| `llama/LlamaEngineImpl.kt` | JNI 封装，所有原生调用串行化到单线程 |
| `model/ModelRepository.kt` | 下载（续传/校验/镜像）、导入、删除 |
| `chat/ChatViewModel.kt` | 对话编排、历史回放、思考块过滤 |
| `chat/ThinkingBlockFilter.kt` | 流式剥离 `thinking` 块 |
| `data/` | Room 实体、DAO、数据库 |

## 与官方示例的差异

基于 `llama.cpp/examples/llama.android`，但修了 4 处会直接导致功能不可用的问题：

1. **JNI 符号前缀**：官方是 `com_arm_aichat_internal_*`，本项目改为 `com_example_chatai_llama_*`
2. **system prompt 只能用一次**：官方 `_readyForSystemPrompt` 是一次性标志，导致无法新建对话。本项目允许重复调用（原生侧每次都会清 KV cache）
3. **`stop_generation_position` 重复累加**：官方把 prompt 长度加了两次，对话越长生成越早截断。已修正
4. **context size 硬编码**：官方写死 8192，本项目由 Kotlin 侧按模型传入（模拟器用 2048 省内存）

另外关闭了 `GGML_CPU_KLEIDIAI` 与 `GGML_CPU_ALL_VARIANTS`：它们会在 CMake 配置阶段从
GitHub 下载 KleidiAI，在 TLS 拦截代理后面会失败。纯 CPU 后端对这两个模型足够。

## 已知限制

- **不保存 KV cache**：重开 App 需重放历史（长会话恢复会有几秒延迟）
- **单模型常驻**：切换模型需卸载重载
- **纯 CPU 推理**：未接 GPU/NNAPI/OpenCL。0.6B 在模拟器约 8-15 tok/s，真机更快
- **仅文本**：无语音/图片多模态
- **模型清单静态**：内置于 `app/src/main/assets/models.json`，改这个文件即可增删模型

## 模型

| 模型 | 量化 | 体积 | 最低内存 | 说明 |
|---|---|---|---|---|
| Qwen3 4B | Q4_K_M | 2381 MB | 5 GB | 旗舰档，带思考块（已过滤） |
| Qwen2.5 1.5B Instruct | Q4_K_M | 1065 MB | 3 GB | 中文主力，无思考块 |
| Qwen3 0.6B | Q4_K_M | 378 MB | 1.5 GB | 低配档，带思考块（已过滤） |
