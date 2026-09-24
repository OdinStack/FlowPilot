# Architecture

```mermaid
graph TD
    A[User speaks command] --> B[Android SpeechRecognizer]
    B --> C[Raw text]
    C --> D{First time?}
    D -->|Yes| E[Teaching Mode]
    D -->|No| F[Intent Processor]
    
    E --> G[AccessibilityService records actions]
    G --> H[ActionCleaner filters noise]
    H --> I[Gemini: Workflow Synthesis]
    I --> J[Parameterized Workflow JSON]
    J --> K[Room DB Storage]
    K --> L[Embedding computed & cached]
    
    F --> M[Embedding similarity matching]
    M --> N{Match found?}
    N -->|No| O[Unknown intent - offer to teach]
    N -->|Ambiguous| P[Gemini confirms + extracts slots]
    N -->|High confidence| Q[Extract slots directly]
    
    P --> R{Slots complete?}
    Q --> R
    R -->|Missing| S[Ask user for each missing slot]
    S --> R
    R -->|Complete| T[Replay Engine]
    
    T --> U[For each step]
    U --> V[Safety check - credential/payment]
    V -->|Unsafe| W[STOP - hand to user]
    V -->|Safe| X[Find target node - NodeMatcher]
    X --> Y{Found?}
    Y -->|No| Z[Recovery - popup dismiss / scroll / LLM cross-app / ask user]
    Z --> X
    Y -->|Yes| AA[Execute action]
    AA --> AB[Verify result]
    AB --> U
```

## Components
- **VoiceManager**: Handles voice recognition and command extraction.
- **IntentProcessor**: Determines intent from spoken text.
- **ReplayEngine**: Executes parameterized workflows step by step.
- **TeachingCoordinator**: Orchestrates recording user actions.
- **WorkflowSynthesizer**: Processes and structures learned workflows.
- **ActionCleaner**: Filters out noise and unnecessary actions from recordings.
- **NodeMatcher**: Finds target UI elements during execution.
- **SafetyDetector**: Ensures safe execution (stops at credentials/payments).
- **RecoveryManager**: Handles unexpected UI states (dismiss popups, scroll).
- **ClarificationManager**: Asks user for missing slots or confirmation.
- **CrossAppMapper**: Translates concepts across applications.

## Data Flow
- **Teaching Flow**: User intent -> Record actions -> Clean -> Synthesize -> Store -> Compute Embedding.
- **Replay Flow**: User intent -> Match embedding -> Extract slots -> Clarify (if needed) -> Replay Engine -> Safety check -> Node match -> Execute -> Verify.

## Technology Stack
- **Platform**: Android
- **Language**: Kotlin
- **UI**: Jetpack Compose
- **Database**: Room DB
- **AI/ML**: Gemini API (multi-provider: Groq → OpenRouter → Gemini)
- **Core System**: Accessibility Service

## Key Design Decisions
- **Accessibility Service**: Used for broad and systemic capability to read UI and perform actions.
- **Embedding Similarity + LLM Fallback**: Efficient and accurate matching of natural language to known actions.
- **Generic UI Patterns**: Prefer generic patterns over hardcoded selectors to increase robustness to app updates.
- **Safety Boundaries**: Crucial stop-points for sensitive actions like payments or credential entry to maintain user trust and security.
