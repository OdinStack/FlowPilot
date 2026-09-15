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
- **NLU & Synthesis:** Google Gemini 2.0 Flash + Text Embeddings (`text-embedding-004`)
- **Storage:** Room / SQLite Database with kotlinx.serialization
- **Audio & Speech:** Android SpeechRecognizer API + Text-to-Speech (TTS)
- **Networking:** OkHttp 4.12
- **Concurrency:** Kotlin Coroutines & StateFlow / SharedFlow

---

## Current Status (Phases 0, 1, 2, 3, 4 & 5 Complete)

- [x] **Phase 0: Project Setup & Architecture**
  - Android Gradle build system (SDK 34, Min SDK 26, Java 17).
  - Jetpack Compose Material 3 theming (dark/light/dynamic).
  - Complete Android Manifest with accessibility permissions and configuration XML.

- [x] **Phase 1: Accessibility Engine (The Eyes & Hands)**
  - `UITreeParser`: Recursive snapshot capture of UI hierarchies without memory leaks, screen signature generation, and sibling/parent context resolution.
  - `ActionExecutor`: Multi-strategy execution (direct clicks, ancestor climbing, gesture coordinate fallback, text input, scroll actions).
  - `FlowPilotAccessibilityService`: Real-time touch/event listener with text debouncing and fallback UINode creation to prevent any dropped clicks.
  - `DebugScreen`: Interactive developer console for testing real-time UI hierarchy inspection and action recording.

- [x] **Phase 2: Teaching Pipeline & Noise Filtering**
  - `SystemStateMachine`: Reactive state machine tracking 9 global system modes (`IDLE`, `LISTENING`, `TEACHING`, `SYNTHESIZING`, `REPLAYING`, etc.).
  - `ActionCleaner`: Intelligent post-processor that filters out cross-app noise, merges rapid duplicate clicks (<350ms), consolidates keystrokes into final `TYPE` actions, and eliminates scroll-undo pairs.
  - `TeachingCoordinator`: Orchestrates demonstration capture, auto-detects target packages using click/type frequency analysis, and compiles structured `TeachingResult` objects.
  - `TeachingNotificationManager`: Ongoing notification with a **"Done (Save)"** action button so users can finish teaching without leaving the target app.

- [x] **Phase 3: Voice Interaction & Teaching UI**
  - `VoiceManager`: Coroutine-based Android `SpeechRecognizer` (ASR) with real-time partial transcription and `TextToSpeech` (TTS) response synthesis.
  - `HomeViewModel`: Coordinates voice recognition, state transitions, and teaching completion.
  - `HomeScreen`: Pulsing microphone FAB, real-time spoken subtitle display, active teaching status banner, and live action feedback.

- [x] **Phase 4: LLM Client + Workflow Synthesis (Gemini 2.0 Flash)**
  - `GeminiClient`: High-speed OkHttp REST client targeting `gemini-2.0-flash` with JSON output mode and `text-embedding-004` embedding generation.
  - `WorkflowSynthesizer`: Analyzes user utterance + cleaned UI action sequences to deduce parameterized slots (`{restaurant}`, `{item}`, etc.), semantic element descriptions, and credential boundaries.
  - `IntentProcessor`: Hybrid matching engine using vector cosine similarity (>0.88 fast match) and full LLM intent reasoning for ambiguous voice commands.
  - `FlowMatcher`: Embeds trigger utterances for rapid zero-shot comparison.

- [x] **Phase 5: Workflow Storage (Room Database)**
  - `WorkflowEntity` & `WorkflowDao`: Room entities and DAOs for persistent storage of learned workflows with serialized JSON and embedding vectors.
  - `ExecutionLogEntity`: Audit log table recording execution history, step outcomes, and stop reasons.
  - `FlowPilotDatabase`: Thread-safe Room database instance.
  - `WorkflowRepository`: High-level data repository exposing reactive `Flow<List<Workflow>>` to the UI.
  - **HomeScreen Integration**: Real-time list of learned workflows with step count, parameter count, target app badges, and delete capabilities.

---

## Configuration & API Key

To enable Gemini workflow synthesis and embedding-based intent matching:

1. Open `app/src/main/java/com/flowpilot/util/Constants.kt`.
2. Set your Google Gemini API Key:
   ```kotlin
   const val GEMINI_API_KEY = "AIzaSy..."
   ```

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

### Testing End-to-End Workflow Learning (Phases 1-5)
1. On the **Home** tab, tap the **Microphone** button.
2. Speak your automation trigger (e.g. *"Add five and two"* or *"Order a Margherita pizza from Domino's on Zomato"*).
3. The mic pulses red, transcription appears live on screen, and FlowPilot will guide you to demonstrate the action.
4. Open the target app (Calculator, Zomato, Phone, etc.) and perform the steps.
5. While demonstrating:
   - An ongoing notification appears in the status bar: `🔴 Recording (X actions captured)`.
   - Tap **Done (Save)** directly in the notification or switch back to FlowPilot and tap **Done**.
6. FlowPilot enters `SYNTHESIZING` mode:
   - Gemini 2.0 Flash converts raw clicks and inputs into a generalized, parameterized workflow.
   - Embeddings are generated and cached for future voice matching.
   - The workflow is saved into the Room database.
7. The newly learned workflow appears immediately in the **Learned Workflows** list on the Home screen, showing steps, parameters, and target app!
8. Tap the mic and say the same command or a variation — FlowPilot will recognize the saved workflow via vector semantic search!

---

## License
Developed for Samsung PRISM Hackathon 2026.
