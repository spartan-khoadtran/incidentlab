package com.c0x12c.incidentlab.labctl.quiz.concepts

import com.c0x12c.incidentlab.labctl.quiz.ChoiceQuestion

/** Concept drills for docs/methods.md: one situation, four readings, one of them right. */
internal object MethodsConcepts {

  val all: List<ChoiceQuestion> = listOf(
    // ---------------------------------------------------------------- Methods for the root cause
    ChoiceQuestion(
      text = "You think the DB pool of 4 is the limit on /checkout. Which of these is a prediction that can fail?",
      answer = "If the pool is the limit, raising pool_max from 4 to 8 drops checkout p99 within a minute",
      others = listOf(
        "The pool is involved in the slowness somehow",
        "Something in the DB layer is wrong",
        "The pool may or may not be full, depending on the load"
      ),
      explanation = "A prediction names a number and a direction. If p99 does not drop, the hypothesis is dead. The other three can never be wrong, so they prove nothing.",
      doc = "Methods for the root cause > Scientific method"
    ),
    ChoiceQuestion(
      text = "Before touching the system, which kind of test do you run first on a hypothesis?",
      answer = "An observational one: read a panel, a trace or a log that already answers it",
      others = listOf(
        "An experimental one: change the setting and watch",
        "A load test against production",
        "None: a good hypothesis needs no test"
      ),
      explanation = "An observational test costs nothing and changes nothing. In the lab, the signal question of make answer is that test.",
      doc = "Methods for the root cause > Scientific method"
    ),
    ChoiceQuestion(
      text = "The data to test your hypothesis does not exist: there is no metric for the wait you suspect. What is the next step of the diagnosis cycle?",
      answer = "Add the instrumentation (a metric, a log line, a span), then read the data",
      others = listOf(
        "Guess from the metrics you have and move on",
        "Drop the hypothesis: no data means it is wrong",
        "Restart the service and see if it helps"
      ),
      explanation = "The cycle is hypothesis, instrumentation, data, new hypothesis. The missing metric is the step people skip. A guess teaches nothing.",
      doc = "Methods for the root cause > Diagnosis cycle"
    ),
    ChoiceQuestion(
      text = "The DB span from the app reads 200 ms and Postgres reports 5 ms of exec time for the same query. In the diagnosis cycle, what is that gap?",
      answer = "The next hypothesis: the 195 ms are waited on the client side, so look at the pool",
      others = listOf(
        "A measurement error in one of the two tools",
        "The end of the lap: the DB is proven fine, close the ticket",
        "Network latency, by definition"
      ),
      explanation = "Two readings that disagree are the next question. Each lap narrows the question until a trace or a metric shows the mechanism.",
      doc = "Methods for the root cause > Diagnosis cycle"
    ),
    ChoiceQuestion(
      text = "A trace of one checkout: 250 ms of pool wait, 80 ms in pricing, 40 ms of queries, 30 ms of other work. The target is 300 ms. By Method R, where do you start?",
      answer = "The pool wait: the largest share, and removing it alone meets the target",
      others = listOf(
        "The pricing call: an external service is the usual suspect",
        "The queries: SQL is the easiest to tune",
        "All four at once, to be safe"
      ),
      explanation = "Method R works on the largest share of the one task that matters, and stops when the task meets its target. 400 − 250 = 150 ms is under 300 ms.",
      doc = "Methods for the root cause > Method R"
    ),
    ChoiceQuestion(
      text = "Host CPU is at 90% and the team wants to tune it. By Method R, what is wrong with that starting point?",
      answer = "Nothing is slow until a task waits for that CPU. Start from the response time of the business task",
      others = listOf(
        "Nothing: 90% CPU is always the bottleneck",
        "CPU cannot be profiled, so tune memory instead",
        "Method R only works for databases"
      ),
      explanation = "R is response time. The profile of one task says what it waited for. A busy resource that no task waits for is not the problem.",
      doc = "Methods for the root cause > Method R"
    ),
    ChoiceQuestion(
      text = "An alert says p99 of /checkout is 450 ms. Is that high?",
      answer = "You cannot tell without a baseline: compare with the same hour last week",
      others = listOf(
        "Yes: anything above 200 ms is slow",
        "No: p99 under 1 s is fine everywhere",
        "Yes, if CPU is also high"
      ),
      explanation = "High has no meaning without a normal value. Traffic repeats by day and by week, so offset 1w on the same chart is the baseline.",
      doc = "Methods for the root cause > Baseline statistics"
    ),
    ChoiceQuestion(
      text = "No incident is open. What does static performance tuning check?",
      answer = "The configuration with no load: versions, limits, pool sizes, timeouts, GC flags, container limits",
      others = listOf(
        "The p99 under a load test",
        "The flame graph of the busiest service",
        "The error budget of the last month"
      ),
      explanation = "Many incidents are a setting that was always wrong and a load that finally reached it. Read every limit and write it next to its metric.",
      doc = "Methods for the root cause > Static performance tuning"
    ),
    ChoiceQuestion(
      text = "A client times out at 1 s. The server answers that call in 1.2 s on average. Which method finds this before an incident, and what does it see?",
      answer = "Static performance tuning: the client gives up before the server answers, so every call is wasted work",
      others = listOf(
        "Workload characterization: the client sends too much",
        "Off-CPU analysis: the server waits on a lock",
        "Nothing finds it before an incident"
      ),
      explanation = "Mismatched timeouts are a setting, visible with no load at all. The client sees errors, the server sees successes it worked on for nothing.",
      doc = "Methods for the root cause > Static performance tuning"
    ),
    ChoiceQuestion(
      text = "After the pool fix, p99 of /checkout is 280 ms against an SLO of 300 ms. A teammate wants to tune the pricing call next. What do you do?",
      answer = "Stop: users are inside the SLO, and every further change is a risk",
      others = listOf(
        "Keep tuning: faster is always better",
        "Tighten the SLO so the next change is needed",
        "Tune the pricing call only on the weekend"
      ),
      explanation = "The stop rules: inside the SLO, the next bottleneck is outside your control, the next gain costs more than it is worth, or the bottleneck moved to a cheaper resource.",
      doc = "Methods for the root cause > Knowing when to stop"
    ),
    ChoiceQuestion(
      text = "You fixed the DB, and the limit is now the api CPU, which is one more container away. Is that a good place to stop?",
      answer = "Yes: the bottleneck moved to a cheaper resource that is easy to scale",
      others = listOf(
        "No: a bottleneck is a bottleneck",
        "No: CPU limits must be removed before stopping",
        "Yes, but only after the DB is scaled too"
      ),
      explanation = "Moving the limit from a hard-to-scale resource to a cheap one is a result. The next gain costs more than it is worth.",
      doc = "Methods for the root cause > Knowing when to stop"
    ),

    // ---------------------------------------------------------------- Working an incident
    ChoiceQuestion(
      text = "A ticket says checkout is slow. Which five questions come before any dashboard?",
      answer = "What makes you think there is a problem, has it ever worked well, what changed, who is affected, what is the environment",
      others = listOf(
        "CPU, memory, disk, network, errors",
        "Rate, errors, duration, saturation, utilization",
        "Which host, which pod, which thread, which lock, which query"
      ),
      explanation = "The answers decide where to look first and rule out half the system in two minutes. Write them down: the later steps read from them.",
      doc = "Working an incident > Problem statement"
    ),
    ChoiceQuestion(
      text = "You raise the pool and roll back the deploy in the same minute. p99 recovers. What do you know?",
      answer = "Not which one fixed it: two changes at once cannot be told apart, and the deploy may ship again",
      others = listOf(
        "The deploy was the cause, since rollbacks are the usual fix",
        "The pool was the cause, since it is the lower layer",
        "Both were needed"
      ),
      explanation = "Change one thing, measure for a full window, keep or revert. Then the next change.",
      doc = "Working an incident > One change at a time"
    ),
    ChoiceQuestion(
      text = "You changed one setting 20 seconds ago, and the rate(...[1m]) panel has not moved. What does that mean?",
      answer = "Nothing yet: a 1-minute rate needs a full minute after the change before it shows the new state",
      others = listOf(
        "The change had no effect, revert it",
        "Prometheus has stopped scraping",
        "The change worked, since nothing got worse"
      ),
      explanation = "Decide the number and its direction before the change, then wait for the window, then compare with the baseline, not with the minute before.",
      doc = "Working an incident > One change at a time"
    ),
    ChoiceQuestion(
      text = "Why keep the dead ends of an investigation, not only the cause?",
      answer = "The next person skips them, and the next incident starts further along",
      others = listOf(
        "To show how much work was done",
        "They are the cause more often than not",
        "Only the cause matters, dead ends are noise"
      ),
      explanation = "Keep three things: the timeline, the evidence used, the dead ends. In the lab, make answer asks for one line and make history shows it next to the score.",
      doc = "Working an incident > Writing it down"
    )
  )
}
