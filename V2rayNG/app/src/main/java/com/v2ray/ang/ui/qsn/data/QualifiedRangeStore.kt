package com.v2ray.ang.ui.qsn.data

import com.v2ray.ang.ui.qsn.domain.QualifiedRange
import com.v2ray.ang.ui.qsn.domain.RangeSet

/**
 * The one place qualified ranges are written and read.
 *
 * Discovery used to write its results only through IspManager, while the
 * connection flow read QsPrefs.loadQualifiedRanges(). Two stores meant a run
 * could "succeed" and still leave the next connection with nothing to scan, so
 * everything now goes through this object: the scanner appends a range the
 * moment it qualifies, and every reader uses [snapshot].
 *
 * Writes are serialised: three discovery coroutines can qualify a range at the
 * same time, and a read-modify-write race would drop one of them.
 */
object QualifiedRangeStore {

    /** Current set, sanitised and de-duplicated. */
    fun snapshot(): List<QualifiedRange> = synchronized(this) { QsPrefs.loadQualifiedRanges() }

    /**
     * Merges [found] into the stored set and returns the new set.
     *
     * Merging keeps what was already qualified: a later run that finds fewer
     * ranges, is cancelled, or finds none at all can never shrink the database.
     */
    fun add(found: List<QualifiedRange>): List<QualifiedRange> {
        if (found.isEmpty()) return snapshot()
        return synchronized(this) {
            val merged = RangeSet.merge(QsPrefs.loadQualifiedRanges(), found)
            QsPrefs.saveQualifiedRanges(merged)
            merged
        }
    }
}
