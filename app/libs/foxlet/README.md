# Foxlet Translate 0.5.0

`../foxlet-v0.5.0.aar` contains the official 0.5.0 native libraries and a Kotlin 2.2.10 rebuild of its Kotlin interface layer. The upstream Kotlin 2.4 metadata is newer than AGP 9.2.1's built-in Kotlin compiler can consume. A small Kotlin-only preload API patch loads models without translating placeholder text; native inference and networking are unchanged. See `preload.patch`.

- Release: https://github.com/yinvoke/foxlet-translate/releases/tag/v0.5.0
- Pinned source: https://github.com/yinvoke/foxlet-translate/tree/0eff2a15032362115848a496ce1df8ed9fc68650
- Original AAR SHA-256: `5dbb384907d69c2aba129fd15dba30dda94ede0fa556dc6a167d041ee479ec0e`
- Native libraries, Android manifest, consumer rules and AAR metadata are unchanged.
- Kotlin sources: upstream `foxlet/src/main/kotlin/`, compiled with Kotlin JVM 2.2.10, JVM target 17, JDK 21, Android API 37 compile-only and kotlinx-coroutines-core 1.11.0. Apply `preload.patch` after downloading the sources. The resulting jar replaces only `classes.jar`; original non-class resources are retained.
- Rebuild: `uv run --no-project app/libs/foxlet/rebuild.py` from the repository root, using Windows Gradle/JDK/SDK through `gradlew-win`.
- Android 9+ and a 64-bit ARM runtime are required for this provider. The host keeps minSdk 26 with a manifest override and never initializes this provider on unsupported runtimes.
- `app/src/main/assets/translation/foxlet-models.json` is the matching upstream registry snapshot. Downloads use Mozilla's official CDN. Translation calls prepare local models only and never download or upload text.

License notices and source links are retained inside the AAR and displayed in the application's license screen. Original Foxlet code is MIT; included components and Mozilla models retain their own licenses, including MPL-2.0. See upstream NOTICE and THIRD_PARTY_NOTICES.
