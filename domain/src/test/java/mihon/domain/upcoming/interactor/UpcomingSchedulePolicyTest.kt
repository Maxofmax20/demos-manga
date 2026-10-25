package mihon.domain.upcoming.interactor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class UpcomingSchedulePolicyTest {
    @Test
    fun overdueScheduleRemainsEligible() {
        UpcomingSchedulePolicy.isEligibleWithinWindow(10L, 15L) shouldBe true
        UpcomingSchedulePolicy.isEligibleWithinWindow(0L, 15L) shouldBe true
    }

    @Test
    fun futureOutsideWindowIsNotYetDue() {
        UpcomingSchedulePolicy.isEligibleWithinWindow(16L, 15L) shouldBe false
        UpcomingSchedulePolicy.isEligibleWithinWindow(15L, 15L) shouldBe true
    }

    @Test
    fun unknownOrExpiredDateIsNotUpcoming() {
        UpcomingSchedulePolicy.isUpcomingCheck(0L, 1L) shouldBe false
        UpcomingSchedulePolicy.isUpcomingCheck(10L, 12L) shouldBe false
        UpcomingSchedulePolicy.isUpcomingCheck(15L, 12L) shouldBe true
    }
}
