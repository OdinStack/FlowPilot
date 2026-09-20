package com.flowpilot.util

object Constants {
    // Loaded securely from local.properties via BuildConfig
    val GEMINI_API_KEY: String
        get() = com.flowpilot.BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() }
            ?: "YOUR_GEMINI_API_KEY_HERE"

    val GROQ_API_KEY: String
        get() = com.flowpilot.BuildConfig.GROQ_API_KEY.takeIf { it.isNotBlank() }
            ?: ""

    val OPENROUTER_API_KEY: String
        get() = com.flowpilot.BuildConfig.OPENROUTER_API_KEY.takeIf { it.isNotBlank() }
            ?: ""

    fun hasAnyAiKey(): Boolean {
        val gemini = GEMINI_API_KEY.isNotBlank() && GEMINI_API_KEY != "YOUR_GEMINI_API_KEY_HERE"
        val groq = GROQ_API_KEY.isNotBlank()
        val openRouter = OPENROUTER_API_KEY.isNotBlank()
        return gemini || groq || openRouter
    }
    
    // Timing constants
    const val UI_SETTLE_DELAY_MS = 500L
    const val ACTION_DEBOUNCE_MS = 300L
    const val SCREEN_TRANSITION_DELAY_MS = 1000L
    const val MAX_SCROLL_ATTEMPTS = 10
    const val MAX_RETRY_ATTEMPTS = 3
    const val STEP_TIMEOUT_MS = 10_000L
    
    // Matching thresholds
    const val NODE_MATCH_THRESHOLD = 0.5f
    const val HIGH_CONFIDENCE_THRESHOLD = 0.88f
    const val LOW_CONFIDENCE_THRESHOLD = 0.55f
    
    // Safety detection
    val CREDENTIAL_TEXT_PATTERNS = listOf(
        "password", "passwd", "passcode",
        "sign in", "signin", "log in", "login",
        "enter otp", "verification code", "one time password",
        "enter pin", "mpin", "upi pin",
        "enter code", "confirm code"
    )
    
    val PAYMENT_TEXT_PATTERNS = listOf(
        "pay now", "pay ₹", "pay rs", "pay \$",
        "place order", "confirm payment", "proceed to pay",
        "payment method", "select payment",
        "credit card", "debit card", "card number",
        "cvv", "expiry", "card holder",
        "net banking", "upi", "wallet",
        "cash on delivery", "cod"
    )
    
    val PAYMENT_PACKAGES = setOf(
        "com.google.android.apps.nbu.paisa.user",
        "net.one97.paytm",
        "com.phonepe.app",
        "in.org.npci.upiapp",
        "com.payu.india",
        "com.freecharge.android"
    )
    
    val DISMISS_TEXT_PATTERNS = listOf(
        "close", "dismiss", "cancel", "no thanks", "not now",
        "later", "skip", "got it", "ok", "maybe later",
        "deny", "no", "×", "✕", "✗"
    )
    
    val DISMISS_ID_PATTERNS = listOf(
        "close", "dismiss", "cancel", "btn_close", "btn_cancel",
        "dialog_close", "popup_close", "btn_negative"
    )

    fun isSystemOrLauncherPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return true
        val lower = pkg.lowercase()
        val known = setOf(
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.android.launcher",
            "com.android.launcher2",
            "com.android.launcher3",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.oppo.launcher",
            "com.oneplus.launcher",
            "com.nothing.launcher",
            "com.coloros.home",
            "com.heytap.launcher",
            "com.android.systemui",
            "com.samsung.android.honeyboard",
            "com.samsung.android.app.galaxyfinder",
            "com.google.android.inputmethod.latin",
            "com.google.android.googlequicksearchbox"
        )
        return pkg in known ||
                lower.contains("launcher") ||
                lower.contains("systemui") ||
                lower.contains("keyboard") ||
                lower.contains("honeyboard") ||
                lower.contains("quicksearch") ||
                lower.contains("home") ||
                lower.contains("inputmethod") ||
                lower.contains("safecenter")
    }
}
