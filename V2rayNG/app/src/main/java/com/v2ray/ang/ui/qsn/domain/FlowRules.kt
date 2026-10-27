package com.v2ray.ang.ui.qsn.domain

/**
 * Small, pure rules the UI obeys.
 *
 * They live here instead of inside the Compose code so the behaviour that is
 * easy to get wrong — when the subscription form closes, when a default may be
 * written, what the language control does next — is unit tested against the
 * same code the app runs.
 */
object SubscriptionPanelRules {

    /**
     * Whether the subscription panel is expanded after a save attempt.
     *
     * Only a persisted save closes it. A rejected URL must leave the form open
     * with its error visible, otherwise the user sees nothing happen.
     */
    fun expandedAfterSave(success: Boolean): Boolean = !success
}

object NotificationDefaults {

    /**
     * True only when the preference has never been written.
     *
     * This is what makes "Receive/Send on by default" apply to a fresh install
     * without overwriting a choice an existing user already made.
     */
    fun enableTrafficWhenUnset(keyExists: Boolean): Boolean = !keyExists
}

object LanguageToggle {

    /** The language the control switches to. */
    fun next(current: String): String = if (current == "fa") "en" else "fa"

    /** Normalises a stored preference into one of the two offered languages. */
    fun normalize(stored: String?, systemLanguage: String): String {
        if (stored == "fa" || stored == "en") return stored
        return if (systemLanguage == "fa") "fa" else "en"
    }
}

/** Counts shown by the IP Ranges header. */
object RangesSummary {

    fun total(ranges: List<ScanRange>): Int = ranges.size

    fun enabled(ranges: List<ScanRange>): Int = ranges.count { it.enabled }
}
