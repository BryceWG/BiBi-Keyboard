<div align="center">

# 🎙️ 说点啥 (BiBi Keyboard)

**基于 AI 的智能语音输入法 | 让语音输入更自然、更高效**

### 🌐 [官方网站](https://bibi.brycewg.com)

简体中文 | [English](README_EN.md)

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://www.android.com/)
[![Language](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org/)
[![Telegram](https://img.shields.io/badge/Telegram-Join%20Chat-blue?logo=telegram)](https://t.me/+UGFobXqi2bYzMDFl)
[![zread](https://img.shields.io/badge/Ask_Zread-_.svg?style=flat&color=00b0aa&labelColor=000000&logo=data%3Aimage%2Fsvg%2Bxml%3Bbase64%2CPHN2ZyB3aWR0aD0iMTYiIGhlaWdodD0iMTYiIHZpZXdCb3g9IjAgMCAxNiAxNiIgZmlsbD0ibm9uZSIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIj4KPHBhdGggZD0iTTQuOTYxNTYgMS42MDAxSDIuMjQxNTZDMS44ODgxIDEuNjAwMSAxLjYwMTU2IDEuODg2NjQgMS42MDE1NiAyLjI0MDFWNC45NjAxQzEuNjAxNTYgNS4zMTM1NiAxLjg4ODEgNS42MDAxIDIuMjQxNTYgNS42MDAxSDQuOTYxNTZDNS4zMTUwMiA1LjYwMDEgNS42MDE1NiA1LjMxMzU2IDUuNjAxNTYgNC45NjAxVjIuMjQwMUM1LjYwMTU2IDEuODg2NjQgNS4zMTUwMiAxLjYwMDEgNC45NjE1NiAxLjYwMDFaIiBmaWxsPSIjZmZmIi8%2BCjxwYXRoIGQ9Ik00Ljk2MTU2IDEwLjM5OTlIMi4yNDE1NkMxLjg4ODEgMTAuMzk5OSAxLjYwMTU2IDEwLjY4NjQgMS42MDE1NiAxMS4wMzk5VjEzLjc1OTlDMS42MDE1NiAxNC4xMTM0IDEuODg4MSAxNC4zOTk5IDIuMjQxNTYgMTQuMzk5OUg0Ljk2MTU2QzUuMzE1MDIgMTQuMzk5OSA1LjYwMTU2IDE0LjExMzQgNS42MDE1NiAxMy43NTk5VjExLjAzOTlDNS42MDE1NiAxMC42ODY0IDUuMzE1MDIgMTAuMzk5OSA0Ljk2MTU2IDEwLjM5OTlaIiBmaWxsPSIjZmZmIi8%2BCjxwYXRoIGQ9Ik0xMy43NTg0IDEuNjAwMUgxMS4wMzg0QzEwLjY4NSAxLjYwMDEgMTAuMzk4NCAxLjg4NjY0IDEwLjM5ODQgMi4yNDAxVjQuOTYwMUMxMC4zOTg0IDUuMzEzNTYgMTAuNjg1IDUuNjAwMSAxMS4wMzg0IDUuNjAwMUgxMy43NTg0QzE0LjExMTkgNS42MDAxIDE0LjM5ODQgNS4zMTM1NiAxNC4zOTg0IDQuOTYwMVYyLjI0MDFDMTQuMzk4NCAxLjg4NjY0IDE0LjExMTkgMS42MDAxIDEzLjc1ODQgMS42MDAxWiIgZmlsbD0iI2ZmZiIvPgo8cGF0aCBkPSJNNCAxMkwxMiA0TDQgMTJaIiBmaWxsPSIjZmZmIi8%2BCjxwYXRoIGQ9Ik00IDEyTDEyIDQiIHN0cm9rZT0iI2ZmZiIgc3Ryb2tlLXdpZHRoPSIxLjUiIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIvPgo8L3N2Zz4K&logoColor=ffffff)](https://zread.ai/BryceWG/BiBi-Keyboard)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/BryceWG/BiBi-Keyboard)
![GitHub all releases](https://img.shields.io/github/downloads/BryceWG/BiBi-Keyboard/total)

[功能特性](#-功能特性) • [快速开始](#-快速开始) • [使用指南](#-使用指南) • [配置说明](#-配置说明)

</div>

---

## 🌟 Pro 版已上架

> 💎 **说点啥 Pro 版**现已正式上架！

Pro 版提供更多高级功能和更优质的使用体验。目前提供的功能:

多供应商统一热词管理/自动备份/个性化 UI 取色

全向光标滑动模式/繁体结果转换/正则后处理

如果你对 Pro 版感兴趣：

🎯 **加入我们的 [Telegram 群组](https://t.me/+UGFobXqi2bYzMDFl)**

💬 **在群里联系我购买激活码**

🎁 **Play 商店价 4.49$**

欢迎在 说点啥 3.9.0 版本后的关于-了解Pro中了解更多内容。我们非常欢迎你的体验反馈，帮助我们打磨出更好的产品！

---

## ✨ 功能特性

<table>
<tr>
<td width="50%">

### 🎤 语音识别

- **长按录音** - 简单直观的录音操作
- **智能判停** - 静音自动停止录音,无需手动操作
- **极速识别** - 松开即上传，快速返回结果
- **多引擎支持** - 11 个主流 ASR 服务（7 个云端 + 4 个本地）
- **本地 ASR 模型** - 支持离线语音识别，无需网络，保护隐私
- **AI 文本后处理** - LLM 后处理修正识别结果

</td>
<td width="50%">

### 🟣 悬浮球输入 ⭐

- **跨输入法使用** - 任何输入法都能语音输入
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
- **丰富的编辑工具** - 光标移动、选择、复制粘贴等完整编辑功能
- **智能目标选择** - 自动识别编辑目标（选中文本/上次识别/全文）
- **自定义按键** - 个性化标点符号

</td>
<td width="50%">

### 🎨 用户体验

- **Material3 设计** - 现代化界面风格，Monet 色彩适配
- **多语言支持** - 支持简体中文、繁体中文、英文、日语
- **键盘高度调节** - 三档高度自由选择
- **测试输入** - 设置页内直接测试输入法
- **统计功能** - 识别字数统计
- **振动反馈** - 按下麦克风时振动反馈
- **自动更新检查** - 每日打开软件自动检查新版本

</td>
</tr>
</table>

---

## 📱 UI 展示

<table>
<tr>
<td width="50%" align="center">
<img src="images/keyboard_view.png" alt="键盘视图" style="max-height: 200px; width: auto;"/>
<img src="images/edit_keyboard_view.png" alt="编辑键盘视图" style="max-height: 200px; width: auto;"/>
<img src="images/numpad_keyboard_view.jpg" alt="小键盘视图" style="max-height: 200px; width: auto;"/>
<br/>
<b>🎹 键盘视图</b>
<br/>
<sub>简洁的键盘界面，支持语音输入、键盘切换和自定义按键</sub>
</td>
<td width="50%" align="center">
<img src="images/settings.jpg" alt="设置界面" style="max-height: 500px; width: auto;"/>
<br/>
<b>⚙️ 设置界面</b>
<br/>
<sub>丰富的配置选项，支持多种 ASR 引擎和 LLM 后处理</sub>
</td>
</tr>
<tr>
<td width="50%" align="center">
<img src="images/floating_ball.jpg" alt="悬浮球功能" style="max-height: 500px; width: auto;"/>
<br/>
<b>🟣 悬浮球功能</b>
<br/>
<sub>跨输入法语音识别，随时随地语音输入</sub>
</td>
<td width="50%" align="center">
<img src="images/post_processing.jpg" alt="AI 后处理" style="max-height: 500px; width: auto;"/>
<br/>
<b>🤖 AI 后处理</b>
<br/>
<sub>LLM 智能优化识别结果，提升文本质量</sub>
</td>
</tr>
</table>

## 🚀 快速开始

[供应商配置文档](https://brycewg.notion.site/bibi-keyboard-providers-guide)

### 📋 系统要求

- Android 10.0 (API 29) 或更高版本
- 麦克风权限（语音识别）
- 悬浮窗权限（可选，用于悬浮球功能）
- 无障碍权限（可选，用于自动插入文本）

### 📥 安装步骤

1. **下载安装**

   - 从 [Releases](../../releases) 页面下载最新版本 APK
   - 安装到 Android 设备

2. **启用输入法**

   ```
   设置 → 系统 → 语言和输入法 → 虚拟键盘 → 管理键盘 → 启用"说点啥"
   ```

3. **配置 ASR 服务**

   - 打开说点啥设置
   - 选择 ASR 供应商（推荐：火山引擎）
   - 填入 API 密钥

4. **开始使用**
   - 在任意输入框切换到说点啥
   - 长按麦克风按钮开始语音输入

> 💡 **提示**: 首次使用建议先配置火山引擎，可获得 20 小时免费额度！

## 📖 使用指南

### 🎤 语音输入功能

<details>
<summary><b>基本操作</b></summary>

1. 长按键盘中央的麦克风按钮开始录音
2. 松开按钮后，音频会自动上传到所选的 ASR 服务进行识别
3. 识别结果会自动插入到当前输入框

**智能判停功能**:

- 开启后,检测到连续静音会自动停止录音
- 可在设置中调整静音时长(0.5-3 秒)和灵敏度(1-10 档)

**分段录音与自动续录（非流式）**:

- 录音达到各厂商的本地上限后，将自动切出当前片段并后台上传识别，同时继续录下一段，录音不中断
- 键盘面板与悬浮球在分段期间保持“录音中”外观（深色/红色），不中途闪烁到“识别中/空闲”
- 各渠道（非流式）本地上限：
  - 火山引擎：1 小时（官方 2 小时，留出安全余量）
  - SiliconFlow：20 分钟
  - ElevenLabs：20 分钟
  - OpenAI Whisper：20 分钟
  - 阿里云百炼（DashScope）：3 分钟
  - Google Gemini：4 小时（官方约 9.5 小时，留出安全余量）
  - Soniox：1 小时
  - 本地 SenseVoice：5 分钟
  - 本地 Telespeech：5 分钟
- 流式识别模式不设时长上限（支持：Volc、Soniox、DashScope、ElevenLabs、Paraformer、Zipformer）

</details>

<details>
<summary><b>AI 编辑功能</b></summary>

### 🎯 基本操作

1. 点击键盘上的编辑按钮（AI 图标）进入 AI 编辑面板
2. 语音输入编辑指令（如"删除最后一个词"、"将'你好'改为'您好'"等）
3. 说完指令后，AI 会根据指令修改选中文本或上次识别的内容

### 🎨 AI 编辑面板功能

AI 编辑面板提供了丰富的文本编辑工具，左右对称布局：

**左侧功能区：**

- **⬅️ 返回主键盘** - 退出 AI 编辑面板
- **✨ 应用预设 Prompt** - 快速应用预设的 AI 提示词
- **◀️ ▶️ 光标移动** - 左右移动光标位置
- **🔢 数字小键盘** - 切换到数字输入面板
- **📝 选择模式** - 切换文本选择模式

**右侧功能区：**

- **📄 全选** - 选中全部文本
- **⌫ 撤销/退格** - 撤销操作或删除字符
- **📋 复制/粘贴** - 复制和粘贴文本
- **🏠 🔚 移动到开头/末尾** - 快速定位到文本首尾

### 🎙️ 编辑目标选择

AI 编辑支持多种目标选择方式：

- **选中文本优先**：如果有选中文本，优先编辑选中内容
- **上次识别结果**：无选中时，默认编辑最后一次语音识别结果
- **整个输入框**：可配置为编辑整个输入框内容

### 💡 使用场景

- **文本修正**："修正错别字"、"调整语序"
- **格式优化**："添加标点"、"分段处理"
- **内容改写**："简化表达"、"润色文字"
- **批量操作**："删除重复内容"、"统一格式"

</details>

### ⌨️ 键盘按钮功能

<details>
<summary><b>主要按钮布局</b></summary>

| 按钮      | 功能                | 特殊操作                    |
| --------- | ------------------- | --------------------------- |
| 🎤 麦克风 | 长按进行语音识别    | -                           |
| 🤖 后处理 | 开启/关闭 AI 后处理 | -                           |
| 💬 提示词 | 切换 AI 提示词预设  | -                           |
| ⬇️ 收起   | 隐藏键盘界面        | -                           |
| ⌫ 退格    | 删除字符            | 上滑/左滑删除全部，下滑撤销 |
| ⚙️ 设置   | 进入设置界面        | -                           |
| 🔄 切换   | 切换到其他输入法    | -                           |
| ↵ 回车    | 换行或提交          | -                           |

</details>

<details>
<summary><b>自定义按键</b></summary>

- 键盘底部有 **4 个可自定义**的标点符号按钮
- 可在设置中自定义每个按钮显示的字符或标点
- 支持添加常用符号：`,` `.` `?` `!` 等

</details>

### 🟣 悬浮球语音识别功能 ⭐

> 完美解决语音输入与常规输入法配合使用的痛点！

<details open>
<summary><b>功能亮点</b></summary>

#### 🌐 跨输入法语音输入

- 无论当前使用哪个输入法（搜狗、百度、Gboard 等），都能通过悬浮球进行语音输入
- 无需频繁切换输入法，保持原有输入习惯的同时享受高质量语音识别
- 识别结果自动插入到当前输入框，无缝衔接

#### 💼 使用场景

| 场景          | 说明                                                     |
| ------------- | -------------------------------------------------------- |
| 💬 日常聊天   | 使用熟悉的输入法打字，需要长段文字时点击悬浮球语音输入   |
| 📄 文档编辑   | 既能用常规输入法输入格式化内容，又能快速语音录入大段文字 |
| 🌍 多语言输入 | 保持原输入法的多语言支持，同时获得高质量中文语音识别     |
| ⚡ 效率提升   | 打字和语音输入随时切换，大幅提升输入效率                 |

#### 🎯 操作流程

```
1. 设置中开启"使用悬浮球进行语音识别"
2. 授予悬浮窗权限和无障碍权限
3. 悬浮球始终显示在屏幕上（可调节透明度和大小）
4. 长按拖动悬浮球调整位置
5. 点击悬浮球开始录音，再次点击停止
6. 识别结果自动插入到当前输入框
```

#### 🎨 视觉反馈

- 🔘 **空闲状态**：麦克风图标显示为灰色
- 🔴 **录音中**：图标变为红色
- 🔵 **AI 处理中**：图标变为蓝色
- **功能菜单**：长按悬浮球可呼出功能菜单，包括切换到 AI 后处理、切换输入法等选项

</details>

<details>
<summary><b>兼容性模式</b></summary>

在部分应用中文本框会出现背景文字被误识别，导致通过无障碍服务插入文字时会附带背景文字的情况。可在“悬浮球设置”开启【悬浮球写入文字兼容性模式】：

- 开启后：在“兼容目标包名”列表中的应用里，悬浮球将尝试“全选+粘贴”写入一个不可见字符，以屏蔽背景文字。
- 兼容目标包名：每行一个完整包名，采用前缀匹配（示例：org.telegram.messenger、nu.gpu.nagram）。
- 已内置示例：org.telegram.messenger、nu.gpu.nagram。 |

</details>

<details>
<summary><b>与输入法切换悬浮球的区别</b></summary>

| 类型                | 用途                       | 适用场景                 |
| ------------------- | -------------------------- | ------------------------ |
| 🔄 输入法切换悬浮球 | 快速切换回说点啥           | 需要使用本应用键盘功能时 |
| 🎤 语音识别悬浮球   | 在任何输入法下进行语音输入 | 日常混合使用场景         |

> ⚠️ **注意**：两种模式互斥，开启语音识别悬浮球后，输入法切换悬浮球会自动隐藏

</details>

<details>
<summary><b>推荐配置方案</b></summary>

```
✅ 将常用的第三方输入法设为默认输入法（如搜狗、百度等）
✅ 开启说点啥的悬浮球语音识别功能
✅ 支持多种流式引擎：Volc、Soniox、DashScope、ElevenLabs（云端）及 Paraformer、Zipformer（本地）
✅ 日常使用第三方输入法打字，需要语音输入时点击悬浮球
✅ 享受两全其美的输入体验：熟悉的打字手感 + 高质量的语音识别
✅ 需要时切换到说点啥，享受更多智能 ASR 功能
```

如果你使用 Fctix5,可以通过语音识别按钮唤出说点啥,并可以通过说点啥切换按钮返回到 Fcitx5
(需要在说点啥设置中开启 Fcitx5 联动,并且保证 Google 语音输入服务被禁用)

</details>

---

## ⚙️ 配置说明

### 🎤 语音识别配置

[Notion 文档](https://brycewg.notion.site/bibi-keyboard-providers-guide)

### 🧠 LLM 后处理配置

<details>
<summary><b>配置参数</b></summary>

| 参数       | 说明                 | 示例                                         |
| ---------- | -------------------- | -------------------------------------------- |
| API 密钥   | LLM 服务的 API 密钥  | `sk-xxx...`                                  |
| 服务端点   | LLM API 地址         | `https://api.openai.com/v1/chat/completions` |
| 模型名称   | 使用的 LLM 模型      | `gpt-4o-mini`                                |
| 温度参数   | 控制生成文本的随机性 | `0.0 - 2.0`                                  |
| 提示词预设 | 多种预设提示词       | 可自定义添加和删除                           |
| 自动后处理 | 自动后处理开关       | 开启/关闭                                    |

</details>

### 🎛️ 其他功能配置

<details>
<summary><b>ASR 高级设置</b></summary>

- **静音自动判停**: 开启/关闭自动判停功能
- **判停时长**: 0.5-3 秒可调
- **判停灵敏度**: 低/中/高三档
- **识别提示词**: 支持 Gemini、DashScope 等引擎自定义提示词
- **语言选择**: Soniox、DashScope 支持多语言选择

</details>

<details>
<summary><b>语音预置信息</b></summary>

- 功能说明：当语音识别结果与某个“名称”完全一致时，自动用其“内容”替换，帮助快速输入常用短语、签名、地址、工号等。
- 匹配规则：先进行严格大小写匹配，若未命中再进行不区分大小写匹配；仅当整段文本与名称相同才会触发替换。
- 使用方式：在“其他设置 → 语音预置信息”中新增或删除条目，通过下拉选择要编辑的条目，分别填写“名称”和“内容”。
- 示例：名称“我的地址” → 内容“上海市徐汇区…”；当你说出“我的地址”并完成识别后，将直接替换为预设内容。

</details>

<details>
<summary><b>悬浮球设置</b></summary>

- **语音识别悬浮球**: 透明度、大小调节、兼容性模式
- **输入法切换悬浮球**: 快速切换功能
- **键盘可见性兼容模式（按应用）**: 仅对指定包名生效；在这些应用内采用“IME 包名检测”作为兼容回退，默认包含 com.tencent.mm

</details>

<details>
<summary><b>体验设置</b></summary>

- **键盘高度**: 小/中/大三档可调
- **振动反馈**: 麦克风 / 键盘按键振动
- **语言设置**: 跟随系统 / 简体中文 / 繁体中文 / 英文 / 日语
- **测试输入**: 设置页内直接测试输入法
- **自动更新**: 每日自动检查新版本

</details>

### 🎨 技术栈

---

```
Kotlin 2.2.20
Android SDK 36 (Compile SDK 36, Target SDK 35, Min SDK 29)
Material Design 3
Coroutines (异步处理)
OkHttp 5.2.1 (网络请求)
SharedPreferences (数据存储)
sherpa-onnx (本地 ASR 模型)
```

## 📄 许可证

本项目采用 **Apache 2.0 许可证**，详见 [LICENSE](LICENSE) 文件。

```
Apache 2.0 License - 自由使用、修改、分发，需保留版权声明
```

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=BryceWG/BiBi-Keyboard&type=date&legend=top-left)](https://www.star-history.com/#BryceWG/BiBi-Keyboard&type=date&legend=top-left)

## 👥 贡献者

感谢所有为本项目做出贡献的开发者！

<table>
<tr>
<td align="center">
<a href="https://github.com/BryceWG">
<img src="https://github.com/BryceWG.png" width="60px;" alt="BryceWG"/>
<br />
<sub><b>BryceWG</b></sub>
</a>
<br />
<sub>项目创建者</sub>
</td>
<td align="center">
  <a href="https://github.com/flyhunterl">
    <img src="https://github.com/flyhunterl.png" width="60px;" alt="flyhunterl" />
    <br />
    <sub><b>flyhunterl</b></sub>
  </a>
  <br />
  <sub>功能建议/实现</sub>
</td>
<td align="center">
  <a href="https://github.com/kc0ed">
    <img src="https://github.com/kc0ed.png" width="60px;" alt="kc0ed" />
    <br />
    <sub><b>kc0ed</b></sub>
  </a>
  <br />
  <sub>功能建议/实现</sub>
</td>
</tr>
</table>

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
- [SyncClipboard](https://github.com/Jeric-X/SyncClipboard) - 提供了剪贴板同步的后端服务(非软件本地运行,需要服务器)
- [Phosphor](https://github.com/phosphor-icons/homepage) - 提供了软件内几乎所有 Icons
- [WaveLineView](https://github.com/Jay-Goo/WaveLineView) - 提供了录音波形动画的实现方案，使音频可视化效果更加流畅美观

---

<div align="center">

**Made with ❤️ by BryceWG**

</div>
