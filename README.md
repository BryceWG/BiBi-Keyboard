<div align="center">

<img src="images/icon_new.svg" width="128" height="128" alt="Logo">

# 「说点啥」(BiBi Keyboard)

**基于 AI 的智能语音输入法 | 让语音输入更自然、更高效**

### 🌐 [官方网站](https://bibi.brycewg.com) • 📖 [使用文档](https://bibidocs.brycewg.com)

简体中文 | [English](README_EN.md)

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://www.android.com/)
[![Language](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org/)
[![Telegram](https://img.shields.io/badge/Telegram-Join%20Chat-blue?logo=telegram)](https://t.me/+UGFobXqi2bYzMDFl)
[![zread](https://img.shields.io/badge/Ask_Zread-_.svg?style=flat&color=00b0aa&labelColor=000000&logo=data%3Aimage%2Fsvg%2Bxml%3Bbase64%2CPHN2ZyB3aWR0aD0iMTYiIGhlaWdodD0iMTYiIHZpZXdCb3g9IjAgMCAxNiAxNiIgZmlsbD0ibm9uZSIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIj4KPHBhdGggZD0iTTQuOTYxNTYgMS42MDAxSDIuMjQxNTZDMS44ODgxIDEuNjAwMSAxLjYwMTU2IDEuODg2NjQgMS42MDE1NiAyLjI0MDFWNC45NjAxQzEuNjAxNTYgNS4zMTM1NiAxLjg4ODEgNS42MDAxIDIuMjQxNTYgNS42MDAxSDQuOTYxNTZDNS4zMTUwMiA1LjYwMDEgNS42MDE1NiA1LjMxMzU2IDUuNjAxNTYgNC45NjAxVjIuMjQwMUM1LjYwMTU2IDEuODg2NjQgNS4zMTUwMiAxLjYwMDEgNC45NjE1NiAxLjYwMDFaIiBmaWxsPSIjZmZmIi8%2BCjxwYXRoIGQ9Ik00Ljk2MTU2IDEwLjM5OTlIMi4yNDE1NkMxLjg4ODEgMTAuMzk5OSAxLjYwMTU2IDEwLjY4NjQgMS42MDE1NiAxMS4wMzk5VjEzLjc1OTlDMS42MDE1NiAxNC4xMTM0IDEuODg4MSAxNC4zOTk5IDIuMjQxNTYgMTQuMzk5OUg0Ljk2MTU2QzUuMzE1MDIgMTQuMzk5OSA1LjYwMTU2IDE0LjExMzQgNS42MDE1NiAxMy43NTk5VjExLjAzOTlDNS42MDE1NiAxMC42ODY0IDUuMzE1MDIgMTAuMzk5OSA0Ljk2MTU2IDEwLjM5OTlaIiBmaWxsPSIjZmZmIi8%2BCjxwYXRoIGQ9Ik0xMy43NTg0IDEuNjAwMUgxMS4wMzg0QzEwLjY4NSAxLjYwMDEgMTAuMzk4NCAxLjg4NjY0IDEwLjM5ODQgMi4yNDAxVjQuOTYwMUMxMC4zOTg0IDUuMzEzNTYgMTAuNjg1IDUuNjAwMSAxMS4wMzg0IDUuNjAwMUgxMy43NTg0QzE0LjExMTkgNS42MDAxIDE0LjM5ODQgNS4zMTM1NiAxNC4zOTg0IDQuOTYwMVYyLjI0MDFDMTQuMzk4NCAxLjg4NjY0IDE0LjExMTkgMS42MDAxIDEzLjc1ODQgMS42MDAxWiIgZmlsbD0iI2ZmZiIvPgo8cGF0aCBkPSJNNCAxMkwxMiA0TDQgMTJaIiBmaWxsPSIjZmZmIi8%2BCjxwYXRoIGQ9Ik00IDEyTDEyIDQiIHN0cm9rZT0iI2ZmZiIgc3Ryb2tlLXdpZHRoPSIxLjUiIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIvPgo8L3N2Zz4K&logoColor=ffffff)](https://zread.ai/BryceWG/BiBi-Keyboard)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/BryceWG/BiBi-Keyboard)
![GitHub all releases](https://img.shields.io/github/downloads/BryceWG/BiBi-Keyboard/total)

[功能特性](#-功能特性) • [界面预览](#-界面预览) • [快速开始](#-快速开始)

</div>

## 📱 界面预览

<div align="center">
<img src="images/readme-showcase.png" width="100%" alt="说点啥的新手引导、语音键盘与智能设置界面">
</div>

<table>
<tr>
<td width="33%" align="center"><b>开箱即用</b><br/><sub>分步引导完成输入法与权限配置</sub></td>
<td width="34%" align="center"><b>语音即输</b><br/><sub>打开键盘，长按麦克风即可自然表达</sub></td>
<td width="33%" align="center"><b>智能增强</b><br/><sub>识别、AI 后处理与历史记录集中管理</sub></td>
</tr>
</table>

## ✨ 功能特性

<table>
<tr>
<td width="50%">

### 🎤 语音识别

- **长按录音** - 简单直观的录音操作
- **智能判停** - 静音自动停止录音，无需手动操作
- **极速识别** - 松开即上传，快速返回结果
- **多引擎支持** - 18 个 ASR 供应商（12 个云端 + 6 个本地）
- **本地 ASR 模型** - SenseVoice / FunASR Nano / Qwen3-ASR / Parakeet / FireRedASR / X-ASR，离线可用
- **主备与本地兜底** - 支持备用引擎与本地备用驻留，网络不稳时更可靠
- **AI 文本后处理** - LLM 后处理修正识别结果；历史可缓存音频并重跑识别/后处理

</td>
<td width="50%">

### 🟣 悬浮球输入 ⭐

- **跨输入法使用** - 任何输入法都能语音输入
- **长按录音** - 支持按住录音、松手停止
- **无缝集成** - 保持原有输入习惯
- **自动插入** - 识别结果自动填入
- **兼容性模式** - 支持 Telegram、抖音等特殊应用
- **视觉反馈** - 录音/处理状态一目了然

</td>
</tr>
<tr>
<td width="50%">

### 📝 智能输入

- **AI 编辑面板** - 专用编辑界面，语音指令编辑文本
- **自定义键盘布局** - 自由调整按键池与面板布局
- **剪贴板同步与历史** - 对接 SyncClipboard，支持上传/拉取与应用内历史
- **IME Bridge** - 通过 LSPosed/LSPatch 模块，在第三方输入法中录音、上屏与剪贴板同步
- **小企鹅/同文输入法联动** - 支持修改版小企鹅/同文直接调用「说点啥」识别能力
- **外部语音输入接口** - 支持第三方应用通过 SpeechRecognizer / AIDL 调用 [开源接口测试工具](https://github.com/BryceWG/SpeechRecognizer-Tester)

</td>
<td width="50%">

### 🎨 用户体验

- **Material3 / Miuix** - 现代化设置界面，支持 Monet 与 Miuix 风格
- **多语言支持** - 简体中文、繁体中文、英文、日语、阿拉伯语
- **键盘高度调节** - 三档高度自由选择
- **识别完成后自动回车** - 聊天场景可自动发送
- **备份与恢复** - 配置一键导出/导入
- **振动反馈** - 麦克风与按键振动反馈
- **自动更新检查** - 每日打开软件自动检查新版本

</td>
</tr>
</table>

## 🌟 Pro 版已上架

> 💎 **「说点啥」Pro 版**现已正式上架 Play 商店，买断价仅 $5.49！

Pro 版提供更多高级功能和更优质的使用体验（热词、简繁转换、畅说模式、WebDAV 自动备份等）。
欢迎在应用「关于 → 了解 Pro」或 [Pro 功能](https://bibidocs.brycewg.com/pro/features.html) 文档中了解更多内容。我们非常欢迎你的体验反馈，帮助我们打磨出更好的产品！

如果你对「说点啥」感兴趣，也加入我们的 [Telegram 群组](https://t.me/+UGFobXqi2bYzMDFl)了解更多信息

## 🚀 快速开始

[供应商配置文档](https://bibidocs.brycewg.com/getting-started/asr-providers.html)

### 📋 系统要求

- Android 8.0 (API 26) 或更高版本
- 麦克风权限（语音识别）
- 悬浮窗权限（可选，用于悬浮球功能）
- 无障碍权限（可选，用于自动插入文本）
- LSPosed / LSPatch（可选，用于与任意第三方输入法联动）

### 📥 安装步骤

1. **下载安装**
   - 从 [Releases](../../releases) 页面下载最新版本 APK
   - 安装到 Android 设备

2. **启用输入法**

   ```
   设置 → 系统 → 语言和输入法 → 虚拟键盘 → 管理键盘 → 启用"「说点啥」"
   ```

3. **配置 ASR 服务**
   - 打开说点啥设置
   - 选择 ASR 供应商（推荐：火山引擎）
   - 填入 API 密钥

4. **开始使用**
   - 在任意输入框切换到说点啥
   - 长按麦克风按钮开始语音输入

> 💡 **提示**: 首次使用建议先配置火山引擎，可获得 20 小时免费额度！

### 🎨 技术栈

```
Kotlin 2.3.21
Android SDK (Compile SDK 37, Target SDK 35, Min SDK 26)
Jetpack Compose + Material Design 3 / Miuix
Coroutines (异步处理)
OkHttp 5.3.2 (网络请求)
SharedPreferences (数据存储)
sherpa-onnx (本地 ASR 模型)
```

## 📄 许可证

本项目采用 **Apache 2.0 许可证**，详见 [LICENSE](LICENSE) 文件。

```
Apache 2.0 License - 自由使用、修改、分发，需保留版权声明
```

## Star History

<a href="https://www.star-history.com/?type=date&legend=top-left&repos=BryceWG%2FBiBi-Keyboard">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=BryceWG/BiBi-Keyboard&type=date&theme=dark&legend=top-left&sealed_token=Qzk3vW5gzhrFU38x3S-wQ2YRnP03Q4pKh5p4GSqlL0Y9Lq2CX8eCvAK8C_-K08ofqKJV5xmmXgEIzM48LKBX8Ptp41I_7sKYlE33OcG_bFxIit_u3jIvtw" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=BryceWG/BiBi-Keyboard&type=date&legend=top-left&sealed_token=Qzk3vW5gzhrFU38x3S-wQ2YRnP03Q4pKh5p4GSqlL0Y9Lq2CX8eCvAK8C_-K08ofqKJV5xmmXgEIzM48LKBX8Ptp41I_7sKYlE33OcG_bFxIit_u3jIvtw" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=BryceWG/BiBi-Keyboard&type=date&legend=top-left&sealed_token=Qzk3vW5gzhrFU38x3S-wQ2YRnP03Q4pKh5p4GSqlL0Y9Lq2CX8eCvAK8C_-K08ofqKJV5xmmXgEIzM48LKBX8Ptp41I_7sKYlE33OcG_bFxIit_u3jIvtw" />
 </picture>
</a>

## ☕ 赞赏支持

如果这个项目对你有帮助，请给个 Star ⭐️ 也欢迎请我喝杯咖啡或者购买 Pro 版 ☕️

<div align="center">
<img src="images/wechat.jpg" alt="微信赞赏码" width="300"/>
<br/>
<sub>微信扫码赞赏</sub>
</div>

## 🙏 致谢

感谢以下开源项目为本项目提供的技术支持：

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) - 提供了本地 ASR 模型的技术方案支持，使离线语音识别成为可能
- [TEN-VAD](https://github.com/TEN-framework/ten-vad) - 提供了现有的 VAD 模型支持
- [SyncClipboard](https://github.com/Jeric-X/SyncClipboard) - 提供了剪贴板同步的后端服务(非软件本地运行,需要服务器)
- [Phosphor](https://github.com/phosphor-icons/homepage) - 提供了软件内几乎所有 Icons
- [miuix](https://github.com/compose-miuix-ui/miuix) - 提供了 Miuix 风格的 Compose UI 组件、图标与偏好设置组件
- [WaveLineView](https://github.com/Jay-Goo/WaveLineView) - 提供了录音波形动画的实现方案，使音频可视化效果更加流畅美观
- 感谢《补全计划》图标包作者南㲺为本项目设计了全新的应用图标

<div align="center">

**Made with ❤️ by BryceWG**

</div>
