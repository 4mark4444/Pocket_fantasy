# Pocket Fantasy

[English](README.md) | **简体中文**

一款 Android 交互式小说应用，完全在设备本地运行的写R18内容的小模型。

## 截图展示

<p align="center">
  <img src="example/generation.jpg" alt="截图 1" />
</p>

## 下载（APK）

如果你只想在 Android 手机上体验应用，请从 [Releases 页面](https://github.com/4mark4444/Pocket_fantasy/releases) 下载最新的预构建 APK。

**运行要求**
- Android 8.0（API 26）或更高版本
- **arm64-v8a** 架构设备
- 约 2.5 GB 可用存储空间
- 在浏览器 / 文件管理器中开启「安装未知应用」权限（设置 → 应用 → 特殊访问权限）

## 从源码构建

面向想要自行构建应用的开发者。仓库只包含源码 — 1.2 GB 的语言模型和 llama.cpp 库需要单独获取。

### 1. 克隆本仓库

```bash
git clone https://github.com/4mark4444/Pocket_fantasy.git
cd Pocket_fantasy
```

### 2. 拉取指定 commit 的 llama.cpp

```bash
git clone https://github.com/ggml-org/llama.cpp.git app/src/main/cpp/llama.cpp
git -C app/src/main/cpp/llama.cpp checkout 45155597aa23243c5f6d10064bd9bca3eaddee16
```

### 3. 下载模型

从 Hugging Face 下载 `test_1.gguf`，并放置到 `app/src/main/assets/model/test_1.gguf`：

- Hugging Face: https://huggingface.co/Mark4444/PF_qwen3.5ab_v1.0.0

快速方式：

```bash
# 方式 A — huggingface_hub 命令行工具
pip install -U "huggingface_hub[cli]"
hf download Mark4444/PF_qwen3.5ab_v1.0.0 test_1.gguf \
  --local-dir app/src/main/assets/model

# 方式 B — curl
curl -L -o app/src/main/assets/model/test_1.gguf \
  https://huggingface.co/Mark4444/PF_qwen3.5ab_v1.0.0/resolve/main/test_1.gguf
```

### 4. 构建

所需工具链：
- Android Studio Giraffe 或更高版本
- NDK `25.1.8937393`
- JDK 17
- 最低 SDK 26（Android 8.0）

```bash
./gradlew assembleDebug
./gradlew installDebug   # 安装到已连接的设备
```

仅构建 `arm64-v8a`。

## 参考与致谢

- **代码：** [Claude Code](https://claude.com/claude-code) （本人才疏学浅，没写过android应用，大部分代码都是Claude code写的，如果代码很屎请您谅解。）
- **图标：** 来自 [game-icons.net](https://game-icons.net/)
- **基础模型：** [huihui-ai/Huihui-Qwen3.5-2B-abliterated](https://huggingface.co/huihui-ai/Huihui-Qwen3.5-2B-abliterated)
- **数据集：** [wuliangfo/Chinese-Pixiv-Novel](https://huggingface.co/datasets/wuliangfo/Chinese-Pixiv-Novel)

## 许可证

MIT  [LICENSE](LICENSE)。
