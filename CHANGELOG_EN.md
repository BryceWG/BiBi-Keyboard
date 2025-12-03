# Changelog

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
