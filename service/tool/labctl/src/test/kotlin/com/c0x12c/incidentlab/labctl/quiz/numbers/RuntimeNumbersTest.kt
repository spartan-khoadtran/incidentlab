package com.c0x12c.incidentlab.labctl.quiz.numbers

import assertk.assertThat
import assertk.assertions.isCloseTo
import com.c0x12c.incidentlab.labctl.quiz.NumericQuestion
import kotlin.random.Random
import org.junit.jupiter.api.Test

/** Reads the drawn values back from each question and checks the answer with the formula written out by hand. */
class RuntimeNumbersTest {

  @Test
  fun `the old generation is two thirds of the heap share of the limit`() {
    // 512 MB × 50% = 256 MB heap, × 2/3 = 170.7 MB
    check(RuntimeNumbers::oldGenSize) { (limit, percent) -> limit * percent / 100 * 2 / 3 }
  }

  @Test
  fun `the pause share is pause time over the time between pauses`() {
    // 150 ms every 15 s = 0.15 / 15 = 1%
    check(RuntimeNumbers::pauseShare) { (pause, every) -> pause / (every * 1000) * 100 }
  }

  @Test
  fun `time in GC adds the young and the full pause time per second`() {
    // 10 ms every 5 s + 200 ms every 30 s = 2 + 6.67 = 8.67 ms per second = 0.867%
    check(RuntimeNumbers::gcTimeShare) { (youngPause, youngEvery, fullPause, fullEvery) -> (youngPause / youngEvery + fullPause / fullEvery) / 10 }
  }

  @Test
  fun `the next full GC comes when promotion fills the free room`() {
    // 170 MB with 85% live = 25.5 MB free, at 1.5 MB/s = 17 s
    check(RuntimeNumbers::fullGcInterval) { (old, live, promotion) -> old * (100 - live) / 100 / promotion }
  }

  @Test
  fun `an entry lives the cache size over the write rate`() {
    // 80 MB at 64 KB × 50/s = 3.125 MB/s, so 25.6 s
    check(RuntimeNumbers::objectLifetime) { (store, churn, rps) -> store / (churn * rps / 1024) }
  }

  @Test
  fun `carriers cap the pinned request rate`() {
    // 2 carriers, 200 ms pinned = 10 requests/s
    check(RuntimeNumbers::carrierCapacity) { (carriers, pinned) -> carriers * 1000 / pinned }
  }

  @Test
  fun `demand is carriers needed over carriers available`() {
    // 15/s × 0.16 s = 2.4 carriers, over 2 = 1.2
    check(RuntimeNumbers::carrierDemand) { (rps, pinned, carriers) -> rps * pinned / 1000 / carriers }
  }

  @Test
  fun `the system share is system over user plus system`() {
    // 0.2 user + 0.2 system = 50%
    check(RuntimeNumbers::systemShare) { (user, system) -> system / (user + system) * 100 }
  }

  @Test
  fun `every flushed line is one syscall`() {
    // 500 lines × 50/s = 25000
    check(RuntimeNumbers::syscallRate) { (lines, rps) -> lines * rps }
  }

  @Test
  fun `log volume per minute`() {
    // 300 lines × 50/s × 100 bytes × 60 / 1048576 = 85.8 MB
    check(RuntimeNumbers::logVolume) { (lines, rps, bytes) -> lines * rps * bytes * 60 / 1_048_576 }
  }

  private fun check(generate: (Random) -> NumericQuestion, formula: (List<Double>) -> Double) {
    val random = Random(3)
    repeat(40) {
      val question = generate(random)
      val values = NUMBER.findAll(question.text.replace("-XX:MaxRAMPercentage=", "MaxRAMPercentage ").replace("NewRatio=2", ""))
        .map { it.value.toDouble() }.toList()
      val expected = formula(values)
      assertThat(question.answer).isCloseTo(expected, expected * 1e-9)
    }
  }

  private companion object {
    val NUMBER = Regex("""\d+(\.\d+)?""")
  }
}
