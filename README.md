# FlowPilot: Teachable Voice Automation for Android

> **Samsung PRISM Hackathon — Theme 03: Teachable Voice Automation**  
> *Core Concept: Teach the assistant a flow once; it replays it on command.*

---

## Overview

FlowPilot is an intelligent Android assistant that enables users to teach custom automation workflows simply by demonstrating them once on third-party Android applications. Using Android's AccessibilityService combined with large language models (LLM), FlowPilot records UI interactions, generalizes them into reusable parameterized workflows, and executes them upon natural language voice commands.

- **No Hardcoded Scripts:** Learns generalized, dynamic workflows from user demonstrations.
- **Robust UI Understanding:** Captures structural UI trees with bounding boxes, view IDs, contextual sibling texts, and screen signatures.
- **Safety First:** Strict 3-layer credential boundary detection ensuring credentials, passwords, and payment authorization screens always halt and hand control to the user.
- **Recoverable Execution:** Handles unexpected popups, app state shifts, and mid-flow parameter clarifications.

---

## Tech Stack

- **Language:** Kotlin
- **UI Framework:** Jetpack Compose + Material 3
- **Automation Core:** Android AccessibilityService + Gesture Dispatch
- **NLU & Synthesis:** Google Gemini 2.0 Flash + Text Embeddings
- **Storage:** Room / SQLite Database
- **Audio & Speech:** Android SpeechRecognizer API + Text-to-Speech (TTS)
- **Concurrency:** Kotlin Coroutines & StateFlow / SharedFlow

---

## Current Status (Phases 0 & 1 Complete)

- [x] **Phase 0: Project Setup & Architecture**
  - Android Gradle build system (SDK 34, Min SDK 26, Java 17).
  - Jetpack Compose Material 3 theming (dark/light/dynamic).
  - Complete Android Manifest with accessibility permissions and configuration XML.

- [x] **Phase 1: Accessibility Engine (The Eyes & Hands)**
  - UITreeParser: Recursive snapshot capture of UI hierarchies without memory leaks, screen signature generation, and sibling/parent context resolution.
  - ActionExecutor: Multi-strategy execution (direct clicks, ancestor climbing, gesture coordinate fallback, text input, scroll actions).
  - FlowPilotAccessibilityService: Real-time touch/event listener with text debouncing and smart home launcher filtering.
  - DebugScreen: Interactive developer console for testing real-time UI hierarchy inspection and action recording.

---

## Getting Started

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34 (Android 14)
- Physical device or Emulator running Android 8.0+ (API 26+)

### Installation
1. Clone the repository (in terminal):
   `
   git clone https://github.com/<your-username>/FlowPilot.git
   `
2. Open the project in Android Studio.
3. Allow Gradle to sync dependencies.
4. Run the app on your device or emulator.
5. Enable Accessibility:
   - Tap the red **"Accessibility Service not connected"** banner on the Home screen to jump directly to Android Settings.
   - Select **FlowPilot** and toggle it **ON**.
6. Switch to the **Debug** tab to inspect live UI hierarchies and test action recording across target applications.

---

## License
Developed for Samsung PRISM Hackathon 2026.
