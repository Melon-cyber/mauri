# Сторонние компоненты Mauri

Mauri распространяется по GPL-3.0-or-later. Тексты основных лицензий также включены в APK в `assets/licenses/`.

| Компонент | Лицензия | Исходный проект |
| --- | --- | --- |
| NewPipe Extractor v0.27.0 | GPL-3.0-or-later | https://github.com/TeamNewPipe/NewPipeExtractor |
| AndroidX, Compose, Media3 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin и kotlinx.coroutines | Apache-2.0 | https://github.com/JetBrains/kotlin / https://github.com/Kotlin/kotlinx.coroutines |
| OkHttp | Apache-2.0 | https://github.com/square/okhttp |
| Coil | Apache-2.0 | https://github.com/coil-kt/coil |
| Gson | Apache-2.0 | https://github.com/google/gson |
| Rhino (зависимость Extractor) | MPL-2.0 | https://github.com/mozilla/rhino |
| jsoup (зависимость Extractor) | MIT | https://github.com/jhy/jsoup |
| Protocol Buffers (зависимость Extractor) | BSD-3-Clause | https://github.com/protocolbuffers/protobuf |
| nanojson (зависимость Extractor), Copyright 2011 The nanojson Authors | Apache-2.0 | https://github.com/TeamNewPipe/nanojson |
| Manrope, Copyright 2018 The Manrope Project Authors | SIL Open Font License 1.1 | https://github.com/sharanda/manrope / https://github.com/google/fonts/tree/main/ofl/manrope |

Точные версии и зависимости закреплены в `app/build.gradle.kts` и POM NewPipe Extractor v0.27.0. Код и ресурсы Liner в Mauri отсутствуют. AndroidX Palette включён в строку AndroidX.

Исходный код NewPipe Extractor v0.27.0 (commit `7d4d691846acfa3c5af4b2c124955ee4c11cd3e8`) доступен в его репозитории по тегу `v0.27.0`: https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.27.0. Сборка Mauri получает его через JitPack именно с этого тега. Полный исходный код самого Mauri находится в этом репозитории, у каждого релиза есть свой тег.
