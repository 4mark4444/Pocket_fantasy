# Pocket Fantasy

An Android interactive-fiction app that runs a local LLM (GGUF via llama.cpp) entirely on-device. The model streams back structured XML that the app parses into story text and player choices, giving an immersive first-person novel experience with no network calls.

## Setup

The repo contains source only — the 1.2 GB language model and the llama.cpp library are fetched separately.

### 1. Clone this repo

```bash
git clone https://github.com/4mark4444/Pocket_fantasy.git
cd Pocket_fantasy
```

### 2. Fetch llama.cpp at the pinned commit

```bash
git clone https://github.com/ggml-org/llama.cpp.git app/src/main/cpp/llama.cpp
git -C app/src/main/cpp/llama.cpp checkout 45155597aa23243c5f6d10064bd9bca3eaddee16
```

### 3. Download the model

Download `test_1.gguf` from Hugging Face and place it at `app/src/main/assets/model/test_1.gguf`:

- Hugging Face: https://huggingface.co/Mark4444/PF_qwen3.5ab_v1.0.0

Quick options:

```bash
# Option A — huggingface_hub CLI
pip install -U "huggingface_hub[cli]"
hf download Mark4444/PF_qwen3.5ab_v1.0.0 test_1.gguf \
  --local-dir app/src/main/assets/model

# Option B — direct curl
curl -L -o app/src/main/assets/model/test_1.gguf \
  https://huggingface.co/Mark4444/PF_qwen3.5ab_v1.0.0/resolve/main/test_1.gguf
```

### 4. Build

Required toolchain:
- Android Studio Giraffe or later
- NDK `25.1.8937393`
- JDK 17
- Min SDK 26 (Android 8.0)

```bash
./gradlew assembleDebug
./gradlew installDebug   # install on connected device
```

Only `arm64-v8a` is built.

## License

MIT — see [LICENSE](LICENSE).
