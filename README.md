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

## Current Status (Phases 0, 1, 2 & 3 Complete)

- [x] **Phase 0: Project Setup & Architecture**
  - Android Gradle build system (SDK 34, Min SDK 26, Java 17).
  - Jetpack Compose Material 3 theming (dark/light/dynamic).
  - Complete Android Manifest with accessibility permissions and configuration XML.

- [x] **Phase 1: Accessibility Engine (The Eyes & Hands)**
  - `UITreeParser`: Recursive snapshot capture of UI hierarchies without memory leaks, screen signature generation, and sibling/parent context resolution.
  - `ActionExecutor`: Multi-strategy execution (direct clicks, ancestor climbing, gesture coordinate fallback, text input, scroll actions).
  - `FlowPilotAccessibilityService`: Real-time touch/event listener with text debouncing and smart home launcher filtering.
  - `DebugScreen`: Interactive developer console for testing real-time UI hierarchy inspection and action recording.

- [x] **Phase 2: Teaching Pipeline & Noise Filtering**
  - `SystemStateMachine`: Reactive state machine tracking 9 global system modes (`IDLE`, `LISTENING`, `TEACHING`, `REPLAYING`, etc.).
  - `ActionCleaner`: Intelligent post-processor that filters out cross-app noise, merges rapid duplicate clicks (<350ms), consolidates keystrokes into final `TYPE` actions, and eliminates scroll-undo pairs (+3 bonus feature).
  - `TeachingCoordinator`: Orchestrates demonstration capture, auto-detects third-party target packages, and compiles structured `TeachingResult` objects.
  - `TeachingNotificationManager`: Ongoing notification with a **"Done (Save)"** action button so users can finish teaching without leaving the target app.

- [x] **Phase 3: Voice Interaction & Teaching UI**
  - `VoiceManager`: Coroutine-based Android `SpeechRecognizer` (ASR) with real-time partial transcription and `TextToSpeech` (TTS) response synthesis.
  - `HomeViewModel`: Coordinates voice recognition, state transitions, and teaching completion.
  - `HomeScreen`: Pulsing microphone FAB, real-time spoken subtitle display, active teaching status banner, and `LearnedFlowCard` showing the cleaned action list.

---

## Getting Started

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34 (Android 14)
- Physical device or Emulator running Android 8.0+ (API 26+)

### Installation & Running
1. Clone the repository:
   ```bash
   git clone https://github.com/OdinStack/FlowPilot.git
   ```
2. Open the project in Android Studio.
3. Allow Gradle to sync dependencies.
4. Run the app on your device or emulator.
5. Enable Accessibility:
   - Tap the red **"Accessibility Service not connected"** card on the Home screen to jump directly to Android Settings.
   - Select **FlowPilot** and toggle it **ON**.

### Testing Voice & Teaching (Phases 2 & 3)
1. On the **Home** tab, tap the **Microphone** button.
2. Say your automation command (e.g. *"Order a Margherita pizza from Domino's on Zomato"* or *"Calculate total cost"*).
3. The mic pulses red, transcription appears live on screen, and FlowPilot will speak: *"Got it! Open your app and perform the task. Tap Done when finished."*
4. Switch to your target app (Calculator, Phone, Zomato, etc.) and perform the steps.
5. While demonstrating:
   - Notice the ongoing notification in the status bar: `🔴 Recording (X actions captured)`.
   - Tap **Done (Save)** directly in the notification, or return to FlowPilot and tap **Done**.
6. FlowPilot announces completion via voice and renders the **✨ Learned Workflow** card right on the Home screen, showing every cleaned action (`CLICK`, `TYPE`, `SCROLL`, etc.) with its target element!

---

## License
Developed for Samsung PRISM Hackathon 2026.
