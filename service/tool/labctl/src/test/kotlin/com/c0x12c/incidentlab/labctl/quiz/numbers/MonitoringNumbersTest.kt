package com.c0x12c.incidentlab.labctl.quiz.numbers

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.random.Random
import org.junit.jupiter.api.Test

/** The arithmetic of a few monitoring drills, recomputed from the numbers the question text shows. */
class MonitoringNumbersTest {

  @Test
  fun `the safe rate window is four times the scrape interval shown in the question`() {
    repeat(20) { seed ->
      val question = MonitoringNumbers.rateWindow(Random(seed))
      val scrape = Regex("every (\\d+) s").find(question.text)!!.groupValues[1].toInt()
      assertThat(question.answer).isEqualTo(4.0 * scrape)
    }
  }

  @Test
  fun `tasks in uninterruptible sleep are the load minus the runnable tasks`() {
    repeat(20) { seed ->
      val question = MonitoringNumbers.loadDisk(Random(seed))
      val load = Regex("load average of (\\d+)").find(question.text)!!.groupValues[1].toInt()
      val runnable = Regex("r = (\\d+)").find(question.text)!!.groupValues[1].toInt()
      assertThat(question.answer).isEqualTo((load - runnable).toDouble())
    }
  }

  @Test
  fun `a counter rate is the difference over the seconds between the two reads`() {
    repeat(20) { seed ->
      val question = MonitoringNumbers.counterRate(Random(seed))
      val (start, end) = Regex("read (\\d+) at 10:00:00 and (\\d+) at 10:(\\d\\d):(\\d\\d)").find(question.text)!!.destructured.let { (a, b, m, s) ->
        a.toInt() to Triple(b.toInt(), m.toInt(), s.toInt())
      }
      val seconds = end.second * 60 + end.third
      assertThat(question.answer).isEqualTo((end.first - start).toDouble() / seconds)
    }
  }
}
