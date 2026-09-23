# Changelog

## v4.5.0 (2026-09-20)

### New Features

- **AI Auto Prompt Selection**: Before polishing, automatically pick the best Prompt preset for the transcript. Presets can include a Skill description; you can choose a separate classifier model and preview with history. High-quality transcripts can skip polishing
- **Keep Screen On While Recording**: Keeps the screen on during keyboard or floating-ball recording so it will not sleep mid-session; also available as a keyboard extension toggle
- **DashScope Qwen3.8-Omni-Flash**: DashScope adds the Qwen3.8-Omni-Flash non-streaming model
- **StepAudio Models**: StepAudio adds stepaudio-3-asr-max and custom recognition models
- **[Pro] Separate Assistant and Hotword Models**: AI assistant and hotword-correction analysis can each use their own model instead of the default polish model

### Improvements

- **Online Non-Streaming Chunking**: Online file recognition now chunks on silence for faster first results on long recordings; failed segments are retried automatically
- **Full-Screen Prompt Editor**: Polish prompts can be edited full screen
- **AI Extra Parameters**: Temperature and other advanced parameters move into a dedicated sheet
- **[Pro] Input Field Context**: When enabled, automatic prompt selection also uses text around the cursor

### Bug Fixes

- **Fold and Rotation**: Fix floating ball and floating keyboard position and usable area after rotation or folding
- **Recording Source**: Cache the validated capture source to reduce recording failures or repeated source probing on some devices
- **Backup Import**: More compatible recognition-history backup import when newer enum fields are missing

---

## v4.4.5 (2026-09-11)

### New Features

- **Deep Thinking Threshold**: Deep thinking now turns on based on recognized length. 0 always enables it; the maximum never enables it; values in between enable it when the length exceeds the threshold
- **Android 13 Accessibility Insert**: Optional new Accessibility IME API for the floating ball, more reliable in terminals and editors. Turns off streaming preview and write-compatibility optimization

### Improvements

- **[Pro] Cursor Glide**: Smoother tracking and more accurate vertical movement in multi-line fields; also available from the AI edit panel

### Bug Fixes

- **History Loss on IME Switch**: Fixed recognition history loss in certain scenarios
- **[Pro] Cursor Glide**: Fix entering the mode on dynamic keyboard layouts; sliding to the start or end of text no longer makes the cursor disappear

---

## v4.4.3 (2026-09-06)

### New Features

- **SiliconFlow Models**: Free recognition adds the XingChen ASR series; BYOK adds Qwen3-ASR 1.7B
- **DashScope Qwen3 Restored**: Qwen3-ASR-Flash (non-streaming) and Qwen3-ASR-Flash-Realtime are available again
- **[Pro] Hotwords in Polish Prompt**: Append enabled hotwords and aliases to the AI polish prompt; optionally include only phonetically related ones

### Improvements

- **Recognition and Polish Warmup**: Warm online ASR and polish connections when recording starts; capture release is now async, so results appear sooner and polish waits less after you stop
- **More Reliable Capture**: Faster input-source probing during warmup; preload offline denoising

### Bug Fixes

- **Numpad Height**: Fix the numpad not matching the main keyboard height
- **Clipboard Attachments**: Reduce repeat processing of watched folders
- **History Section Crash**: After midnight, the same record no longer appears in both “Last 2 hours” and “Today”, which crashed the history list
- **IME Key Display**: Fix keys sometimes showing incorrectly
- **Keyboard Timing**: Fix the data source for recognition-stage duration shown during keyboard recognition

---

## v4.4.2 (2026-08-27)

### New Features

- **Gemini Transcribe**: Gemini adds a Transcribe call mode
- **History Timing Timeline**: History details show time spent on recording, recognition, polish, and text commit; the dialog adapts to available height

### Improvements

- **LLM Connectivity Test**: Test results show connect, first-token, and output latency, connection reuse, and whether streaming is supported
- **Faster Recognition and Keyboard**: Async history commit, cached first-frame UI, and broader HTTP connection-pool reuse for lower recognition latency, less commit jank, and faster keyboard open
- **AI Polish Wording**: UI now uses “polish” instead of “post-processing”
- **Typewriter Effect**: When off, the full result is applied at once after polish finishes
- **Qwen3-ASR 1.7B Cleanup**: Strips model markers so they are not committed as text
- **Cancel Polish on Keyboard Hide**: Hiding the IME cancels in-flight AI polish and commits the raw transcript, so the next mic tap is not ignored
- **Polish Timeout and Cancel**: AI polish now has a timeout cap; cancelled requests no longer fall back to non-streaming
- **[Pro] Hotword Preload**: Builds phoneme indexes at startup to reduce first-recognition post-processing delay

### Bug Fixes

- **Bridge Module**: Improved communication management with the Bridge module
- **Notification Language**: Keep-alive, floating, and similar notifications now follow the app language
- **Floating Ball Performance**: Aura effects no longer keep refreshing when not recording

---

## v4.4.1 (2026-08-20)

### New Features

- **Keyboard History Panel**: New keyboard extension button to browse and insert recent transcripts; swipe to re-recognize or re-process
- **Keep Failed Recognition Records**: Failed, timed-out, or cancelled sessions keep audio and appear in history with a reason, so they can be re-recognized or re-processed
- **Doubao Model Picker**: Volcano Engine now uses a direct model picker for streaming/file 1.0/2.0 instead of stacked toggles
- **Qwen3-ASR 1.7B Int8**: Added the Qwen3-ASR 1.7B Int8 local model
- **Switch IME After Input**: After recognition (and post-processing) finishes, optionally switch back to a chosen IME for further editing
- **Keep-Alive Notification Tap**: When background keep-alive is on, choose which page the persistent notification opens

### Improvements

- **Recording Re-recognition**: When a streaming model is selected, re-recognition automatically switches to a file recognition engine or model to improve success rate (X-ASR supports streaming replay)
- **Vendor Setting Labels**: Unified recognition model, language, and endpoint labels across vendors
- **Pro Intro**: Refined the Pro introduction page

### Bug Fixes

- **Streaming Preview Logs**: Added more detailed streaming-preview process logs; export detailed logs if you hit related issues
- **Slider Commit**: Slider values apply only after release, avoiding repeated local-model reloads while dragging thread count
- **Custom Reasoning Params**: Fixed LLM vendor custom reasoning parameters not being saved
- **History Audio Mix-up**: Cancelled recognition no longer reuses the previous audio

---

## v4.4.0 (2026-08-02)

### New Features

- **Clipboard Attachment Sync**: Clipboard sync can now pull image and file attachments; it can watch a selected folder to upload new files, with a configurable attachment size limit
- **Clipboard File Sharing**: Upload files via the system share sheet, with attachment upload/download notifications
- **New DashScope Models**: Added Qwen-Audio-3.0-ASR-Flash streaming and non-streaming models, removed legacy models, and refined semantic punctuation handling
- **[Pro] Hotword Injection Control**: New toggle to control whether hotwords are injected into supported recognition engines

### Improvements

- **Usage Stats**: Consolidated usage stats into a dedicated page with one-tap share card generation
- **UI Settings**: Consolidated interface style, haptic feedback, and keyboard layout into a dedicated UI Settings entry
- **IME Bridge Error Handling**: Enhanced IME Bridge error handling and user feedback
- **Recognition History UI**: Refined the layout and styling of history cards and text components
- **[Pro] Continuous Talk**: Attempted to optimize continuous talk session management and audio focus handling for more stable recording
- **[Pro] Hotword Optimization**: Targets already present in existing hotwords are no longer learned again; improved DashScope hotword management and sync

### Bug Fixes

- Optimized some descriptive copy
- Attempted to fix duplicated streaming previews appearing in the IME
- Fixed the empty search state content not being centered

---

## v4.3.0 (2026-07-26)

### New Features

- **App Shortcuts**: Long-press the app icon for quick access to ASR settings, AI settings, and recognition history
- **Fcitx5 / Trime Clipboard Sync**: AIDL-linked Fcitx5 and Trime forks can join SayWhat's clipboard sync
- **[Pro] Automatic Hotword Learning**: Corrections after voice input can be learned as hotwords, with pending confirmation or auto-add; works with modified IMEs, IME bridge, and accessibility floating ball

### Improvements

- **Local Model Downloads**: Resumable downloads with local caching for more reliable recovery after interruptions
- **Download Source Latency**: Download source latency checks use HTTP probing
- **Settings Home Search**: Refined home search entry and search field styling
- **Recognition History**: Optimizes list/detail layout
- **Local Non-Streaming Recognition**: Long audio is progressively recognized in silence-based chunks for better local non-streaming recognition speed, and fixes the error of Qwen ASR and FunASR Nano local non-streaming recognition of long audio

- **Companion Module Updates**: LSPosed IME Bridge and Fcitx5 / Trime forks updated alongside the app for clipboard sync and (Pro) hotword learning

### Bug Fixes

- **[Pro] AI Assistant Wake Word**: Fixed the problem of custom wake word being forcibly overridden by app language automatic synchronization
- **Stop Recording on Keyboard Dismiss**: Recording stops reliably when the keyboard is dismissed, avoiding a stuck recording state on reopening

---

## v4.2.1 (2026-07-22)

### New Features

- **Clipboard Realtime Sync**: SyncClipboard Auto Receive uses realtime sync when the server supports it, otherwise periodic pull; optionally keep the realtime connection alive in the background

### Improvements

- **Target API Version**: Updated Target API version to 3
- **Clipboard Sync Runtime**: IME Bridge clipboard sync now runs as an independent background service and no longer depends on the floating ball
- **IME Hook Module Updates**: Optimized IME Hook module toggle and status copy; recording-area availability is controlled by the bridge module; added multiple module settings and support for real-time module settings changes
- **Settings Switch Explanations**: More settings switches show feature explanations when toggled, making on/off effects clearer

---

## v4.2.0 (2026-07-20)

### New Features

- **Floating Ball Hold-to-Record**: Added a hold-to-record mode for the floating ball—press and hold to record, release to stop
- **Cohere ASR**: Added Cohere as a speech recognition vendor
- **Arabic UI**: Added Arabic as an app language option
- **History Audio & Rerun**: Recognition sessions can cache audio; history details show the raw transcript and final result, with re-recognize and re-process actions; Other settings can configure audio retention count
- **IME Bridge Clipboard Sync**: Clipboard sync can use IME Bridge assistance while the target IME process is alive

### Improvements

- **Recording Audio Focus**: Unified audio-focus handling for recording sessions so recording stops more reliably when focus is lost
- **Settings Interactions**: Smoother dialog exit animations and various UI improvements

### Bug Fixes

- **IME Bridge Coordination**: Fixed premature cancellation of finishing sessions during service shutdown, which could drop pending recognition results

---

## v4.1.2 (2026-07-13)

### New Features

- **Auto Enter After Input**: Added a toggle and extension button to automatically press Enter / trigger the editor Send action after recognition (or after AI post-processing ), making it easier to send in chat apps
- **IME Bridge In-IME Recording**: Long-press to record inside a compatible third-party keyboard and send audio to BiBi Keyboard for recognition (requires an updated LSPosed/LSPatch bridge module)
- **Volcengine New Authentication**: Volcengine ASR now supports the new API Key authentication method
- **Clear Clipboard History**: Other settings can clear all in-app clipboard history at once (including pinned items)

### Improvements

- **Switch IME on Hide**: When “switch IME when hidden” is enabled, the keyboard will try to reappear after switching to the target IME
- **Clipboard Preview**: Clipboard preview is now a limited number of lines display with more stable text normalization and truncation
- **ASR Settings Copy**: Clarified descriptions for silence auto-stop, backup engine, ITN, and related settings

---

## v4.1.1 (2026-07-08)

### New Features

- **Fun-ASR-Flash Support**: Added support for Fun-ASR-Flash, an Alibaba Cloud Bailing speech recognition model

### Information Update

- **Pro Price Adjustment**: The price of Pro has been adjusted from $4.49 to $5.49

## v4.1.0 (2026-07-05)

### New Features

- **StepAudio Endpoint Configuration**: Added endpoint presets for StepAudio, supporting pay-as-you-go, Coding Plan, and custom endpoints
- **MiMo / StepAudio Per-Endpoint API Keys**: MiMo and StepAudio now support saving API keys separately for each endpoint preset
- **Local Backup Residency Mode**: Added local backup residency settings to choose between on-demand loading and resident mode
- **[Pro] Floating Ball Continuous Talk**: Pro floating ball now supports continuous talk mode for persistent listening and segmented recognition
- **[Pro] IME Bridge Input Context**: Pro floating ball recording with IME bridge enabled can now use input box context for AI post-processing

### Improvements

- **Floating Ball Recording Visual Feedback**: Shows halo and peak ripple effects based on audio levels during recording; enhanced processing visuals
- **Backup ASR Strategy Refactor**: Refactored backup ASR arbitration and timeout logic with lazy serial local backup support, plus loading/recognition status indicators for a smoother experience
- **VAD Auto-Stop Optimization**: Input volume normalization for better VAD performance across engines; clearer auto-stop setting labels
- **One-Click Setup Accessibility Detection**: Improved accessibility requirement detection to better reflect floating ball, volume key, and IME bridge settings

### Bug Fixes

- **Debug Log Cleanup**: Fixed debug log cleanup not working when exporting logs from the About page
- **Backup Engine Tolerance**: Empty results are no longer considered as call failures, improving the fault tolerance of backup engine switching logic

---

## v4.0.3 (2026-07-02)

### New Features

- **Continuous Recording While Visible**: Records audio locally while the keyboard or floating ball is visible for faster recognition startup
- **Recording Timeout Stop**: Added timeout-based auto-stop recording mode with configurable maximum recording duration
- **Soniox Recognition Mode**: Added low-latency to high-accuracy sensitivity options for Soniox streaming recognition

### Improvements

- **OpenAI Custom Endpoint Compatibility**: Non-streaming compression no longer applies to custom endpoints
- **Floating Keyboard Drag Handle**: Automatically moves the drag handle to the top or bottom based on bottom window gap
- **Microphone Button Styling**: Removed the fixed square microphone button constraint for more flexible custom layout proportions
- **Custom Keyboard Layout Grid Sync**: Syncs grid size across all panels when any panel is resized, automatically filtering out-of-bounds buttons
- **[Pro] Continuous Mode Optimization**: Tried to solve the problem of audio being discarded before triggering recording in continuous mode
- Improved backup settings page text styling

### Bug Fixes

- **X-ASR Tail Audio**: Fixed potential loss of tail audio when stopping X-ASR streaming recognition
- **[Pro] Pro Backup Fix**: Fixed partial content loss in Pro configuration backup

---

## v4.0.2 (2026-06-18)

### New Features

- **IME Bridge Mode**: Added IME bridge mode that communicates with LSPosed modules via broadcast, supporting keyboard state detection, text insertion, composing text preview, and session management
- **Floating Ball Foreground Service**: Added microphone foreground service support required by Android 14 for floating ball recording
- **AI Edit System Prompt Customization**: Allow users to customize the AI edit feature's system prompt; leaving it empty falls back to the default

### Improvements

- **Soniox Streaming Model Upgrade**: Upgraded Soniox streaming recognition model to stt-rt-v5
- **Accessibility Copy Optimization**: Updated accessibility compatibility and advanced feature related string descriptions

---

## v4.0.1 (2026-06-14)

### New Features

- **Floating Keyboard Mode**: Added floating keyboard support with a control toggle
- **Widescreen Optimization**: Optimized display for landscape and tablet devices, with left/right docking support
- **Punctuation Jump Button**: Added forward/backward jump buttons in the keyboard based on punctuation positions
- **fxliang Fcitx5 Voice Integration**: Support for fxliang's Fcitx5 IME to invoke speech recognition via AIDL
- **Trailing Punctuation Unlimited Option**: Added "Unlimited" option for trailing punctuation and emoji removal threshold

### Improvements

- **Soniox Model Upgrade**: Upgraded Soniox non-streaming recognition model to v5
- **Haptic Feedback Refactor**: Refactored and centralized haptic feedback mechanism
- **Recording Test Page Optimization**: Optimized latency color display on the recording test page
- **[Pro] AMOLED Independent Toggle**: Refactored AMOLED mode as an independent toggle with automatic migration from old settings
- **[Pro] Auto-Update Check Optimization**: Optimized auto-update checks; when disabled, no longer depends on Play Services

### Bug Fixes

- **Keyboard Bottom Spacing**: Optimized keyboard bottom spacing in certain scenarios
- **ITN Optimization**: Improved ITN performance in certain scenarios
- **[Pro] Hotword Statistics Fix**: Fixed hotword hit count statistics and color grading

---

## v4.0.0 (2026-06-07)

### Major Changes

- **Settings Compose Migration**: Migrated all settings pages from traditional XML layouts to Jetpack Compose
- **Keyboard and Floating Ball UI Refactor**: Refactored keyboard and floating ball UI using Compose for improved maintainability and rendering performance
- **Custom Keyboard Layout**: Support for customizing keyboard layout and content on canvas
- **[Pro] Theme Color System Refactor**: Refactored the theme color system with added AMOLED mode and multiple Monet dynamic color support

### New Features

- **MiMo ASR Online Recognition**: Added MiMo online ASR vendor support with multimodal and ASR models
- **X-ASR Local Streaming Engine**: Introduced X-ASR engine to replace Paraformer, supporting local streaming recognition
- **Volume Key Recording Control**: Support for volume key recording start/stop via accessibility permission
- **Recording Test and Analysis Page**: Added recording test and analysis page
- **Recording Mode Switch Extension Button**: Added microphone recording mode switch extension button
- **Miuix Library Integration**: Integrated the Miuix component library to enhance settings page UI experience
- **Trailing Punctuation Removal Threshold**: Added configurable character count threshold for removing trailing punctuation and emoji
- **OpenAI Completions API**: OpenAI ASR channel supports calling multimodal models via the completions interface for transcription
- **[Pro] Hotword Enhancement Mode**: Added phoneme-based hotword enhancement mode, dynamically replacing recognition results based on phoneme matching
- **[Pro] MiMo Context Injection**: Added context injection support for MiMo multimodal models
- **[Pro] Regex Post-Processing Order Option**: Added option to run regex before AI post-processing, with feature description dialog

### Improvements

- **Local ITN Algorithm Refactoring**: Refactored the local model Chinese ITN module, covering more use cases
- Upgraded sherpa-onnx to 1.13.2
- Implemented hash integrity check for local models
- Improved numerous component UI details and copy
- Optimized accessibility permission detection and handling

### Bug Fixes

- Fixed mimo-v2.5 request body construction and response parsing
- Optimized accessibility event processing and settings page recomposition performance

---

## v3.16.1 (2026-05-23)

### New Features

- **[Pro] Input Field Context**: When using the main keyboard, LLM post-processing can now use the existing text in the input field as context, improving rewrite coherence
- **API Log Page Local ASR Records**: Added local ASR call logging support to the API Log page

### Improvements

- **Empty Audio Filtering Disabled by Default**: Changed VAD empty audio auto-cancel and silent segment filtering to be disabled by default; users can enable it as needed
- **Empty Audio Skip Message Optimization**: Improved error messages for empty audio skips to differentiate between skip reasons
- **Audio Filter Rewrite**: Rewrote the audio filter with chunk-based analysis and trimming for improved silence detection accuracy
- **Local Model Loading Optimization**: Extended preload-on-recording logic to cover external linkage scenarios

### Bug Fixes

- **Swipe Lock Touch Event Handling**: Fixed touch event handling for the microphone button when in swipe-locked mode

---

## v3.16.0 (2026-05-17)

### New Features

- **OpenRouter ASR Support**: Added OpenRouter as an online ASR provider with API key and model configuration
- **API Call Logs**: Added API call recording and viewing to the recognition history page, with list, filter, search, and detail views
- **VAD Empty Audio Filtering**: Added settings to filter empty audio requests and remove silent segments before non-streaming recognition. Default enabled.
- **About Page Recognition Stats**: Added online ASR channel failure rate stats for the last 30 days on the About page

### Improvements

- **armv7a Packaging**: Added armv7a support, selected APKs by device ABI, and added fallback download URLs to improve in-app update download compatibility
- **[Pro] Regex Edit Input**: Changed the input field in the regex rule editor to a regular text input field

### Bug Fixes

- **Recognition Cancellation Handling**: When using the main keyboard, post-processing changes can be reverted for a short period after post-processing completes

---

## v3.15.2 (2026-04-29)

### New Features

- **Non-Streaming Upload Audio Compression**: Added an option to compress upload audio for online non-streaming recognition when supported by the selected provider, reducing transfer size and network time. Default enabled.

### Improvements

- **Gemini 3 Thinking Config Support**: Added Gemini 3 model thinking configuration support for Gemini ASR and updated the related setting copy to reduce thinking intensity.

---

## v3.15.1 (2026-04-26)

### Bug Fixes

- **Foreground Service Stability Optimization**: Enhanced foreground service types and exception handling, improving user experience on Android 15 and above.

---

## v3.15.0 (2026-04-26)

### New Features

- **Qwen3-ASR Local Recognition**: Added Qwen3-ASR 0.6B as a new local ASR vendor
- **Parakeet Local Recognition**: Added support for the Parakeet local ASR engine, with V3 supporting multiple European languages and V2 supporting English
- **StepAudio ASR Support**: Added support for StepAudio as an online ASR vendor

### Improvements

- **Local Model Auto-Unload Timing**: Adjusted auto-unload scheduling after warm-up starts successfully to improve local model resource management
- **[Pro] Expanded Hotword Support**: Added hotword support for Qwen3ASR, FunASR Nano, and StepAudio

### Bug Fixes

- **Vendor Tag Correction**: Improved vendor tags for Qwen3Asr, FireRedAsr, and Paraformer
- **Recognition Duration Reporting**: Fixed duration reporting for some ASR vendors

---

## v3.14.2 (2026-04-18)

### New Features

- **Alibaba DashScope Qwen3.5-Omni**: Added Qwen3.5-Omni Flash and Plus model options for Alibaba DashScope ASR

### Improvements

- **Recording Startup Network Warmup**: Added network warmup during recording startup to help reduce first-use network latency
- **Local Model Timeout Tuning**: Set different timeout thresholds for different local ASR engines based on resource usage
- **Keep-Alive Notification Content**: Added support for showing live-updating basic information in the persistent notification

---

## v3.14.1 (2026-03-22)

### New Features

- **OpenAI ASR Multi-Profile Support**: Added multi-profile switching support for OpenAI ASR
- **FunASR Nano Language Selection**: Added recognition language selection for FunASR Nano
- **FunASR MLT Nano Variant Support**: Added support for the FunASR MLT Nano variant

### Improvements

- **FunASR Nano Native ITN**: Enabled native ITN in FunASR Nano to improve Chinese text normalization results

---

## v3.14.0 (2025-03-08)

### New Features

- **FireRedASR V2 Engine Support**: Replaced TeleSpeech and added support for the FireRedASR V2 engine

### Improvements

- **Recognition Performance Optimization**: Optimized model discovery and PCM conversion flow to reduce redundant processing overhead
- **UI Rendering Optimization**: Reduced redundant UI updates to improve rendering efficiency
- **Local ASR Dependency Upgrade**: Upgraded sherpa-onnx to 1.12.28

### Bug Fixes

- Fixed FireRedASR result sanitization and resource cleanup issues on error paths to improve recognition stability
- Fixed delayed UI updates caused by structural render refresh not being flushed in some scenarios

---

## v3.13.4 (2025-02-28)

### New Features

- **OpenAI Realtime Streaming Recognition**: Added OpenAI Realtime streaming speech recognition support
- **Alibaba DashScope Model Upgrade**: Upgraded Alibaba DashScope streaming recognition model to qwen3-asr-flash-realtime-2026-02-10

### Improvements

- **Compatibility Enhancement**: Lowered minSdk to 26, supporting Android 8.0 and above

---

## v3.13.3 (2025-02-23)

### New Features

- **Initial Setup Guide**: Refactored initial configuration guide page for first-time users
- **Keyboard Button Long-Press Hints**: Added long-press hint feature for keyboard buttons
- **Provider Configuration Grouping**: Separated configured and unconfigured ASR and LLM providers into different groups
- **Backup ASR Engine Timeout Sensitivity Setting**: Added timeout threshold sensitivity setting for backup ASR engine

### Improvements

- **SyncClipboard Compatibility**: Added support for new SyncClipboard version (v3.11.1 and above)

---

## v3.13.2 (2025-02-17)

### New Features

- **Alibaba Dashscope LLM Provider**: Added Alibaba Dashscope as a new LLM provider option
- **[Pro] Hotword Trigger Frequency Statistics**: Added hotword trigger count in hotword management interface to help users optimize their hotword list

### Improvements

- **Shizuku/Root Privilege Manager**: Refactored Shizuku/Root privilege manager for improved stability and maintainability
- **Keyboard Panel Stability**: Unified IME bottom inset resolution logic across different ROMs, added bottom inset warm-up mechanism to improve keyboard panel stability
- **Soniox Model Update**: Updated Soniox streaming engine model version to stt-rt-v4

---

## v3.13.1 (2025-02-09)

### New Features

- **Shizuku/Root Enhanced Keep-Alive**: Added Shizuku/Root enhanced keep-alive feature for improved background stability
- **Built-in Prompts Multilingual Support**: Implemented multilingual support for built-in prompts, enhancing user experience for different language speakers
- **LLM Test Cancellation**: Added LLM test cancellation feature
- **[Pro] New AI Assistant Feature**: Added AI assistant feature in Pro version, triggered by keywords
- **[Pro] Hotword Injection Optimization**: When enabling hotword injection AI post-processing

### Improvements

- **Floating Ball Recording Animation**: Optimized floating ball recording breathing animation effect
- **LLM Temperature Settings**: Updated default temperature value

---

## v3.13.0 (2025-02-01)

### New Features

- **Settings Search**: Added settings search functionality
- **History Optimization**: Added external input source category identifier in history records with optimized time display
- **AI Edit Panel Enhancement**: Added info bar and space key to AI edit panel with optimized layout
- **Soniox Model Upgrade**: Updated Soniox ASR non-streaming engine model version to v4

### Improvements

- **Soniox Language Selector Optimization**: Optimized Soniox language selector UI
- **UI Detail Optimization**: Optimized pasteboard panel icon and adjusted numeric keyboard panel height and margins
- **Architecture Refactoring**: Refactored keyboard core, ASR settings, floating ball interaction and settings storage modules for better maintainability
- **VAD Performance Optimization**: Implemented VAD instance pooling to improve performance and resource utilization
- **RecognitionService Enhancement**: Improved security and compatibility of RecognitionService
- **IME Tile Refactoring**: Refactored input method selector tile logic

### Bug Fixes

- Fixed floating ball error state animation and visual effect cleanup issues
- Added VAD resource release logic for streaming ASR engines
- Fixed recognition record handling when AI processing fails

### Changes

- Removed experimental features of Doubao voice bidirectional streaming and first-character acceleration

---

## v3.12.1 (2025-01-27)

### New Features

- **Floating Ball Icon Optimization**: Optimized floating ball icon and animation effects, added arrow handle when floating ball is docked at screen edge in persistent mode
- **History Statistics Enhancement**: Enhanced history statistics with AI post-processing time display
- **External IME Linkage Enhancement**: Improved external input method linkage guidance with added support for Trime input method

### Improvements

- **AI Post-Processing Typewriter Effect**: Only use typewriter animation when AI post-processing is actually active

### Bug Fixes

- Added edge anchor system to fix position offset issues when switching between portrait and landscape modes
- Fixed options menu display issue when using Android navigation bar
- Fixed layout issues caused by abnormal system insets during cold start

---

## v3.12.0 (2025-01-20)

### New Features

- **Parallel Primary-Backup Dual-Engine Recognition**: Added parallel primary-backup dual-engine recognition feature to improve speech recognition accuracy and reliability
- **FunASR Nano Independent Vendor**: Separated FunASR Nano from SenseVoice as an independent ASR vendor
- **FunASR Nano Inference Optimization**: Added support for FunASR Nano full version to improve recognition accuracy
- **Post-Processing Typewriter Effect**: Added typewriter animation effect for streaming AI post-processing results to enhance interaction experience
- **Post-Processing Typewriter Toggle**: Added typewriter effect toggle option in post-processing settings
- **Switch IME Tile**: Added new system settings quick tile for convenient input method switching
- **Vendor Tags**: Added tags for speech recognition vendors to help users choose the appropriate vendor
- **[Pro] One-Tap Hotword Deletion**: Added one-tap delete all hotwords feature in hotword management interface
- **[Pro] Regex Post-Processing Optimization**: Built-in common regex templates with configurable descriptions and optimized UI

### Improvements

- **Local Model Loading Coordinator**: Introduced local model loading coordinator to optimize model loading process and improve loading reliability
- **Optimized ITN Effect**: Optimized Chinese number normalization effect to improve number recognition accuracy
- **Soniox Streaming Engine Refactor**: Fixed several issues with Soniox streaming engine
- **ASR Option Display Names**: Simplified multi-ASR option display names to improve interface cleanliness

### Bug Fixes

- Attempted to fix Paraformer end-of-sentence character loss issue
- Prevented false timeout trigger on first local model load

---

## v3.11.2 (2025-01-07)

### New Features

- **Haptic Feedback Intensity Adjustment**: Added haptic feedback intensity level adjustment and completed haptic feedback for various scenarios
- **Custom Thinking Parameters**: Added custom thinking parameter configuration for AI post-processing models
- **Foreground Keep-Alive Service**: Added foreground keep-alive service and battery optimization whitelist request entry in Other Settings
- **Download Source Speed Test**: Added latency speed test display in download source selection dialog for easier fastest mirror selection
- **Auto Update Check**: Added "Auto Check for Updates" toggle option in About page
- **New Built-in Post-Processing Models**: Added multiple built-in post-processing models to choose from
- **[Pro] Quick Access to Hotword Management**: Added "Add as Hotword" option in Android long-press text selection menu for quick hotword addition

### Improvements

- **Preset Channel Model Fetch**: Extended model list fetching to preset channels, no longer limited to custom channels only
- **Download Mirror Configuration**: Unified download mirror source configuration and simplified calling logic

### Bug Fixes

- Fixed "Configuration Guide" jump button not working for local models
- Fixed potential loss of already counted ASR characters when syncing total character count

---

## v3.11.1 (2025-12-30)

### New Features

- **Volcengine New Model Support**: Added Volcengine DeepSeek-V3-2-251201 model support
- **[Pro] App-Specific Prompt Settings**: Added app-specific prompt configuration feature in AI post-processing settings, allowing customized post-processing prompts for different apps using accessibility capabilities

### Improvements

- **AI Post-Processing Streaming Output**: Added streaming output functionality for AI post-processing, allowing users to see progressively generated text fragments while waiting for the final result, improving interactive experience
- **Settings Option Bottom Sheet**: Added new settings option bottom sheet component to replace original dialogs, enhancing interaction smoothness
- **Word Count Performance Optimization**: Optimized CJK character detection performance using range checks and fixed counting errors in some extreme text cases

### Changes

- **Removed Zipformer Engine**: Deprecated Zipformer streaming engine to streamline local ASR solutions. Existing users will be automatically migrated to Paraformer; please reconfirm or switch models in ASR settings.

---

## v3.11.0 (2025-12-28)

### New Features

- **App Icon Update**: Updated app icon design with Monet dynamic color system adaptation
- **Floating Ball Drag Enhancement**: Added direct drag-to-move support for floating ball, no need to long-press to enter drag mode
- **IME Switching Enhancement**: Added manual target IME selection when switching back, improving multi-IME switching experience
- **Custom LLM Enhancement**: Custom vendors now support configuration without model names, simplifying connection validation
- **LLM Model List**: Added "Fetch Model List" feature for custom post-processing channels

### Improvements

- **Icon Resource Refactoring**: Refactored app icon resource structure and streamlined color definitions
- **Wording Optimization**: Clarified Play Store redemption process wording

### Bug Fixes

- Fixed pseudo-streaming buffer processing flow issue

---

## v3.10.1 (2025-12-23)

### New Features

- **VAD Engine Upgrade**: Replaced Silero VAD with Ten VAD for improved voice activity detection accuracy
- **Gemini Custom Endpoint**: Added support for custom Gemini API endpoint, facilitating proxy or private deployment usage
- **Non-Streaming Noise Reduction**: Added noise reduction toggle for non-streaming recognition engines to improve accuracy in noisy environments

### Improvements

- **Pseudo-Streaming Architecture**: Refactored SenseVoice and TeleSpeech pseudo-streaming common logic into an independent Delegate for improved maintainability
- **Pseudo-Streaming Preview Strategy**: Adopted "timed segmentation + VAD filtering" strategy for pseudo-streaming preview to optimize preview effects

### Bug Fixes

- Fixed silence-based auto-stop not working in local model pseudo-streaming mode
- Fixed Volcengine recognition model version selection issue
- Disabled silence auto-stop in long-press recording mode to prevent accidental stops

---

## v3.10.0 (2025-12-18)

### New Features

- **Local ASR Engine Punctuation Support**: Added punctuation functionality to multiple local ASR engines (FunASR Nano, TeleSpeech, Paraformer, Zipformer) to improve offline recognition readability
- **FunASR Nano Model Support**: Added FunASR Nano model support
- **Universal Punctuation Model Management**: Added universal punctuation model management system with unified management and version control for punctuation models
- **Soniox Language Strict Restriction Mode**: Added language strict restriction mode option for Soniox ASR engine to improve recognition accuracy in multilingual environments
- **Dashscope Fun-ASR Semantic Segmentation**: Added semantic segmentation option for DashScope Fun-ASR

### Improvements

- **sherpa-onnx Upgrade**: Upgraded sherpa-onnx to version 1.12.20
- **SenseVoice Model Version Calling**: Optimized SenseVoice Small model version calling logic to ensure accurate model loading

---

## v3.9.4 (2025-12-16)

### New Features

- **Fun-ASR-Realtime Model**: Added Fun-ASR-Realtime model support for DashScope to improve real-time speech recognition performance
- **Initial VAD Delay**: Improved initial Voice Activity Detection (VAD) delay to enhance speech recognition user experience
- **Website and Documentation Buttons**: Added official website and documentation buttons to the about page for easy user access to help and product information
- **[Pro] Fun-ASR-Realtime Hotword Adaptation**: Added hotword adaptation for DashScope Fun-ASR-Realtime model

### Improvements

- **DashScope Model Selector**: Refactored DashScope ASR model selector architecture for improved model management and switching experience
- **Keyboard Stability**: Ensured stability of soft keyboard popup and enabled state detection for enhanced input method reliability

### Bug Fixes

- **Local Streaming Model Stability**: Fixed stability issues with local streaming model recognition, improving recognition accuracy and continuity
- **Update Installation Page**: Fixed issue where the update installation page would pop up again after returning to the app following completion

---

## v3.9.3 (2025-12-13)

### New Features

- **Fireworks AI Support**: Added Fireworks AI model provider support to expand AI post-processing capabilities
- **Enhanced Lexi Integration**: Support for SenseVoice and TeleSpeech pseudo-streaming recognition in Fcitx5 Android Lexi Keyboard
- **Add AI Post-Processing Toggle**: Add an AI post-processing toggle on the post-processing settings page.

### Improvements

- **AI Post-Processing UI Enhancement**: Changed temperature, threshold and other numeric parameters to slider controls for improved user experience
- **Settings UI Style Unification**: Adjusted settings clickable selection items to wrap_content style

### Bug Fixes

- **Punctuation Button Reset**: Fixed issue where punctuation buttons didn't reset after being pressed
- **SpeechRecognizer Interface Status Fix**: Added interface processing timeout mechanism

---

## v3.9.2 (2025-12-12)

### New Features

- **Anonymous Usage Analytics**: Added anonymous usage statistics collection to help improve product experience
- **Complete User Documentation**: Added complete user documentation button for easy access at the bottom of the user guide popup

### Improvements

- **Floating Window Layout Optimization**: Refactored implementation of multiple floating windows to improve layout effects and interaction experience
- **One-Click Setup Process Enhancement**: Improved IME selector state management, optimized polling logic to avoid inopportune prompts
- **Floating Ball Toggle Control**: Fixed floating ball toggle control logic for improved feature stability

---

## v3.9.1 (2025-12-11)

### New Features

- **Android Standard Speech Recognition Service**: Integrated Android standard RecognitionService
- **GLM-ASR Context Prompt**: Added context prompt parameter support for Zhipu GLM-ASR, upgraded model to glm-asr-2512 to improve long audio text recognition continuity
- **Update Check with View History Button**: Added a button to view update history in the update check dialog, making it easier for users to understand version changes
- **[Pro] Support for GLM-ASR Hotword Injection**: Added hotword injection functionality for Zhipu GLM-ASR to enhance recognition accuracy of specific vocabulary
- **[Pro] Support for Inserting Hotwords into Post-Processing Prompt**: Added hotword insertion functionality for LLM post-processing, targeting ASR vendors that do not support hotword injection

---

## v3.9.0 (2025-12-09)

### Improvements

- **Enter key enhancement**: Supports the enter key to send messages in more software
- **UI standardization**: Adapts to the new Android 15 API
- **Pro version promotion pop-up**: To avoid risks, the app name has been changed to 说点啥 (BiBi Keyboard)

## v3.8.6 (2025-12-09)

### New Features

- **Zhipu GLM ASR Support**: Added Zhipu GLM speech recognition engine support, ready for next version of GLM-ASR
- **Volcengine Model Version Selection**: Added model version selection for Volcengine Doubao ASR, allowing switching between different model versions
- **Pro Version Promotion Dialog**: Added Pro version feature introduction dialog to help users learn about professional edition features

---

## v3.8.5 (2025-12-06)

### New Features

- **Volcengine Standard File Recognition**: Added Volcengine standard file recognition support with timeout mechanism and detailed error handling
- **Doubao Speech Recognition Model 2.0**: Added Doubao speech recognition model 2.0 support for Volcengine(), improving recognition accuracy

### Improvements

- **Release Process Enhancement**: Improved release process to support both Chinese and English changelogs, enhancing multilingual release efficiency

---

## v3.8.4 (2025-12-04)

### New Features

- **Smart Timeout Mechanism**: Introduced first-token timeout mechanism and independent connection timeout configuration, with automatic fallback to non-streaming mode when streaming fails

### Improvements

- **WebDAV Backup Compatibility**: Added compatibility support and fallback handling for WebDAV services like Jianguoyun (Nutstore), improving backup functionality stability
- **Configuration Import/Export Extension**: Extended configuration import/export support for SiliconFlow ASR, DashScope regions, Soniox streaming, and other configuration items

### UI Improvements

- **Settings Page Style Optimization**: Unified settings page dropdown selector styles with consistent border and dropdown arrow design
- **Keyboard Height Selector**: Keyboard height selection updated to use segmented button controls for enhanced user experience
- **Layout Structure Simplification**: Removed redundant group title views, optimized layout spacing and border radius styles

---

## v3.8.3 (2025-12-03)

### New Features

- **Multi-vendor Deep Thinking Mode**: Added multi-vendor LLM deep thinking mode support, improving AI post-processing quality
- **Built-in Service Providers**: Refactored LLM vendor architecture with support for built-in service providers, expanding post-processing capabilities

### Improvements

- **AI Prompt Management System**: Refactored AI post-processing prompt management system, optimizing prompt organization and usage

---

## v3.8.2 (2025-12-02)

### UI Improvements

- **Configuration Guide Button**: Added configuration guide button to local model settings page
- **Skip Button**: Added skip button functionality to model selection dialog

### Bug Fixes

- **Status Bar Icon Color**: Fixed dynamic switching of status bar colors

---

## v3.8.1 (2025-11-29)

### UI Improvements

- **App Icon Optimization**: Optimized app icon foreground layer size for better visual effect
- **Predictive Return Animation**: Added predictive return animation to settings page for improved interaction experience, requires Android 13 or above

### Improvements

- **WebDAV Backup Refactoring**: Refactored WebDAV backup implementation to improve code quality and maintainability

---

## v3.8.0 (2025-11-28)

### New Features

- **SiliconFlow Free Service**: Added SiliconFlow free speech recognition service support with registration and configuration tutorial buttons
- **Dynamic Timeout Mechanism**: Implemented dynamic timeout calculation based on recording duration for better handling of long recordings
- **Floating Ball Settings Shortcut**: Added settings button to floating ball menu for quick access to settings

### UI Improvements

- **Settings Page Redesign**: Redesigned settings page layout with grouped card design for improved visual hierarchy and readability
- **Floating Ball Touch Interaction**: Enhanced long-press move touch interaction for floating ball
- **Style Unification**:
  - Unified text style definitions and layout structure across settings pages
  - Unified horizontal button container style definitions
  - Unified layout styles through centralized theme resources
- **Button Text Optimization**: Optimized tap-to-record mode gesture button text
- **Clipboard Sync Button**: Optimized clipboard sync button display logic

### Documentation

- Changed keyboard name to "Lexi Keyboard" in README

---

## v3.7.3 (2025-11-25)

### New Features

- **Recording Gesture Control**: Add gesture control functionality to the recording button, supporting canceling recording by sliding left and releasing, sending directly by sliding right, and temporarily fixing to the tap-to-record mode by sliding down.
- **Waveform Sensitivity Adjustment**: Added audio waveform display sensitivity adjustment, allowing users to customize waveform animation sensitivity based on personal preferences
- **DashScope Model Upgrade**: Upgraded DashScope streaming recognition engine to Qwen3-ASR-Flash-Realtime model for improved recognition speed and accuracy
- **[Pro] Hotword Management**: Added hotword management for Qwen3-ASR-Flash-Realtime model (Pro version)

---

## v3.7.2 (2025-11-23)

### New Features

- **Model Selection Guide**: Added model selection guide feature to help new users quickly choose appropriate speech recognition models
- **[Pro] Continuous Talk Mode**: Added continuous talk mode support (Pro version)

### Improvements

- Refactored build configuration, removed multi-variant builds, and cleaned up open-source codebase

### Documentation

- Updated project logo image resources
- Updated README documentation and image resources

---

## v3.7.1 (2025-11-17)

### New Features

- **TeleSpeech Offline Recognition**: Added TeleSpeech local ASR engine based on sherpa-onnx, supporting both int8 and fp32 models with complete model management, preloading, and automatic unloading capabilities
- **TeleSpeech ITN Support**: Integrated Inverse Text Normalization (ITN) for TeleSpeech engine, converting spoken expressions like "one thousand and one" into written forms like "1001"
- **Pseudo-streaming for Local Models**: Added pseudo-streaming recognition support for SenseVoice and TeleSpeech local models. Uses VAD to segment audio at pauses, providing preview results from small segments while performing final recognition on complete audio after session ends
- **ElevenLabs Streaming Recognition**: Added real-time streaming speech recognition support for ElevenLabs, with options to choose between streaming and non-streaming modes in settings
- **Keyboard Theme Enhancement**: Added dedicated button backgrounds for dark mode, unified colorSurface usage in light mode for better contrast, and improved keyboard container background using colorSurfaceVariant for enhanced visual hierarchy
- **[Pro] Custom Color Themes**: Introduced some elegant preset color themes with support for system colorway following and custom colorway switching
- **Scrolling Status Text**: Added marquee effect for long status messages to ensure complete information visibility
- **Auto-copy Error Messages**: Automatically copies error messages to clipboard when detected, making it easier for users to report issues

### Bug Fixes

- Fixed missing progress notification during TeleSpeech model downloads
- Fixed issue where pseudo-streaming engines didn't properly clear preview segments after session ends
- Fixed ElevenLabs WebSocket URL construction issue and optimized transcription processing logic
- Fixed inconsistent clipboard panel background color

---

## v3.7.0 (2025-11-14)

### New Features

- **Feature Description System Enhancement**: Added unified feature description dialog system for ASR settings, post-processing, floating ball, and other settings switches
- **Extension Buttons Optimization**:
  - Added VAD auto-stop extension button functionality
  - Added collapse keyboard extension button and pinned clipboard button
- **Clipboard Enhancement**: Added cloud clipboard file pull support
- **Floating Ball Improvements**: Added pagination loading for recognition history text panel

### Improvements

- Optimized default settings to improve user experience
- Organized feature description strings by category into corresponding sub-string files for better management

### Bug Fixes

- Fixed floating ball not auto-hiding in certain scenarios under persistent mode
- Fixed some color system issues
- Improved local model file extraction detection logic and fixed download cancellation functionality
- Fixed input method unexpectedly collapsing when opening post-processing prompt or vendor selection menu

---

## v3.6.8 (2025-11-13)

### New Features

- **Local Model Import**: Added local file import functionality for all speech recognition models
- **Feature Description System**: Added feature description dialog system for input settings

### Improvements

- Migrated model download and import format from tar.bz2 to zip
- Updated "ASR Settings" translation to "Speech Recognition Settings"
- Changed default translation resource to English

### Bug Fixes

- Optimized AI post-processing usage state tracking

---

## v3.6.7 (2025-11-10)

### New Features

- **External Integration Enhancement**: Added external IME integration usage guide and optimized user guide content
- **Pro Version Update**: Added dedicated update dialog for Pro version

### Bug Fixes

- Optimized fallback logic when AI post-processing returns empty text

---

## v3.6.6 (2025-11-10)

### New Features

- **AI Post-Processing Optimization**: Added automatic AI post-processing skip based on character count threshold
- **Pro Dual Recognition**: Added dual recognition support for Volcengine channel during integration (Pro version)
- **Integration Enhancement**: Added recognition statistics and history for integration calls

### Bug Fixes

- Fixed speech preset malfunction issue
- Fixed floating ball position overflow issue on devices with system bars

---

## v3.6.5 (2025-11-09)

### New Features

- **Pro Regex Post-Processing**: Added regular expression post-processing functionality (Pro version)

### Improvements

- Optimized paste functionality implementation
- Refactored punctuation button UI and replaced vendor switch button icon

---

## v3.6.4 (2025-11-09)

### New Features

- **Three-Level Undo**: Support for three-level undo functionality
- **Vendor Quick Switch**: Refactored punctuation button layout and added vendor switching functionality
- **Floating Ball Position Reset**: Added floating ball position reset feature
- **External Speech Service**: Added external speech service PCM push mode support

### Improvements

- Removed external speech service dependency on accessibility permission
- Removed external speech service dependency on floating ball functionality
- Optimized interface adaptation when soft keyboard pops up
- Removed fcitx5-android-lexi-keyboard submodule dependency

---

## v3.6.3 (2025-11-08)

### New Features

- Added notification permission check in one-click setup flow

### Bug Fixes

- Fixed microphone position offset and bottom clipping issues when keyboard is scaled
- Fixed microphone interaction in AI edit panel
- Fixed resource race condition when stopping Alibaba Cloud DashScope streaming speech recognition

### Improvements

- Removed redundant exception catching and optimized code structure
- Removed external speech service permission declarations
- Updated important notifications to support speech recognition capabilities for Fcitx5 IME

---

## v3.6.2 (2025-11-08)

### New Features

- **AIDL Integration**: Added external IME AIDL integration support
- **Pro Update Control**: Added update check disable support for Pro channel

### Improvements

- Unified speech recognition post-processing logic
- Unified external speech service configuration source
- Unified some dynamic color definitions

### Bug Fixes

- Fixed type conversion and null safety handling in reflection calls

---

## v3.6.1 (2025-11-07)

### New Features

- **Privacy Control**: Added privacy control switches to disable recognition history and usage statistics
- **LLM Content Filtering**: Filter think tag content from LLM responses

### Improvements

- Optimized release page navigation interaction in update dialog
- Updated clipboard and undo button icons

---

## v3.6.0 (2025-11-07)

### New Features

- **Clipboard History Management**: Added clipboard history management panel
- **Custom Keyboard Buttons**: Added customizable keyboard buttons
- **Audio Waveform Animation**: Added real-time audio waveform animation
- **Extension Button Row**: Added keyboard extension button row and refactored status display area
- **Keyboard Space Text**: Added keyboard space text display
- **Pro Traditional Chinese**: Added Pro feature - Traditional Chinese conversion placeholder

### Improvements

- Updated extension button default configuration and default value handling
- Optimized extension button numeric panel button return logic and selection mode functionality
- Enhanced clipboard sync prompt and panel information preview experience
- Removed button color block style from settings page
- Added WaveLineView open-source library reference and license information

---

## v3.5.2 (2025-11-06)

### Improvements

- Split language fields for easier management
- Added progress display for model extraction
- Unified ASR vendor display logic

---

## v3.5.1 (2025-11-05)

### New Features

- **Zipformer Engine**: Added Zipformer local streaming speech recognition engine support
- **Paraformer Engine**: Added Paraformer local streaming speech recognition engine
- **ITN Support**: Added ITN (Inverse Text Normalization) support for Paraformer and Zipformer engines

### Improvements

- Removed SenseVoice pseudo-streaming recognition mode
- Optimized English character statistics
- Added Zipformer model information description text

---

## v3.5.0 (2025-11-05)

### New Features

- **Android 15 Support**: Upgraded to Android 15 targetSDK
- **DashScope Enhancements**:
  - Changed DashScope streaming recognition to use Fun-ASR model
  - Added DashScope region selection support
  - Integrated DashScope Java SDK for local file direct upload, optimizing Qwen model non-streaming recognition speed
- **Dual Recognition**: Added dual recognition placeholder support for Volcengine streaming recognition (Pro)
- **Debugging Features**:
  - Added debug logging for audio capture and ASR engines
  - Added latency statistics and display for non-streaming recognition requests
- **In-App Updates**: Implemented in-app APK download and installation functionality
- **Keyboard Features**:
  - Added auto-start recording feature for keyboard panel
  - Added current version information dialog
- **Open Source Acknowledgments**: Added open-source project acknowledgments and license display
- **Pro Version UI**: Added Pro version UI injection functionality
- **Soniox Enhancement**: Added context injection functionality for Soniox ASR engine (Pro)

### Improvements

- Added compression and obfuscation rules to optimize package size
- Dynamic processing interval for pseudo-streaming recognition to reduce long recording processing pressure
- Added important notification style support for update checks
- Optimized recognition history select-all effect
- Implemented unified color management

### Bug Fixes

- Fixed recording duration statistics reading method
- Fixed auto-continue installation after APK installation permission is granted
- Fixed floating ball view state management issues
- Fixed floating ball error state reset issues
- Fixed processing rotation view visibility issues
- Adjusted spacing between keyboard view buttons and info bar
- Limited clipboard preview to single line to avoid layout breaking
- Fixed stuck-in-recognition state after quick tap in long-press mode

---

> **Note**: This changelog is automatically generated from Git commit history, covering major changes from version v3.5.0 to v3.6.7.
