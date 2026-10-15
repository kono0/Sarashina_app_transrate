# サードパーティのライセンス

このアプリのソースコードは [MIT License](LICENSE) です。
アプリが使っている外部のライブラリとモデルは、それぞれのライセンスに従います。

## リポジトリに同梱しているもの

| 名前 | 場所 | ライセンス | 配布元 |
|---|---|---|---|
| sherpa-onnx 1.13.8 (Android AAR) | `app/libs/sherpa-onnx-1.13.8.aar` | Apache License 2.0（[全文](licenses/sherpa-onnx-LICENSE.txt)） | https://github.com/k2-fsa/sherpa-onnx |
| ONNX Runtime（上の AAR に含まれる） | 同上 | MIT License（[全文](licenses/onnxruntime-LICENSE.txt)） | https://github.com/microsoft/onnxruntime |
| Gradle Wrapper | `gradlew`, `gradle/wrapper/` | Apache License 2.0 | https://github.com/gradle/gradle |

## アプリが初回起動時にダウンロードするモデル

これらのモデルはリポジトリにも APK にも入っていません。各端末が配布元から直接ダウンロードします。

| モデル | 作者 | ライセンス | 配布元 |
|---|---|---|---|
| Silero VAD | Silero Team | MIT License | https://github.com/snakers4/silero-vad |
| SenseVoiceSmall（sherpa-onnx 版 int8: `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17`） | FunAudioLLM / Alibaba Group (Tongyi Lab) | [FunASR Model License](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE) | https://github.com/FunAudioLLM/SenseVoice 、変換版: https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17 |
| CAM++ 話者埋め込み（`3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced`） | 3D-Speaker (Alibaba Group / ModelScope) | Apache License 2.0 | https://github.com/modelscope/3D-Speaker |

SenseVoice のモデルは FunASR Model License により、出典・作者を表示し、モデル名を残すことが求められています。
アプリでは設定画面の「使用しているモデル」にも表示しています。

## Maven から取得するライブラリ

ビルド時に Gradle が取得する主なライブラリです（APK に含まれます）。

| 名前 | ライセンス |
|---|---|
| AndroidX（Core, Activity, Lifecycle, DataStore, Jetpack Compose, Material 3） | Apache License 2.0 |
| Kotlin 標準ライブラリ、kotlinx.coroutines、kotlinx.serialization | Apache License 2.0 |
| OkHttp / Okio | Apache License 2.0 |

## 外部サービス

翻訳には [Sarashina API](https://docs.sarashina.ai/)（SB Intuitions 株式会社）を使います。利用には各自の API キーが必要で、Sarashina の利用規約に従います。
