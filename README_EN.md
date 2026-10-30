# Andclaw 🤖

<p align="center">
  <img src="./icon.png" alt="Andclaw Logo" width="120">
</p>

[![Android](https://img.shields.io/badge/Android-9%2B-brightgreen?logo=android)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.10-blue?logo=kotlin)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Website](https://img.shields.io/badge/Website-andclaw.app-blue?logo=googlechrome&logoColor=white)](https://andclaw.app/)
[![Install](https://img.shields.io/badge/Download-Install-green?logo=android&logoColor=white)](https://andclaw.app/#/install)

> **Let AI use your phone like a human** — Runs entirely on-device, no Root required, no PC needed.

<p align="center">
  <a href="https://andclaw.app/"><b>🌐 Official Website</b></a> &nbsp;|&nbsp;
  <a href="https://andclaw.app/#/install"><b>📲 Download APK</b></a>
</p>

---

## 🌟 Key Features

| Feature | Description |
|---------|-------------|
| **🚫 No Root Required** | Pure accessibility service implementation |
| **💻 No PC Needed** | Runs entirely on phone, no ADB or PC companion |
| **🧠 AI Powered** | Supports Kimi Code, Moonshot, and OpenAI-compatible APIs |
| **👁️ Screen Aware** | Real-time UI hierarchy + automatic screenshot for WebView/browser |
| **🤏 Human-like Operations** | Simulate tap, swipe, long-press, text input |
| **📸 Multimedia** | Photo, video recording, screen recording, screenshot, volume control |
| **📱 Device Management** | Enterprise-grade management in Device Owner mode |
| **🤖 Remote Control** | Telegram, Feishu, WeChat ClawBot, and official QQ Bot |
| **🌍 Multi-language** | Chinese and English interface support |

## 🚀 Quick Start

### Requirements

- **Android**: Android 12 (API 31) or higher
- **Accessibility Service**: Must enable in Settings > Accessibility
- **Overlay Permission**: For emergency stop button
- **API Key**: Get from [Kimi Code](https://www.kimi.com/code/console), [Moonshot](https://platform.moonshot.cn/), or any OpenAI-compatible provider

### Installation

**Option 1: Online Install (Recommended)**

Visit [andclaw.app/#/install](https://andclaw.app/#/install) with Chrome browser.

**Option 2: Build from Source**

```bash
git clone https://github.com/andforce/Andclaw.git
cd Andclaw
./gradlew :app:installDebug
```

### Enable Device Owner (Optional but Recommended)

```bash
adb shell dpm set-device-owner com.andforce.andclaw/.DeviceAdminReceiver
```

This enables enterprise features: silent app install/uninstall, Kiosk mode, etc.

## 📱 Usage

### Remote Channels

Only one remote channel is active at a time. Configure the selected channel in MDM / AI Settings.

#### Telegram Bot

1. Message @BotFather on Telegram, create a new bot
2. Copy the Bot Token
3. Enter token in Andclaw Settings

#### WeChat ClawBot (iLink)

Scan QR code in AI Settings to bind WeChat ClawBot plugin.

#### Official QQ Bot (Agent / QQ Bot)

Create a QQ Bot in the QQ Open Platform, then enter its AppID and ClientSecret under **AI Settings → Remote Channel → QQ Bot**. The app displays credential/Gateway connection status and server error details. Set an allowed user OpenID to restrict device control to that QQ account.

QQ private chats support text commands, `/status`, `/stop`, and media replies for screenshots, images, videos, and files through the official rich-media upload API. Large files use the official multipart upload flow; platform permissions and rate limits apply.

### Supported Actions

| Action | Description |
|--------|-------------|
| `intent` | Launch apps, open URLs, make calls |
| `click` | Tap at screen coordinates |
| `swipe` | Swipe gestures |
| `long_press` | Long press |
| `text_input` | Inject text into focused field |
| `global_action` | Back, Home, Recents, Notifications |
| `screenshot` | Capture and save screenshot |
| `camera` | Take photo or record video |
| `screen_record` | Record screen |
| `volume` | Volume control |
| `dpm` | Device policy management |

## 🌍 Language Settings

Andclaw supports multiple languages:
- **中文 (Simplified Chinese)**
- **English**
- **System Default**

Change language in: Settings > Language Settings

## 📄 License

[MIT License](LICENSE)

---

<p align="center">
  Made with ❤️ by Andclaw Team
</p>