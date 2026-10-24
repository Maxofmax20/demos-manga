package mihon.domain.upcoming.interactor

/**
 * nextUpdate is an estimated SOURCE CHECK, never a guaranteed chapter
 * publication date. Do not skip overdue titles or invent unscheduled dates.
 */
internal object UpcomingSchedulePolicy {
    fun hasScheduledCheck(nextUpdate: Long): Boolean = nextUpdate > 0L

    fun isEligibleWithinWindow(nextUpdate: Long, upperBound: Long): Boolean =
        nextUpdate <= upperBound // 0 means unknown and should still be checked

    fun isUpcomingCheck(nextUpdate: Long, startOfToday: Long): Boolean =
        nextUpdate >= startOfToday && hasScheduledCheck(nextUpdate)
}
