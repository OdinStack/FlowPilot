package com.flowpilot.engine

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.flowpilot.accessibility.ActionExecutor
import com.flowpilot.accessibility.FlowPilotAccessibilityService
import com.flowpilot.accessibility.NodeMatcher
import com.flowpilot.accessibility.SafetyDetector
import com.flowpilot.accessibility.ScreenAnalyzer
import com.flowpilot.ai.GeminiClient
import com.flowpilot.data.models.StepType
import com.flowpilot.data.models.TargetSpec
import com.flowpilot.data.models.Workflow
import com.flowpilot.data.models.WorkflowStep
import com.flowpilot.util.Constants
import com.flowpilot.util.describe
import com.flowpilot.util.normalizeNumberWords
import com.flowpilot.util.operationToCalculatorTarget
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

class ReplayEngine(
    private val serviceProvider: () -> FlowPilotAccessibilityService? = { FlowPilotAccessibilityService.instance },
    private val nodeMatcher: NodeMatcher = NodeMatcher(),
    private val safetyDetector: SafetyDetector = SafetyDetector(),
    private val screenAnalyzer: ScreenAnalyzer = ScreenAnalyzer(),
    private val stateMachine: SystemStateMachine,
    private val recoveryManager: RecoveryManager? = null,
    private val geminiClient: GeminiClient? = null
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
            // Always re-launch the app to ensure it starts from its home/root screen.
            // FLAG_ACTIVITY_CLEAR_TASK in ActionExecutor.openApp() resets the activity stack.
            Log.w(TAG, "Launching target app '$targetPkg' fresh (clearing activity stack)...")
            stateMachine.transition(SystemMode.REPLAYING, "Opening ${targetPkg.substringAfterLast('.')}...")
            launchAndWaitForApp(service, actionExecutor, targetPkg)
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

                    // Before stopping, handle address change if needed
                    val addressSlot = effectiveSlots["address"] ?: effectiveSlots["delivery_address"]
                    if (!addressSlot.isNullOrBlank()) {
                        val defaultAddr = workflow.slots["address"]?.defaultValue
                            ?: workflow.slots["delivery_address"]?.defaultValue
                        if (defaultAddr == null || !addressSlot.equals(defaultAddr, ignoreCase = true)) {
                            Log.i(TAG, "Attempting address change to '$addressSlot' before credential boundary...")
                            val addrResult = handleAddressChange(service, actionExecutor, addressSlot)
                            stepResults.add(addrResult)
                            if (addrResult.success) {
                                Log.i(TAG, "Address changed successfully to '$addressSlot'")
                            } else {
                                Log.w(TAG, "Address change failed: ${addrResult.details}")
                            }
                        }
                    }

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
                val remappedStep = remapOperatorStep(step, effectiveSlots)
                val result = executeStep(service, actionExecutor, workflow, remappedStep, effectiveSlots)
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

            // ─── POST-STEP: Quantity Increment ───
            if (finalStepResult.success && isAddToCartStep(step)) {
                val qtyResult = handleQuantityIncrement(service, actionExecutor, effectiveSlots)
                if (qtyResult.success && qtyResult.details != "No quantity > 1" && qtyResult.details != "Default quantity (1)") {
                    stepResults.add(qtyResult)
                    Log.i(TAG, "Quantity handled: ${qtyResult.details}")
                } else if (!qtyResult.success) {
                    Log.w(TAG, "Quantity increment failed: ${qtyResult.details}")
                }
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

        // ─── LAST RESORT: Cross-app LLM element mapping ───
        if (geminiClient != null) {
            Log.i(TAG, "Attempting cross-app LLM element mapping for step: ${step.description}")
            val llmNode = crossAppElementMapping(service, step, slots)
            if (llmNode != null) {
                val success = action(llmNode)
                if (success) {
                    return StepResult(step.index, true, actionName, "Found via LLM cross-app mapping", 0)
                }
            }
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

    // ─── Operator Remapping ───

    /**
     * If this step targets a calculator operator button (op_add, op_sub, op_mul, op_div)
     * and an "operation" slot is present, remap the step's target to match the requested operation.
     * This allows a workflow taught with "add" to dynamically work for multiply/subtract/divide.
     */
    private fun remapOperatorStep(step: WorkflowStep, slots: Map<String, String>): WorkflowStep {
        val operation = slots["operation"] ?: return step
        if (step.type != StepType.CLICK) return step

        val resId = step.target.resourceId ?: ""
        val desc = step.target.contentDescription ?: ""

        // Check if this step targets any calculator operator button
        val operatorPatterns = listOf("op_add", "op_sub", "op_mul", "op_div")
        val operatorDescs = listOf("add", "subtract", "multiply", "divide", "plus", "minus", "times")
        val isOperatorStep = operatorPatterns.any { resId.contains(it, ignoreCase = true) } ||
                             operatorDescs.any { desc.equals(it, ignoreCase = true) }

        if (!isOperatorStep) return step

        val mapping = operationToCalculatorTarget(operation) ?: return step
        val (newResIdSuffix, newDesc) = mapping

        // Build new resource ID maintaining the package prefix (e.g. com.coloros.calculator:id/op_mul)
        val newResId = if (resId.contains(":id/")) {
            resId.substringBefore(":id/") + ":id/" + newResIdSuffix
        } else {
            newResIdSuffix
        }

        Log.i(TAG, "Remapping operator step: $resId -> $newResId, '$desc' -> '$newDesc' (operation=$operation)")

        return step.copy(
            target = step.target.copy(
                resourceId = newResId,
                contentDescription = newDesc
            ),
            description = "Click the $operation operator"
        )
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

    // ─── Phase 10: Quantity Handling ───

    /**
     * Detect if a step is an "add to cart" / "add to bag" action.
     * Uses semantic, description, text, and contentDescription heuristics.
     */
    private fun isAddToCartStep(step: WorkflowStep): Boolean {
        val desc = step.description.lowercase()
        val semantic = step.target.semantic?.lowercase() ?: ""
        val text = step.target.text?.lowercase() ?: ""
        val textContains = step.target.textContains?.lowercase() ?: ""
        val contentDesc = step.target.contentDescription?.lowercase() ?: ""
        val resId = step.target.resourceId?.lowercase() ?: ""

        val addPhrases = listOf("add to cart", "add to bag", "add to basket", "add item",
            "add to order", "buy now")
        val addKeywords = listOf("add", "buy now")

        // Check full phrases first (more specific)
        if (addPhrases.any { p -> desc.contains(p) || semantic.contains(p) || text.contains(p) || contentDesc.contains(p) }) {
            return true
        }
        // Check keywords in text/desc (exact match to avoid false positives like "Add address")
        if (addKeywords.any { k -> text == k || contentDesc == k || textContains == k }) {
            return true
        }
        // Check resourceId patterns
        if (resId.contains("add_to_cart") || resId.contains("add_to_bag") ||
            resId.contains("btn_add") || resId.contains("add_item")) {
            return true
        }
        return false
    }

    /**
     * After an "add to cart" action, if quantity > 1, find the increment (+) button
     * and click it (quantity - 1) times. Works generically across apps by searching for
     * common increment button patterns.
     */
    private suspend fun handleQuantityIncrement(
        service: FlowPilotAccessibilityService,
        actionExecutor: ActionExecutor,
        slots: Map<String, String>
    ): StepResult {
        val quantityStr = slots["quantity"] ?: slots["count"] ?: slots["qty"]
        val quantity = quantityStr?.toIntOrNull()
            ?: return StepResult(-1, true, "QUANTITY", "No quantity > 1", 0)

        if (quantity <= 1) return StepResult(-1, true, "QUANTITY", "Default quantity (1)", 0)

        Log.i(TAG, "Quantity=$quantity requested. Looking for increment button...")
        delay(800) // Wait for cart UI to update

        val root = service.rootInActiveWindow
            ?: return StepResult(-1, false, "QUANTITY", "No window available", 0)

        // Strategy 1: Try TargetSpec-based matching via NodeMatcher
        val incrementSpecs = listOf(
            TargetSpec(text = "+", semantic = "quantity increment"),
            TargetSpec(text = "＋", semantic = "quantity increment"),
            TargetSpec(contentDescription = "Increase", semantic = "quantity increment"),
            TargetSpec(contentDescription = "Increase quantity", semantic = "quantity increment"),
            TargetSpec(contentDescription = "increment", semantic = "quantity increment")
        )

        var plusNode: AccessibilityNodeInfo? = null
        for (spec in incrementSpecs) {
            val match = nodeMatcher.findBestMatch(root, spec, slots)
            if (match != null) {
                plusNode = match.node
                Log.i(TAG, "Found increment button via NodeMatcher: ${match.matchDetails}")
                break
            }
        }

        // Strategy 2: Deep tree traversal for increment controls
        if (plusNode == null) {
            plusNode = findIncrementButton(root)
            if (plusNode != null) {
                Log.i(TAG, "Found increment button via tree traversal")
            }
        }

        if (plusNode == null) {
            Log.w(TAG, "Could not find quantity increment button")
            return StepResult(-1, false, "QUANTITY", "Could not find quantity increment button", 0)
        }

        Log.i(TAG, "Clicking increment button ${quantity - 1} times...")
        repeat(quantity - 1) { i ->
            actionExecutor.click(plusNode)
            delay(400)
            Log.d(TAG, "Quantity increment click ${i + 1}/${quantity - 1}")
        }

        return StepResult(-1, true, "QUANTITY", "Set quantity to $quantity", 0)
    }

    /**
     * Traverses the UI tree to find an increment/plus button using common patterns
     * across Zomato, Amazon, Flipkart, Myntra, and other e-commerce apps.
     */
    private fun findIncrementButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (depth > 25) return null
            try {
                if (node.isVisibleToUser && (node.isClickable || node.isEnabled)) {
                    val text = node.text?.toString() ?: ""
                    val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                    val resId = node.viewIdResourceName?.lowercase() ?: ""

                    // Text-based: exact "+" or "＋"
                    if (text == "+" || text == "＋") return node

                    // ContentDescription-based
                    if (desc == "increase" || desc == "increment" || desc == "increase quantity" ||
                        desc == "add quantity" || desc == "plus" || desc == "qty plus" ||
                        desc.contains("increase") || desc.contains("increment")) {
                        if (node.isClickable) return node
                    }

                    // ResourceId-based patterns (common across apps)
                    if (resId.contains("plus") || resId.contains("increment") ||
                        resId.contains("increase") || resId.contains("qty_add") ||
                        resId.contains("qty_increase") || resId.contains("btn_plus") ||
                        resId.contains("btn_increase") || resId.contains("qty_plus") ||
                        resId.contains("add_qty") || resId.contains("quantity_add") ||
                        resId.contains("stepper_plus") || resId.contains("counter_add")) {
                        if (node.isClickable) return node
                    }
                }

                for (i in 0 until node.childCount) {
                    val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                    val result = search(child, depth + 1)
                    if (result != null) return result
                }
            } catch (e: Exception) {
                // Stale node — safe to ignore
            }
            return null
        }
        return search(root, 0)
    }

    // ─── Phase 10: Address Handling ───

    /**
     * Handle delivery address change on checkout/cart screens.
     * Uses generic heuristic patterns that work across Zomato, Amazon, Flipkart, Myntra:
     * - Finds the address section by text patterns (deliver to, shipping address, etc.)
     * - Clicks to open address picker
     * - Finds and selects the target address option
     */
    private suspend fun handleAddressChange(
        service: FlowPilotAccessibilityService,
        actionExecutor: ActionExecutor,
        targetAddress: String
    ): StepResult {
        val root = service.rootInActiveWindow
            ?: return StepResult(-1, false, "ADDRESS", "No window available", 0)

        Log.i(TAG, "Attempting to change address to '$targetAddress'")

        // Strategy 1: Find address section by common text patterns
        val addressPatterns = listOf(
            "deliver to", "delivery address", "shipping to", "shipping address",
            "delivering to", "selected address", "deliver here", "change address",
            "select address", "delivery location"
        )

        var addressNode: AccessibilityNodeInfo? = null
        for (pattern in addressPatterns) {
            val spec = TargetSpec(textContains = pattern, semantic = "address selector")
            val match = nodeMatcher.findBestMatch(root, spec, emptyMap())
            if (match != null) {
                addressNode = match.node
                Log.i(TAG, "Found address section via pattern '$pattern': ${match.matchDetails}")
                break
            }
        }

        // Strategy 2: Look for current address labels (Home, Work, Office)
        if (addressNode == null) {
            val addressLabels = listOf("home", "work", "office", "other")
            for (label in addressLabels) {
                val spec = TargetSpec(text = label, semantic = "current address")
                val match = nodeMatcher.findBestMatch(root, spec, emptyMap())
                if (match != null && match.node.isClickable) {
                    addressNode = match.node
                    Log.i(TAG, "Found address label '$label': ${match.matchDetails}")
                    break
                }
            }
        }

        // Strategy 3: Find by resourceId patterns
        if (addressNode == null) {
            addressNode = findAddressSection(root)
        }

        if (addressNode == null) {
            return StepResult(-1, false, "ADDRESS", "Could not find address section on screen", 0)
        }

        // Click to open address picker
        actionExecutor.click(addressNode)
        delay(1500) // Wait for address list to load

        // Find the target address option in the picker
        val newRoot = service.rootInActiveWindow
            ?: return StepResult(-1, false, "ADDRESS", "Lost window after opening address picker", 0)

        // Search for the target address text
        val targetSpec = TargetSpec(textContains = targetAddress, semantic = "target address option")
        var targetMatch = nodeMatcher.findBestMatch(newRoot, targetSpec, emptyMap())

        // Fallback: try case-insensitive broader search
        if (targetMatch == null) {
            targetMatch = findTextInTree(newRoot, targetAddress)?.let {
                NodeMatcher.MatchResult(it, 0.7f, "Found '$targetAddress' in address list")
            }
        }

        if (targetMatch == null) {
            // Press back to close the picker
            actionExecutor.pressBack()
            delay(500)
            return StepResult(-1, false, "ADDRESS", "Could not find address '$targetAddress' in picker", 0)
        }

        // Click the target address
        actionExecutor.click(targetMatch.node)
        delay(1000) // Wait for screen to return to checkout

        Log.i(TAG, "Address changed to '$targetAddress'")
        return StepResult(-1, true, "ADDRESS", "Changed address to $targetAddress", 0)
    }

    /**
     * Find address section by resourceId patterns common in e-commerce apps.
     */
    private fun findAddressSection(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (depth > 20) return null
            try {
                if (node.isVisibleToUser) {
                    val resId = node.viewIdResourceName?.lowercase() ?: ""
                    val desc = node.contentDescription?.toString()?.lowercase() ?: ""

                    if (resId.contains("address") || resId.contains("delivery") ||
                        resId.contains("location") || resId.contains("shipping") ||
                        desc.contains("address") || desc.contains("delivery location") ||
                        desc.contains("change address")) {
                        if (node.isClickable) return node
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                    val result = search(child, depth + 1)
                    if (result != null) return result
                }
            } catch (e: Exception) {}
            return null
        }
        return search(root, 0)
    }

    /**
     * Find a node containing specific text (case-insensitive) and return the nearest clickable parent.
     */
    private fun findTextInTree(root: AccessibilityNodeInfo, targetText: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (depth > 25) return null
            try {
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""

                if (text.contains(targetText, ignoreCase = true) ||
                    desc.contains(targetText, ignoreCase = true)) {
                    // Return this node if clickable, or walk up to find clickable parent
                    if (node.isClickable) return node
                    var parent = node.parent
                    var parentDepth = 0
                    while (parent != null && parentDepth < 5) {
                        if (parent.isClickable) return parent
                        parent = parent.parent
                        parentDepth++
                    }
                    return node // Return even if not directly clickable
                }

                for (i in 0 until node.childCount) {
                    val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                    val result = search(child, depth + 1)
                    if (result != null) return result
                }
            } catch (e: Exception) {}
            return null
        }
        return search(root, 0)
    }

    // ─── Phase 11: Cross-App Generalization ───

    /**
     * Use LLM (Gemini) to map a workflow step's semantic description to a UI element
     * on the current screen. This enables workflows taught on one app to work on
     * a different app with similar UI patterns (e.g., search bar, add-to-cart button).
     */
    private suspend fun crossAppElementMapping(
        service: FlowPilotAccessibilityService,
        step: WorkflowStep,
        slots: Map<String, String>
    ): AccessibilityNodeInfo? {
        val gemini = geminiClient ?: return null
        val root = service.rootInActiveWindow ?: return null

        val elements = collectVisibleElements(root)
        if (elements.isEmpty()) return null

        val elementsDesc = elements.mapIndexed { i, elem ->
            "[$i] ${elem.describe()}"
        }.joinToString("\n")

        val stepDesc = buildString {
            append("Action: ${step.type.name}\n")
            step.target.semantic?.let { append("Semantic role: $it\n") }
            step.target.text?.let { append("Original text: $it\n") }
            step.target.textContains?.let { append("Text contains: $it\n") }
            step.target.contentDescription?.let { append("Content description: $it\n") }
            step.description.takeIf { it.isNotBlank() }?.let { append("Description: $it\n") }
        }

        val response = try {
            gemini.generate(
                systemPrompt = """
You are a UI element mapper. Given a workflow step designed for one app and 
the current UI elements on screen, find the equivalent UI element.

The workflow may have been learned on a different app. Map the abstract semantic 
action to the correct UI element on the current screen.

Return JSON:
{
  "found": true/false,
  "element_index": -1,
  "confidence": 0.0-1.0,
  "reasoning": "why this element matches"
}

Only return found=true if confidence >= 0.7.
                """.trimIndent(),
                userPrompt = """
WORKFLOW STEP:
$stepDesc

CURRENT SCREEN visible elements:
$elementsDesc

Slot values: $slots

Which element corresponds to this workflow step? Return JSON.
                """.trimIndent(),
                jsonMode = true
            )
        } catch (e: Exception) {
            Log.w(TAG, "Cross-app mapping LLM call failed", e)
            return null
        }

        if (response == null) return null

        return try {
            val cleanJson = response.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            val obj = json.parseToJsonElement(cleanJson).jsonObject

            val found = obj["found"]?.jsonPrimitive?.booleanOrNull ?: false
            if (!found) return null

            val elementIndex = obj["element_index"]?.jsonPrimitive?.intOrNull ?: return null
            val confidence = obj["confidence"]?.jsonPrimitive?.floatOrNull ?: 0f

            if (confidence < 0.7f || elementIndex < 0 || elementIndex >= elements.size) return null

            val reasoning = obj["reasoning"]?.jsonPrimitive?.contentOrNull ?: ""
            Log.i(TAG, "Cross-app mapping: found element[$elementIndex] confidence=$confidence reason='$reasoning'")
            elements[elementIndex]
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cross-app mapping response", e)
            null
        }
    }

    /**
     * Collect all visible interactive elements from the UI tree.
     * Returns at most 50 elements to keep LLM prompts manageable.
     */
    private fun collectVisibleElements(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val elements = mutableListOf<AccessibilityNodeInfo>()
        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 20 || elements.size >= 50) return
            try {
                if (node.isVisibleToUser &&
                    (node.isClickable || node.isEditable || node.isScrollable ||
                     !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank())) {
                    elements.add(node)
                }
                for (i in 0 until node.childCount) {
                    val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                    traverse(child, depth + 1)
                }
            } catch (e: Exception) {
                // Stale node — safe to ignore
            }
        }
        traverse(root, 0)
        return elements
    }
}
