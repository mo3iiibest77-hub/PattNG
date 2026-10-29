package com.v2ray.ang.ui.qsn.domain

/**
 * What adding one manual CIDR to an ISP profile did.
 *
 * The three outcomes are deliberately separate: the screen has to tell a typo
 * apart from a range that is already stored, and neither is a crash.
 */
sealed interface ManualRangeResult {
    data class Added(val cidr: String) : ManualRangeResult
    data class Duplicate(val cidr: String) : ManualRangeResult
    data class Invalid(val error: RangeError) : ManualRangeResult
}

/**
 * Rules for the manual range list.
 *
 * A manual range is owned by one ISP, is normalised to its network address, and
 * is never a "good" range on its own: adding it only makes it available to
 * Stage 2, where it is measured like any other candidate.
 */
object ManualRangePolicy {

    /**
     * Classifies [input] against the ranges already stored for this ISP.
     *
     * [existing] holds both the manual ranges and the discovered ones, so the
     * same CIDR can never appear twice under different labels.
     */
    fun classify(input: String, existing: Collection<String>): ManualRangeResult {
        return when (val parsed = ScanRangeParser.parse(input)) {
            is RangeParseResult.Invalid -> ManualRangeResult.Invalid(parsed.reason)
            is RangeParseResult.Valid -> {
                val cidr = parsed.range.cidr
                val known = existing.any { it.trim().equals(cidr, ignoreCase = true) }
                if (known) ManualRangeResult.Duplicate(cidr) else ManualRangeResult.Added(cidr)
            }
        }
    }
}

/**
 * Chooses the ranges Stage 2 may measure for one ISP.
 *
 * An explicit selection wins, and is intersected with what the ISP actually
 * owns, so a stale selection cannot smuggle another ISP's range into a scan.
 * With no selection the good ranges are used; manual ranges join only when the
 * ISP has no good range yet, or when the user selected them.
 */
object Stage2Ranges {

    fun resolve(
        good: List<String>,
        manual: List<String>,
        selected: Set<String> = emptySet(),
    ): List<String> {
        val owned = LinkedHashSet<String>()
        good.forEach { owned.add(it.trim()) }
        manual.forEach { owned.add(it.trim()) }
        owned.remove("")

        if (selected.isNotEmpty()) {
            val wanted = selected.map { it.trim() }.toSet()
            return owned.filter { wanted.contains(it) }
        }
        if (good.isNotEmpty()) return good.map { it.trim() }.distinct()
        return manual.map { it.trim() }.distinct()
    }
}
