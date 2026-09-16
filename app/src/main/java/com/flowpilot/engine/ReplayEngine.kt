package com.flowpilot.engine

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
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
import kotlinx.coroutines.delay

class ReplayEngine(
    private val serviceProvider: () -> FlowPilotAccessibilityService? = { FlowPilotAccessibilityService.instance },
    private val nodeMatcher: NodeMatcher = NodeMatcher(),
    private val safetyDetector: SafetyDetector = SafetyDetector(),
    private val screenAnalyzer: ScreenAnalyzer = ScreenAnalyzer(),
    private val gemini: GeminiClient? = null,
    private val stateMachine: SystemStateMachine
) {

    companion object {
        private const val TAG = "ReplayEngine"
    }

    constructor(
        service: FlowPilotAccessibilityService,
        nodeMatcher: NodeMatcher,
        actionExecutor: ActionExecutor,
        safetyDetector: SafetyDetector,
        screenAnalyzer: ScreenAnalyzer,
        gemini: GeminiClient,
        stateMachine: SystemStateMachine
    ) : this({ service }, nodeMatcher, safetyDetector, screenAnalyzer, gemini, stateMachine)

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
            val token = if (targetPkg.contains('.')) {
                targetPkg.split('.').filter { it !in listOf("com", "android", "apps", "app", "google") }.lastOrNull() ?: targetPkg
            } else {
                targetPkg
            }
            val isForeground = currentPkg.contains(token, ignoreCase = true) || currentPkg.equals(targetPkg, ignoreCase = true)

            if (!isForeground) {
                Log.w(TAG, "Target app '$targetPkg' is not in foreground (current: '$currentPkg'). Launching target app...")
                stateMachine.transition(SystemMode.REPLAYING, "Opening ${targetPkg.substringAfterLast('.')}...")
                val launched = actionExecutor.openApp(targetPkg)
                if (launched) {
                    for (i in 1..10) {
                        delay(500)
                        val activePkg = service.rootInActiveWindow?.packageName?.toString() ?: ""
                        if (activePkg.contains(token, ignoreCase = true) || activePkg.equals(targetPkg, ignoreCase = true)) {
                            Log.w(TAG, "Target app '$targetPkg' confirmed in foreground (active: '$activePkg')")
                            break
                        }
                    }
                    delay(1000)
                } else {
                    Log.w(TAG, "Failed to launch target app: $targetPkg")
                }
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
                if (safety !is SafetyDetector.SafetyResult.Safe) {
                    val reason = when (safety) {
                        is SafetyDetector.SafetyResult.Credential -> safety.reason
                        is SafetyDetector.SafetyResult.Payment -> safety.reason
                        else -> "Security boundary reached"
                    }

                    Log.w(TAG, "Safety boundary detected at step ${step.index}: $reason")
                    if (step.isCredentialBoundary) {
                        stateMachine.transition(SystemMode.PAUSED, "Payment/login screen reached. Your turn!")
                    } else {
                        stateMachine.transition(SystemMode.PAUSED, "Security screen reached ($reason). Please complete manually.")
                    }

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

            // ─── EXECUTE STEP ───
            val stepResult = executeStep(service, actionExecutor, workflow, step, effectiveSlots)
            val duration = System.currentTimeMillis() - startTime
            val finalStepResult = stepResult.copy(durationMs = duration)
            stepResults.add(finalStepResult)

            if (!finalStepResult.success) {
                Log.e(TAG, "Step ${step.index} failed: ${finalStepResult.details}")
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
            delay(Constants.UI_SETTLE_DELAY_MS.coerceAtLeast(600L))
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
        val success = actionExecutor.openApp(targetPkg)
        val token = if (targetPkg.contains('.')) {
            targetPkg.split('.').filter { it !in listOf("com", "android", "apps", "app", "google") }.lastOrNull() ?: targetPkg
        } else {
            targetPkg
        }
        for (i in 1..10) {
            delay(500)
            val activePkg = service.rootInActiveWindow?.packageName?.toString() ?: ""
            if (activePkg.contains(token, ignoreCase = true) || activePkg.equals(targetPkg, ignoreCase = true)) {
                Log.w(TAG, "Target app '$targetPkg' confirmed in foreground (active: '$activePkg')")
                break
            }
        }
        delay(1000)
        return StepResult(step.index, success, "OPEN_APP", "Opened $targetPkg", 0)
    }

    private suspend fun executeClick(
        service: FlowPilotAccessibilityService,
        actionExecutor: ActionExecutor,
        step: WorkflowStep,
        slots: Map<String, String>
    ): StepResult {
        // 1. Try single element direct click
        val directResult = findAndAct(service, step, slots, "CLICK") { node ->
            actionExecutor.click(node)
        }
        if (directResult.success) {
            return directResult
        }

        // 2. Fallback: if element not found, check if it's a multi-character keypad entry (e.g. "10", "42")
        val resolved = resolveTargetText(step, slots)
        if (resolved != null) {
            val keypadResult = trySequentialKeypadClick(service, actionExecutor, step, resolved)
            if (keypadResult != null) {
                return keypadResult
            }
        }

        return directResult
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

        val result = findAndAct(service, step, slots, "TYPE") { node ->
            actionExecutor.setText(node, textToType)
        }
        if (result.success) {
            return result
        }

        // Fallback for non-editable fields (e.g. calculator display): try sequential keypad clicks
        val keypadResult = trySequentialKeypadClick(service, actionExecutor, step, textToType)
        if (keypadResult != null) {
            return keypadResult
        }

        return result
    }

    private suspend fun executeScroll(
        service: FlowPilotAccessibilityService,
        actionExecutor: ActionExecutor,
        step: WorkflowStep
    ): StepResult {
        val root = service.rootInActiveWindow ?: return StepResult(step.index, false, "SCROLL", "Active window not available", 0)

        val scrollable = findScrollableNode(root)
            ?: return StepResult(step.index, false, "SCROLL", "No scrollable container found", 0)

        val forward = !step.value.equals("UP", ignoreCase = true)
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
            Log.d(TAG, "Target not visible; scrolling to find element...")
            val scrollable = findScrollableNode(root)
            if (scrollable != null) {
                for (attempt in 1..Constants.MAX_SCROLL_ATTEMPTS) {
                    actionExecutor.scroll(scrollable, forward = true)
                    delay(800)

                    val newRoot = service.rootInActiveWindow ?: continue
                    match = nodeMatcher.findBestMatch(newRoot, step.target, slots)
                    if (match != null) {
                        Log.d(TAG, "Found element after $attempt scrolls")
                        break
                    }
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
            return StepResult(step.index, false, "FIND_AND_CLICK", "Could not locate: $targetDesc", 0)
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

    private suspend fun trySequentialKeypadClick(
        service: FlowPilotAccessibilityService,
        actionExecutor: ActionExecutor,
        step: WorkflowStep,
        resolvedText: String
    ): StepResult? {
        val clean = resolvedText.trim()
        if (clean.length <= 1 || !clean.matches(Regex("^[0-9.+-/*×÷=]+$"))) {
            return null
        }

        Log.i(TAG, "Attempting sequential keypad click for '$clean' on step ${step.index}")
        var allClicked = true

        for ((idx, char) in clean.withIndex()) {
            val charStr = char.toString()
            val digitSpec = TargetSpec(text = charStr)

            var charClicked = false
            for (attempt in 1..Constants.MAX_RETRY_ATTEMPTS) {
                val root = service.rootInActiveWindow ?: run {
                    delay(300)
                    continue
                }

                val match = nodeMatcher.findBestMatch(root, digitSpec, emptyMap())
                if (match != null) {
                    val clicked = actionExecutor.click(match.node)
                    if (clicked) {
                        charClicked = true
                        delay(350) // Delay between keypad taps
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
}
