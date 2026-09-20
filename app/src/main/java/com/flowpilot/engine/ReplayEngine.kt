package com.flowpilot.engine

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.flowpilot.accessibility.ActionExecutor
import com.flowpilot.accessibility.FlowPilotAccessibilityService
import com.flowpilot.accessibility.NodeMatcher
import com.flowpilot.accessibility.SafetyDetector
import com.flowpilot.accessibility.ScreenAnalyzer
import com.flowpilot.data.models.StepType
import com.flowpilot.data.models.TargetSpec
import com.flowpilot.data.models.Workflow
import com.flowpilot.data.models.WorkflowStep
import com.flowpilot.util.Constants
import com.flowpilot.util.normalizeNumberWords
import kotlinx.coroutines.delay

class ReplayEngine(
    private val serviceProvider: () -> FlowPilotAccessibilityService? = { FlowPilotAccessibilityService.instance },
    private val nodeMatcher: NodeMatcher = NodeMatcher(),
    private val safetyDetector: SafetyDetector = SafetyDetector(),
    private val screenAnalyzer: ScreenAnalyzer = ScreenAnalyzer(),
    private val stateMachine: SystemStateMachine,
    private val recoveryManager: RecoveryManager? = null
) {

    companion object {
        private const val TAG = "ReplayEngine"
    }

    data class StepResult(
        val stepIndex: Int,
        val success: Boolean,
        val action: String,
        val details: String,
        val durationMs: Long
    )

    data class ReplayResult(
        val success: Boolean,
        val stepsCompleted: Int,
        val totalSteps: Int,
        val stopReason: String?,
        val stepResults: List<StepResult>
    )

    /**
     * Execute a complete workflow.
     * @param workflow The workflow to replay
     * @param slotValues The filled slot values (e.g., {"item": "Farmhouse", "restaurant": "Domino's"})
     * @return Result of the replay
     */
    suspend fun execute(
        workflow: Workflow,
        slotValues: Map<String, String> = emptyMap()
    ): ReplayResult {
        val service = serviceProvider()
        if (service == null) {
            Log.e(TAG, "Cannot replay: FlowPilotAccessibilityService is not connected")
            stateMachine.setError("Accessibility Service not connected. Enable FlowPilot in Accessibility Settings.")
            return ReplayResult(
                success = false,
                stepsCompleted = 0,
                totalSteps = workflow.steps.size,
                stopReason = "Accessibility Service not connected",
                stepResults = emptyList()
            )
        }

        val actionExecutor = service.actionExecutor
        val stepResults = mutableListOf<StepResult>()

        // 1. Resolve effective slot values (workflow default values + runtime user slots)
        val effectiveSlots = mutableMapOf<String, String>()
        for ((name, slot) in workflow.slots) {
            slot.defaultValue?.takeIf { it.isNotBlank() }?.let { effectiveSlots[name] = it }
        }
        effectiveSlots.putAll(slotValues)

        Log.i(TAG, "Beginning replay of '${workflow.name}' (${workflow.steps.size} steps). Effective slots: $effectiveSlots")
        stateMachine.transition(SystemMode.REPLAYING, "Starting \"${workflow.name}\"...")

        // 2. Ensure target app is launched and active in foreground before starting steps
        val targetPkg = workflow.targetAppPackage.trim()
        val firstStepIsOpenApp = workflow.steps.firstOrNull()?.type == StepType.OPEN_APP

        if (targetPkg.isNotBlank() && !firstStepIsOpenApp) {
            val currentPkg = service.rootInActiveWindow?.packageName?.toString() ?: ""
            if (!isPackageInForeground(currentPkg, targetPkg)) {
                Log.w(TAG, "Target app '$targetPkg' not in foreground (current: '$currentPkg'). Launching...")
                stateMachine.transition(SystemMode.REPLAYING, "Opening ${targetPkg.substringAfterLast('.')}...")
                launchAndWaitForApp(service, actionExecutor, targetPkg)
            }
        }

        for (step in workflow.steps) {
            val stepDesc = step.description.ifBlank { "${step.type.name} step ${step.index + 1}" }
            stateMachine.updateStep(step.index, workflow.steps.size, stepDesc)

            val startTime = System.currentTimeMillis()

            // ─── PRE-CHECK: Safety (Credential/Payment) ───
            val currentRoot = service.rootInActiveWindow
            if (currentRoot != null) {
                val safety = safetyDetector.check(currentRoot)
                val isSafetyBoundary = safety !is SafetyDetector.SafetyResult.Safe
                val isMarkedCredential = step.isCredentialBoundary

                if (isSafetyBoundary || isMarkedCredential) {
                    val reason = when {
                        safety is SafetyDetector.SafetyResult.Credential -> safety.reason
                        safety is SafetyDetector.SafetyResult.Payment -> safety.reason
                        isMarkedCredential -> "Step marked as credential/payment boundary"
                        else -> "Security boundary reached"
                    }

                    Log.w(TAG, "Safety boundary detected at step ${step.index}: $reason")
                    stateMachine.transition(SystemMode.PAUSED, "Payment/login screen reached. Your turn!")

                    stepResults.add(
                        StepResult(
                            stepIndex = step.index,
                            success = true,
                            action = step.type.name,
                            details = "Stopped safely at credential boundary: $reason",
                            durationMs = System.currentTimeMillis() - startTime
                        )
                    )

                    return ReplayResult(
                        success = true,
                        stepsCompleted = step.index,
                        totalSteps = workflow.steps.size,
                        stopReason = "Credential/payment boundary reached",
                        stepResults = stepResults
                    )
                }

                // ─── PRE-CHECK: Dismiss Obstructive Popups ───
                if (screenAnalyzer.hasPopupOverlay(currentRoot)) {
                    Log.d(TAG, "Popup/dialog overlay detected, attempting dismissal...")
                    val dismissBtn = screenAnalyzer.findDismissButton(currentRoot)
                    if (dismissBtn != null) {
                        actionExecutor.click(dismissBtn)
                        delay(1000)
                    } else {
                        actionExecutor.pressBack()
                        delay(500)
                    }
                }
            }

            // ─── EXECUTE STEP WITH RECOVERY ───
            var stepResult: StepResult? = null
            val maxAttempts = if (recoveryManager != null) RecoveryManager.MAX_RECOVERY_ATTEMPTS else 1

            for (attempt in 1..maxAttempts) {
                val result = executeStep(service, actionExecutor, workflow, step, effectiveSlots)
                val duration = System.currentTimeMillis() - startTime
                val finalResult = result.copy(durationMs = duration)

                if (finalResult.success) {
                    stepResult = finalResult
                    break
                }

                // If no recovery manager, fail immediately
                if (recoveryManager == null || attempt == maxAttempts) {
                    // On last attempt, try to generate a helpful stuck question
                    if (recoveryManager != null) {
                        try {
                            val root = service.rootInActiveWindow
                            if (root != null) {
                                val question = recoveryManager.generateStuckQuestion(root, step, effectiveSlots)
                                Log.w(TAG, "Genuinely stuck: $question")
                                // Store the question in the step details for the UI
                                stepResult = finalResult.copy(details = "${finalResult.details} | Help: $question")
                            } else {
                                stepResult = finalResult
                            }
                        } catch (e: Exception) {
                            stepResult = finalResult
                        }
                    } else {
                        stepResult = finalResult
                    }
                    break
                }

                // Ask RecoveryManager what to do
                Log.i(TAG, "Step ${step.index} failed (attempt $attempt/$maxAttempts). Consulting RecoveryManager...")
                val recoveryAction = recoveryManager.analyzeAndRecover(step, attempt, effectiveSlots)

                when (recoveryAction) {
                    is RecoveryManager.RecoveryAction.RetryStep,
                    is RecoveryManager.RecoveryAction.DismissAndRetry -> {
                        delay(1000)
                        continue
                    }
                    is RecoveryManager.RecoveryAction.CredentialStop -> {
                        stepResults.add(StepResult(
                            stepIndex = step.index,
                            success = true,
                            action = step.type.name,
                            details = "Stopped at credential boundary during recovery",
                            durationMs = System.currentTimeMillis() - startTime
                        ))
                        return ReplayResult(
                            success = true,
                            stepsCompleted = step.index,
                            totalSteps = workflow.steps.size,
                            stopReason = "Credential/payment boundary reached",
                            stepResults = stepResults
                        )
                    }
                    is RecoveryManager.RecoveryAction.AskUser -> {
                        stepResult = finalResult.copy(details = "${finalResult.details} | Help: ${recoveryAction.question}")
                        break // Exit retry loop — report failure with the question
                    }
                    is RecoveryManager.RecoveryAction.Abort -> {
                        stepResult = finalResult.copy(details = recoveryAction.reason)
                        break
                    }
                }
            }

            val finalStepResult = stepResult!!
            stepResults.add(finalStepResult)

            if (!finalStepResult.success) {
                Log.e(TAG, "Step ${step.index} failed after $maxAttempts attempts: ${finalStepResult.details}")
                stateMachine.setError("Failed at step ${step.index + 1}: ${finalStepResult.details}")
                return ReplayResult(
                    success = false,
                    stepsCompleted = step.index,
                    totalSteps = workflow.steps.size,
                    stopReason = finalStepResult.details,
                    stepResults = stepResults
                )
            }

            // Settle delay between steps for UI animations
            delay(350L)
        }

        Log.i(TAG, "Workflow '${workflow.name}' replay completed successfully!")
        stateMachine.transition(SystemMode.IDLE, "✅ Replayed \"${workflow.name}\" successfully!")
        return ReplayResult(
            success = true,
            stepsCompleted = workflow.steps.size,
            totalSteps = workflow.steps.size,
            stopReason = null,
            stepResults = stepResults
        )
    }

    /**
     * Execute a single workflow step.
     */
    private suspend fun executeStep(
        service: FlowPilotAccessibilityService,
        actionExecutor: ActionExecutor,
        workflow: Workflow,
        step: WorkflowStep,
        slotValues: Map<String, String>
    ): StepResult {
        stateMachine.transition(
            SystemMode.REPLAYING,
            step.description.ifBlank { "Executing ${step.type.name}" }
        )

        return when (step.type) {
            StepType.OPEN_APP -> executeOpenApp(service, actionExecutor, workflow, step)
            StepType.CLICK -> executeClick(service, actionExecutor, step, slotValues)
            StepType.TYPE -> executeType(service, actionExecutor, step, slotValues)
            StepType.SCROLL -> executeScroll(service, actionExecutor, step)
            StepType.FIND_AND_CLICK -> executeFindAndClick(service, actionExecutor, step, slotValues)
            StepType.BACK -> executeBack(actionExecutor, step)
            StepType.CONDITIONAL -> executeConditional(service, actionExecutor, step, slotValues)
        }
    }

    private suspend fun executeOpenApp(
        service: FlowPilotAccessibilityService,
        actionExecutor: ActionExecutor,
        workflow: Workflow,
        step: WorkflowStep
    ): StepResult {
        val targetPkg = step.target.text?.takeIf { it.isNotBlank() }
            ?: step.value?.takeIf { it.isNotBlank() }
            ?: workflow.targetAppPackage.takeIf { it.isNotBlank() }
            ?: return StepResult(step.index, false, "OPEN_APP", "No target package specified", 0)

        Log.w(TAG, "Opening target app: $targetPkg")
        val success = launchAndWaitForApp(service, actionExecutor, targetPkg)
        return StepResult(step.index, success, "OPEN_APP", "Opened $targetPkg", 0)
    }

    private suspend fun executeClick(
        service: FlowPilotAccessibilityService,
        actionExecutor: ActionExecutor,
        step: WorkflowStep,
        slots: Map<String, String>
    ): StepResult {
        // 1. Try single element direct click on current screen
        val directResult = findAndAct(service, step, slots, "CLICK") { node ->
            actionExecutor.click(node)
        }
        if (directResult.success) {
            return directResult
        }

        // 2. If element was not found and scrollToFind is enabled: swipe scroll and retry
        if (step.scrollToFind) {
            Log.w(TAG, "Target not visible on screen; performing swipe scroll to locate element...")
            for (attempt in 1..Constants.MAX_SCROLL_ATTEMPTS) {
                actionExecutor.swipeScroll(forward = true)
                delay(800)

                val scrollResult = findAndAct(service, step, slots, "CLICK") { node ->
                    actionExecutor.click(node)
                }
                if (scrollResult.success) {
                    Log.w(TAG, "Found and clicked element after $attempt swipe scrolls")
                    return scrollResult
                }
            }
        }

        // 3. Fallback: if element not found, check if it's a multi-character keypad entry (e.g. "10", "42")
        val resolved = resolveTargetText(step, slots)
        if (resolved != null) {
            val keypadResult = trySequentialKeypadClick(service, actionExecutor, step, resolved)
            if (keypadResult != null) {
                return keypadResult
            }
        }

        val targetDesc = step.target.semantic ?: step.target.textContains ?: step.target.text ?: step.target.resourceId ?: "target element"
        val errorDetail = if (step.scrollToFind) {
            "Could not locate \"$targetDesc\" even after scrolling down ${Constants.MAX_SCROLL_ATTEMPTS} times."
        } else {
            "Element not found on current screen: \"$targetDesc\"."
        }
        return StepResult(step.index, false, "CLICK", errorDetail, 0)
    }

    private suspend fun executeType(
        service: FlowPilotAccessibilityService,
        actionExecutor: ActionExecutor,
        step: WorkflowStep,
        slots: Map<String, String>
    ): StepResult {
        var textToType = step.value ?: return StepResult(step.index, false, "TYPE", "No text value specified", 0)
        for ((key, value) in slots) {
            textToType = textToType.replace("{$key}", value, ignoreCase = true)
        }
        textToType = textToType.normalizeNumberWords()

        // 1. If target is a Button/keypad or non-editable control, or numeric input on a calculator:
        // Execute sequential keypad clicks directly!
        val isNumericInput = textToType.trim().matches(Regex("^[0-9.+\\-/*×÷=]+$"))
        val isKeypadControl = step.target.className?.contains("Button", ignoreCase = true) == true ||
                              step.target.isEditable == false ||
                              (isNumericInput && service.rootInActiveWindow?.packageName?.contains("calc", ignoreCase = true) == true)

        if (isKeypadControl && isNumericInput) {
            val keypadResult = trySequentialKeypadClick(service, actionExecutor, step, textToType)
            if (keypadResult != null) {
                return keypadResult
            }
        }

        // 2. Otherwise find the editable field and setText
        val result = findAndAct(service, step, slots, "TYPE") { node ->
            actionExecutor.setText(node, textToType)
        }
        if (result.success) {
            // Dismiss soft keyboard ONLY if an actual soft keyboard window is open on screen
            dismissSoftKeyboardIfPresent(service, actionExecutor)
            return result
        }

        // Fallback for non-editable fields if not tried yet: try sequential keypad clicks
        val keypadResult = trySequentialKeypadClick(service, actionExecutor, step, textToType)
        if (keypadResult != null) {
            return keypadResult
        }

        return result
    }

    private suspend fun dismissSoftKeyboardIfPresent(
        service: FlowPilotAccessibilityService,
        actionExecutor: ActionExecutor
    ) {
        try {
            val hasSoftKeyboard = service.windows?.any {
                it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            } == true
            if (hasSoftKeyboard) {
                delay(250)
                actionExecutor.pressBack()
                delay(250)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking soft keyboard window", e)
        }
    }

    private suspend fun executeScroll(
        service: FlowPilotAccessibilityService,
        actionExecutor: ActionExecutor,
        step: WorkflowStep
    ): StepResult {
        val forward = !step.value.equals("UP", ignoreCase = true)
        val root = service.rootInActiveWindow
        val scrollable = root?.let { findScrollableNode(it) }
        val success = actionExecutor.scroll(scrollable, forward)
        return StepResult(step.index, success, "SCROLL", "Scrolled ${if (forward) "down" else "up"}", 0)
    }

    private suspend fun executeFindAndClick(
        service: FlowPilotAccessibilityService,
        actionExecutor: ActionExecutor,
        step: WorkflowStep,
        slots: Map<String, String>
    ): StepResult {
        val root = service.rootInActiveWindow ?: return StepResult(step.index, false, "FIND_AND_CLICK", "Active window not available", 0)

        // First attempt direct match
        var match = nodeMatcher.findBestMatch(root, step.target, slots)

        // Scroll and search if element is not yet on screen
        if (match == null && step.scrollToFind) {
            Log.w(TAG, "Target not visible; scrolling to find element...")
            for (attempt in 1..Constants.MAX_SCROLL_ATTEMPTS) {
                actionExecutor.swipeScroll(forward = true)
                delay(800)

                val newRoot = service.rootInActiveWindow ?: continue
                match = nodeMatcher.findBestMatch(newRoot, step.target, slots)
                if (match != null) {
                    Log.w(TAG, "Found element after $attempt scrolls")
                    break
                }
            }
        }

        if (match == null) {
            val resolved = resolveTargetText(step, slots)
            if (resolved != null) {
                val keypadResult = trySequentialKeypadClick(service, actionExecutor, step, resolved)
                if (keypadResult != null) {
                    return keypadResult
                }
            }

            val targetDesc = step.target.semantic ?: step.target.textContains ?: step.target.text ?: "target element"
            val errorDetail = if (step.scrollToFind) {
                "Could not locate \"$targetDesc\" even after scrolling down ${Constants.MAX_SCROLL_ATTEMPTS} times."
            } else {
                "Could not locate \"$targetDesc\" on screen."
            }
            return StepResult(step.index, false, "FIND_AND_CLICK", errorDetail, 0)
        }

        val success = actionExecutor.click(match.node)
        return StepResult(
            step.index,
            success,
            "FIND_AND_CLICK",
            "Clicked: ${match.matchDetails} (confidence: ${"%.2f".format(match.score)})",
            0
        )
    }

    private suspend fun executeBack(
        actionExecutor: ActionExecutor,
        step: WorkflowStep
    ): StepResult {
        val success = actionExecutor.pressBack()
        return StepResult(step.index, success, "BACK", "Pressed back button", 0)
    }

    private suspend fun executeConditional(
        service: FlowPilotAccessibilityService,
        actionExecutor: ActionExecutor,
        step: WorkflowStep,
        slots: Map<String, String>
    ): StepResult {
        val quantityStr = slots["quantity"] ?: slots["count"]
        val quantity = quantityStr?.toIntOrNull() ?: 1

        if (quantity > 1) {
            val root = service.rootInActiveWindow ?: return StepResult(step.index, false, "CONDITIONAL", "Active window not available", 0)
            val plusButton = nodeMatcher.findBestMatch(root, step.target, slots)

            if (plusButton != null) {
                repeat(quantity - 1) {
                    actionExecutor.click(plusButton.node)
                    delay(400)
                }
                return StepResult(step.index, true, "CONDITIONAL", "Incremented quantity to $quantity", 0)
            } else {
                return StepResult(step.index, false, "CONDITIONAL", "Increment button not found for quantity $quantity", 0)
            }
        }

        return StepResult(step.index, true, "CONDITIONAL", "Condition skipped (quantity <= 1)", 0)
    }

    /**
     * Helper to find a target node with retry attempts, then perform an action on it.
     */
    private suspend fun findAndAct(
        service: FlowPilotAccessibilityService,
        step: WorkflowStep,
        slots: Map<String, String>,
        actionName: String,
        action: suspend (AccessibilityNodeInfo) -> Boolean
    ): StepResult {
        var lastError = "Element not found"

        for (attempt in 1..Constants.MAX_RETRY_ATTEMPTS) {
            val root = service.rootInActiveWindow
            if (root == null) {
                delay(500)
                continue
            }

            val match = nodeMatcher.findBestMatch(root, step.target, slots)
            if (match != null) {
                val success = action(match.node)
                if (success) {
                    return StepResult(
                        step.index,
                        true,
                        actionName,
                        "Success: ${match.matchDetails} (confidence: ${"%.2f".format(match.score)})",
                        0
                    )
                } else {
                    lastError = "Action failed on matched element"
                }
            } else {
                val desc = step.target.semantic ?: step.target.textContains ?: step.target.text ?: step.target.resourceId ?: "target"
                lastError = "Element not found: $desc"
            }

            delay(500)
        }

        return StepResult(step.index, false, actionName, lastError, 0)
    }

    private fun findScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (depth > 25) return null
            try {
                if (node.isScrollable) return node
                for (i in 0 until node.childCount) {
                    val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                    val result = search(child, depth + 1)
                    if (result != null) return result
                    child.recycle()
                }
            } catch (e: Exception) {
                // Stale node safe
            }
            return null
        }
        return search(root, 0)
    }

    private fun resolveTargetText(step: WorkflowStep, slots: Map<String, String>): String? {
        val raw = step.target.text ?: step.value ?: step.target.textContains ?: step.target.semantic
        if (raw.isNullOrBlank()) return null
        var resolved: String = raw
        for ((k, v) in slots) {
            resolved = resolved.replace("{$k}", v, ignoreCase = true)
        }
        return resolved.trim()
    }

    /**
     * Find a clickable keypad node whose text, description, or resource ID EXACTLY matches the target digit/symbol.
     * Used for calculator keypad where we need precise digit matching and must not click formula display.
     */
    private fun findExactTextNode(root: AccessibilityNodeInfo, targetText: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (depth > 25) return null
            try {
                if (!node.isVisibleToUser) return null
                val nodeText = node.text?.toString()?.trim() ?: ""
                val nodeDesc = node.contentDescription?.toString()?.trim() ?: ""
                val rawId = node.viewIdResourceName ?: ""
                val nodeResId = rawId.substringAfterLast('/')

                val isClickableOrButton = node.isClickable ||
                    node.className?.toString()?.contains("Button", ignoreCase = true) == true

                val isExactMatch = nodeText == targetText ||
                    nodeDesc.equals(targetText, ignoreCase = true) ||
                    nodeResId.equals("digit_$targetText", ignoreCase = true) ||
                    nodeResId.equals("btn_$targetText", ignoreCase = true) ||
                    nodeResId.equals("key_$targetText", ignoreCase = true)

                if (isExactMatch && isClickableOrButton) {
                    return node
                }

                for (i in 0 until node.childCount) {
                    val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                    val result = search(child, depth + 1)
                    if (result != null) return result
                    child.recycle()
                }
            } catch (e: Exception) { /* stale node */ }
            return null
        }
        return search(root, 0)
    }

    private suspend fun trySequentialKeypadClick(
        service: FlowPilotAccessibilityService,
        actionExecutor: ActionExecutor,
        step: WorkflowStep,
        resolvedText: String
    ): StepResult? {
        val clean = resolvedText.normalizeNumberWords().trim()
        if (clean.isEmpty() || !clean.matches(Regex("^[0-9.+\\-/*×÷=]+$"))) {
            return null
        }

        Log.i(TAG, "Attempting sequential keypad click for '$clean' on step ${step.index}")
        var allClicked = true

        for ((idx, char) in clean.withIndex()) {
            val charStr = char.toString()
            var charClicked = false
            for (attempt in 1..Constants.MAX_RETRY_ATTEMPTS) {
                val root = service.rootInActiveWindow ?: run {
                    delay(300)
                    continue
                }

                // Find node with EXACT text match or digit resource ID for this digit/symbol
                val exactNode = findExactTextNode(root, charStr)
                if (exactNode != null) {
                    val clicked = actionExecutor.click(exactNode)
                    if (clicked) {
                        charClicked = true
                        delay(200) // Shorter delay to avoid accidental swipe detection
                        break
                    }
                }

                // Fallback: try NodeMatcher with strict spec
                val digitSpec = TargetSpec(
                    text = charStr,
                    className = "android.widget.Button",
                    isEditable = false
                )
                val match = nodeMatcher.findBestMatch(root, digitSpec, emptyMap())
                if (match != null) {
                    val clicked = actionExecutor.click(match.node)
                    if (clicked) {
                        charClicked = true
                        delay(200)
                        break
                    }
                }
                delay(300)
            }

            if (!charClicked) {
                Log.w(TAG, "Sequential keypad click failed at char '$charStr' (index $idx) of '$clean'")
                allClicked = false
                break
            }
        }

        return if (allClicked) {
            StepResult(
                step.index,
                true,
                "CLICK",
                "Sequential keypad input for '$clean' succeeded",
                0
            )
        } else {
            null
        }
    }

    // ─── Shared Helpers ───

    /** Extract the meaningful token from a package name for fuzzy matching. */
    private fun packageToken(pkg: String): String {
        return if (pkg.contains('.')) {
            pkg.split('.').filter { it !in listOf("com", "android", "apps", "app", "google") }.lastOrNull() ?: pkg
        } else {
            pkg
        }
    }

    /** Check if a package name is currently in the foreground. */
    private fun isPackageInForeground(currentPkg: String, targetPkg: String): Boolean {
        if (currentPkg.isBlank()) return false
        val token = packageToken(targetPkg)
        return currentPkg.contains(token, ignoreCase = true) || currentPkg.equals(targetPkg, ignoreCase = true)
    }

    /** Launch an app and wait for it to appear in the foreground. */
    private suspend fun launchAndWaitForApp(
        service: FlowPilotAccessibilityService,
        actionExecutor: ActionExecutor,
        targetPkg: String
    ): Boolean {
        val launched = actionExecutor.openApp(targetPkg)
        if (!launched) {
            Log.w(TAG, "Failed to launch target app: $targetPkg")
            return false
        }
        val token = packageToken(targetPkg)
        for (i in 1..10) {
            delay(500)
            val activePkg = service.rootInActiveWindow?.packageName?.toString() ?: ""
            if (activePkg.contains(token, ignoreCase = true) || activePkg.equals(targetPkg, ignoreCase = true)) {
                Log.i(TAG, "Target app '$targetPkg' confirmed in foreground (active: '$activePkg')")
                delay(1000) // Let the app finish initializing
                return true
            }
        }
        Log.w(TAG, "Target app '$targetPkg' did not appear in foreground within timeout")
        return true // App was launched, just couldn't confirm foreground
    }
}
