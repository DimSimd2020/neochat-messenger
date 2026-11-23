
# NeoChat

**Post-quantum encrypted, cross-platform messenger built with native clients and a shared Rust core.**

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/DimSimd2020/neochat-messenger)](https://github.com/DimSimd2020/neochat-messenger/releases)

---

## Overview

NeoChat is an open-source messaging platform focused on privacy, low latency, and native performance across desktop and mobile devices. It combines a shared cryptographic and protocol core in Rust with native UI clients and a self-hostable backend.

## Key Features

- **Post-Quantum End-to-End Encryption** — Modern cryptographic primitives designed to protect conversations against both classical and quantum threats.
- **Native Cross-Platform Clients** — Dedicated native apps for Desktop (C++ / Qt 6), Android (Kotlin), and iOS (Swift) with feature parity across platforms.
- **On-Device Speech-to-Text** — Local voice message transcription without sending audio to third-party cloud APIs.
- **Rich Media & Messaging** — Animated emoji, high-efficiency photo/media decoding, saved messages, group chats, and real-time sync across devices.
- **Self-Hostable Architecture** — Full server and infrastructure stack included for independent deployment.

## Repository Structure


neochat-messenger/
├── apps/       # Native client applications (Desktop Qt/C++, Android Kotlin, iOS Swift)
├── core/       # Shared cryptographic, networking, and protocol core (Rust)
├── server/     # Backend messaging server (Go)
├── infra/      # Deployment configurations and infrastructure tooling
├── assets/     # Animated emoji and static media resources
├── scripts/    # Build, packaging, and release automation scripts
└── docs/       # Architecture, protocol, and developer documentation


## Getting Started

### Downloads

Prebuilt binaries and installers (including Windows MSI packages) are available on the [Releases](https://www.google.com/url?sa=E&source=gmail&q=https://github.com/DimSimd2020/neochat-messenger/releases) page.

### Building from Source

Platform-specific build instructions, environment setup, and server deployment guides are maintained in the [`docs/`](https://www.google.com/search?q=docs/) directory.

## License

The source code of NeoChat is licensed under the **GNU Affero General Public License v3.0 (AGPL-3.0)** — see the [LICENSE](https://www.google.com/search?q=LICENSE) file for details.

### Brand Assets & Trademarks (All Rights Reserved)

The AGPL-3.0 license **does not** apply to the NeoChat name, logos, icons, wordmarks, or other brand assets (including, without limitation, `messenger-logo-*.png`, `messenger-logo-*.svg`, and any derived visual identity files in this repository).

All rights to these brand assets are strictly reserved by the author (**All Rights Reserved**). In accordance with Section 7(e) of the AGPL-3.0, this license does not grant any rights under trademark law or copyright to copy, modify, redistribute, or use NeoChat's logos, icons, or brand elements for any purpose—commercial or non-commercial—without prior written permission from the copyright holder.
