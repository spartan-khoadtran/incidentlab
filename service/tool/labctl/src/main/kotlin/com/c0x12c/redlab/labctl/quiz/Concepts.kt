package com.c0x12c.redlab.labctl.quiz

/**
 * Concept drills: one situation, four readings, one of them right. Together with [Numbers] they
 * touch every subsection of the guide, which `GuidePointersTest` checks.
 */
object Concepts {

  val all: List<ChoiceQuestion> = listOf(
    // ---------------------------------------------------------------- Overview
    ChoiceQuestion(
      text = "You are new to an incident. In which order do RED and USE usually go?",
      answer = "RED first, to find which service has the problem; then USE, to find which resource is the bottleneck",
      others = listOf(
        "USE first on every host, then RED on the service that owns the busiest host",
        "Either one alone is enough; they measure the same thing from two angles",
        "RED for load problems, USE for code problems; pick one from the ticket"
      ),
      explanation = "RED asks whether the service serves well and where. USE asks why, by naming the saturated resource.",
      doc = "Overview"
    ),
    ChoiceQuestion(
      text = "What is the blind spot of USE?",
      answer = "It does not say whether users are affected; a busy resource is not always a problem",
      others = listOf(
        "It only works for hardware, not for software resources like pools",
        "It cannot see errors at all",
        "It needs distributed tracing to work"
      ),
      explanation = "USE looks at the resource from inside. RED is what says whether users feel it.",
      doc = "Overview"
    ),

    // ---------------------------------------------------------------- RED method in detail
    ChoiceQuestion(
      text = "The duration of /checkout doubled. The request rate did not move. What does the flat rate tell you?",
      answer = "Suspect the system itself (code, config, a resource), not load",
      others = listOf(
        "The problem is load: users send heavier requests",
        "Nothing yet: rate never helps with latency",
        "The LB is dropping requests, so the rate only looks flat"
      ),
      explanation = "Rate flat and duration up points at architecture. Rate and duration up together may be load; check with workload characterization.",
      doc = "RED method in detail > Rate"
    ),
    ChoiceQuestion(
      text = "Orders are down 30% on the business dashboard. Every service dashboard is green and nobody was paged. What do you check first?",
      answer = "The request rate against the baseline, split by client or tenant: who stopped calling",
      others = listOf(
        "Error rates on every service, because something must be failing",
        "Nothing: green dashboards mean the system is fine",
        "Host CPU and memory on the API servers"
      ),
      explanation = "A drop in rate is a symptom too. A crashed client, a wrong DNS record or a broken upstream stops the requests before they arrive, and the service looks healthy because it has nothing to do.",
      doc = "RED method in detail > Rate"
    ),
    ChoiceQuestion(
      text = "Which of these should count in the error rate of an HTTP service?",
      answer = "A client-side timeout that never got a status code",
      others = listOf(
        "A 404 for a product id that does not exist",
        "A 401 after the user's token expired",
        "A 429 from the rate limiter"
      ),
      explanation = "Timeouts are failures even without a status code. 4xx, 401/403 and 429 are tracked on their own: they tell a different story (client errors, auth, saturation).",
      doc = "RED method in detail > Errors"
    ),
    ChoiceQuestion(
      text = "Service A shows 0.1% errors. Service B shows 20% errors. Which one is worse for users?",
      answer = "You cannot tell from the ratio alone; multiply by the rate to get the count of failed requests",
      others = listOf(
        "B, always: 20% is 200 times more than 0.1%",
        "A, because low ratios hide big problems",
        "Neither: anything under 50% is noise"
      ),
      explanation = "At low traffic 2 of 10 is 20% and may not matter. At high traffic 0.1% can be thousands of users. Watch both the ratio and the count.",
      doc = "RED method in detail > Errors"
    ),
    ChoiceQuestion(
      text = "A dependency dies. The service now answers 500 right away for 30% of requests, and the combined p99 went down. What should you read instead?",
      answer = "The duration of successful requests and of failed requests, as two separate series",
      others = listOf(
        "The mean duration: it is more stable than p99 during incidents",
        "Only the error rate: duration is meaningless now",
        "The p99 of the dependency, since the service looks fine"
      ),
      explanation = "Fail-fast errors pull the combined latency down. Split successes from errors, or the system looks better than before.",
      doc = "RED method in detail > Duration"
    ),
    ChoiceQuestion(
      text = "You have the p99 of each of 20 pods. How do you get the p99 of the whole service?",
      answer = "Merge the latency histograms of all pods, then compute the percentile",
      others = listOf(
        "Average the 20 p99 values",
        "Take the median of the 20 p99 values",
        "Take the p99 of the busiest pod"
      ),
      explanation = "Percentiles cannot be averaged. The service-level p99 comes from the combined histogram buckets.",
      doc = "RED method in detail > Duration"
    ),
    ChoiceQuestion(
      text = "During an incident users complain, but the p99 panel still looks normal. What explains that?",
      answer = "Duration is recorded when a request ends; the requests still hanging are not counted yet",
      others = listOf(
        "The users are on a slow network; the server is fine",
        "p99 is too coarse; use the mean",
        "Prometheus is behind by a few minutes"
      ),
      explanation = "Requests that are still waiting do not appear in the histogram until they finish or time out. Watch the number of requests in flight to see the problem sooner.",
      doc = "RED method in detail > Duration"
    ),
    ChoiceQuestion(
      text = "A service gets a few dozen requests per minute. Its p99 jumps around a lot. What is the best response?",
      answer = "Use a longer time window: with little traffic p99 is almost the max",
      others = listOf(
        "Alert on it anyway; jumps mean real problems",
        "Switch to the mean, percentiles do not work at low traffic",
        "Add more histogram buckets"
      ),
      explanation = "With few samples p99 is nearly the single slowest request and is unstable. A longer window gives it enough samples.",
      doc = "RED method in detail > Duration"
    ),
    ChoiceQuestion(
      text = "What is Duration, in RED terms, for a Kafka consumer?",
      answer = "Processing time per message, read together with consumer lag",
      others = listOf(
        "Broker disk write time",
        "Time between two polls of the consumer",
        "The producer's send time"
      ),
      explanation = "The consumer's D is the processing time per message. Lag (time in the queue) is what users feel on top of it.",
      doc = "RED method in detail > RED for other workloads"
    ),
    ChoiceQuestion(
      text = "A nightly batch job: what is its Duration in RED terms?",
      answer = "The job's run time, and how late the data is (freshness)",
      others = listOf(
        "The number of jobs per hour",
        "The count of failed jobs",
        "The CPU time of the job's host"
      ),
      explanation = "For a batch job, Rate is jobs per hour or records per second, Errors are failed jobs, and Duration is run time plus data freshness.",
      doc = "RED method in detail > RED for other workloads"
    ),

    // ---------------------------------------------------------------- USE method in detail
    ChoiceQuestion(
      text = "In USE, what is saturation?",
      answer = "How much work is waiting for the resource, usually a queue; another name for it is pressure",
      others = listOf(
        "The share of time the resource was busy",
        "The count of error events on the resource",
        "Utilization above 100%"
      ),
      explanation = "Utilization is how busy, saturation is how much is waiting, errors are error events.",
      doc = "USE method in detail > Three metrics"
    ),
    ChoiceQuestion(
      text = "In which order does USE check the three metrics of a resource?",
      answer = "Errors, then saturation, then utilization",
      others = listOf(
        "Utilization, then saturation, then errors",
        "Saturation, then utilization, then errors",
        "Any order; they are independent"
      ),
      explanation = "Errors are objective and quick. Saturation above 0 is easy to read. Utilization needs interpretation, so it comes last.",
      doc = "USE method in detail > Order of checks"
    ),
    ChoiceQuestion(
      text = "Going through the resource list, you find one saturated resource. What next?",
      answer = "Dig into it with another method, then come back and finish the loop over the remaining resources",
      others = listOf(
        "Stop: the first saturated resource is the root cause",
        "Restart the service that owns it and watch",
        "Skip to utilization on all the other resources"
      ),
      explanation = "The first thing you find may be a problem, but not the problem. Finish the loop.",
      doc = "USE method in detail > Order of checks"
    ),
    ChoiceQuestion(
      text = "A disk shows 65% utilization averaged over 5 minutes. Is that a problem?",
      answer = "It can be: the long window hides bursts at 100%, and queueing delay grows fast above 60 to 70%",
      others = listOf(
        "No: anything under 100% has room",
        "No: disks are fine up to 95%",
        "Yes, always: disks must stay under 50%"
      ),
      explanation = "Utilization above 60% deserves attention. Confirm with saturation (queue length, await) and with the effect on users.",
      doc = "USE method in detail > Reading the numbers"
    ),
    ChoiceQuestion(
      text = "Which USE reading deserves a look at any value above zero?",
      answer = "Saturation: anything waiting in a queue may be a problem",
      others = listOf(
        "Utilization: a resource should idle",
        "None: you need a baseline for every metric",
        "Throughput: more work is always a risk"
      ),
      explanation = "Saturation above 0 can be a problem, errors above 0 deserve a look, utilization needs interpretation.",
      doc = "USE method in detail > Reading the numbers"
    ),
    ChoiceQuestion(
      text = "iostat shows an SSD at 100% util. Can the SSD take more work?",
      answer = "Maybe: time-based 100% busy is not 100% capacity; a device that works in parallel can still take more",
      others = listOf(
        "No: 100% means full for every resource",
        "Yes, always: %util is meaningless for SSDs",
        "No: it is about to fail"
      ),
      explanation = "Time-based utilization is busy time over the window. Capacity-based (memory, a pool at 50/50) is the one where 100% means no room left.",
      doc = "USE method in detail > Time-based and capacity-based"
    ),
    ChoiceQuestion(
      text = "A connection pool reads 50 in use out of 50. What kind of utilization is that, and what does 100% mean?",
      answer = "Capacity-based; 100% means no room left, so new requests queue or fail",
      others = listOf(
        "Time-based; 100% means busy all the time but it can still take more",
        "Neither; pools are measured by saturation only",
        "Capacity-based; 100% is normal for a pool"
      ),
      explanation = "A pool is a capacity resource. At 100% every new request waits for a connection or times out.",
      doc = "USE method in detail > Time-based and capacity-based"
    ),
    ChoiceQuestion(
      text = "Which signal shows memory saturation on a Linux host?",
      answer = "Swap in/out, page scanning, OOM kills, PSI memory",
      others = listOf(
        "Used memory above 80%",
        "Free memory below 1 GB",
        "The size of the page cache"
      ),
      explanation = "Used and free say little: Linux uses free memory as page cache. Saturation shows as swapping, scanning, OOM kills and pressure.",
      doc = "USE method in detail > Hardware checklist on Linux"
    ),
    ChoiceQuestion(
      text = "What is the CPU saturation signal in the Linux hardware checklist?",
      answer = "A run queue longer than the core count (vmstat column r), or PSI cpu",
      others = listOf(
        "CPU utilization above 80%",
        "Load average above 1",
        "Context switches per second"
      ),
      explanation = "Threads waiting for a CPU are the queue. Utilization alone does not show that anyone is waiting.",
      doc = "USE method in detail > Hardware checklist on Linux"
    ),
    ChoiceQuestion(
      text = "A Node.js or Netty event loop as a resource: what is its saturation metric?",
      answer = "Event loop lag",
      others = listOf(
        "Heap used against max",
        "Open file descriptors",
        "Requests per second"
      ),
      explanation = "The loop is a single thread. Work waiting for it shows as lag before a scheduled task runs.",
      doc = "USE method in detail > Software resources"
    ),
    ChoiceQuestion(
      text = "A rate limiter or bulkhead as a resource: which of the three USE metrics is a 429 response?",
      answer = "Errors: a rejected request",
      others = listOf(
        "Utilization: permits in use",
        "Saturation: requests waiting for a permit",
        "None: 429 is a client error"
      ),
      explanation = "Permits in use over max is utilization, requests waiting is saturation, rejections (429) are errors.",
      doc = "USE method in detail > Software resources"
    ),
    ChoiceQuestion(
      text = "A Linux host shows 92% memory used. Nothing else looks wrong. What do you conclude?",
      answer = "Nothing yet: Linux uses free memory as page cache; look at available memory and at saturation (swap, OOM, PSI)",
      others = listOf(
        "The host is about to run out of memory",
        "A memory leak in the app",
        "Add RAM before anything else"
      ),
      explanation = "High used memory is not a problem by itself. The saturation signals are what matter.",
      doc = "USE method in detail > Pitfalls"
    ),
    ChoiceQuestion(
      text = "A resource (an internal bus, a cloud quota) has no metric at all. What do you do with it in a USE pass?",
      answer = "Keep it on the list as a known unknown, with a plan to add a metric",
      others = listOf(
        "Drop it: USE only covers what you can measure",
        "Assume it is fine because nobody alerted",
        "Replace it with the nearest host metric"
      ),
      explanation = "USE loops over resources, not over tools. A resource with no metric still goes on the list.",
      doc = "USE method in detail > Pitfalls"
    ),

    // ---------------------------------------------------------------- RED at many layers
    ChoiceQuestion(
      text = "Which layer is the only one that sees a DNS failure or a client-side timeout?",
      answer = "The client (browser, mobile app)",
      others = listOf(
        "The load balancer",
        "The service's entry span",
        "The database"
      ),
      explanation = "Errors before the request reaches the edge exist only in the client's numbers. Every lower layer shows nothing.",
      doc = "RED at many layers > What each layer sees"
    ),
    ChoiceQuestion(
      text = "What does a service's entry span (APM) not see?",
      answer = "The time a request waited before the app accepted it, and the errors the LB created",
      others = listOf(
        "The time spent in the app's own code",
        "The calls the app makes to the database",
        "The errors the app logged"
      ),
      explanation = "The entry span starts when the handler starts. The queue ahead of it and the LB's own 5xx are invisible from there.",
      doc = "RED at many layers > What each layer sees"
    ),
    ChoiceQuestion(
      text = "Queries per second at the DB went up 4 times. The API request rate is flat. What kind of cause is likely?",
      answer = "The fan-out grew: N+1 queries, cache misses or retries, not more users",
      others = listOf(
        "A traffic surge from one tenant",
        "The DB is slow, so queries pile up",
        "Prometheus is double-counting"
      ),
      explanation = "When a lower layer's rate rises and the upper one is flat, the multiplier between them changed. Next: workload characterization at the DB, which query and called from where.",
      doc = "RED at many layers > Why Rate differs"
    ),
    ChoiceQuestion(
      text = "The LB counts 1,200 requests/s, APM counts 1,000 requests/s for the same service, and both are healthy. A common reason?",
      answer = "Health checks, bots and synthetic tests: the LB counts them, APM usually filters them out",
      others = listOf(
        "APM lost 200 requests per second",
        "The LB double counts retries",
        "The service is overloaded"
      ),
      explanation = "Each layer has its own idea of what a request is. A steady gap of that kind is normal; a gap that changes is a clue.",
      doc = "RED at many layers > Why Rate differs"
    ),
    ChoiceQuestion(
      text = "Users fail in bulk, but APM reports an error rate of 0 for the service. Where do the errors come from?",
      answer = "The LB or gateway creates them itself (502/503/504), usually because no backend is healthy; the requests never reach the app",
      others = listOf(
        "The users' network; the system is fine",
        "APM is sampling the errors away",
        "A bug in the error rate panel"
      ),
      explanation = "When no backend is healthy the LB returns 5xx itself. The app logs nothing because the request never arrived.",
      doc = "RED at many layers > Why Errors differ"
    ),
    ChoiceQuestion(
      text = "A service calls the DB, the call fails, the service retries and succeeds. What do the two layers record?",
      answer = "The DB sees an error, the service sees a success, and only the service's latency goes up",
      others = listOf(
        "Both see an error",
        "Both see a success",
        "The service sees an error, the DB sees a success"
      ),
      explanation = "Retries hide errors from the layer above. The clue is latency rising while the upper layer stays green.",
      doc = "RED at many layers > Why Errors differ"
    ),
    ChoiceQuestion(
      text = "The client times out after 5 s. The server finishes the same request at 7 s and records a 200. Who sees an error?",
      answer = "Only the client; the server sees a success and wasted the work",
      others = listOf(
        "Only the server",
        "Both",
        "Neither: a 200 is a success everywhere"
      ),
      explanation = "Mismatched timeouts between layers make the client see errors the server never records.",
      doc = "RED at many layers > Why Errors differ"
    ),
    ChoiceQuestion(
      text = "When a service is overloaded, which part of the request time does APM not see?",
      answer = "The wait before the app accepts the request: the LB queue, the accept queue, the worker queue",
      others = listOf(
        "The time in the app's own handler",
        "The time in downstream calls",
        "The serialization of the response"
      ),
      explanation = "App duration starts when the app accepts the request. The queueing ahead of it grows under overload, and APM does not count it.",
      doc = "RED at many layers > Why Duration differs"
    ),
    ChoiceQuestion(
      text = "A DB span seen from the app is 200 ms. The DB itself says the query took 5 ms. Where did the other 195 ms go?",
      answer = "Waiting for a pool connection on the app side, or the network",
      others = listOf(
        "Inside the query; the DB clock is wrong",
        "Nowhere: spans always over-count",
        "Garbage collection on the DB host"
      ),
      explanation = "A client-side DB span can include the wait for a connection. The server-side exec time does not.",
      doc = "RED at many layers > Why Duration differs"
    ),
    ChoiceQuestion(
      text = "One request fans out to 20 downstream calls in parallel. Which percentile should you watch at the lower layer?",
      answer = "p99.9 and beyond: with 20 calls, about 18% of upstream requests hit a call above its p99",
      others = listOf(
        "p50: the median call decides the total",
        "p99 is enough at every layer",
        "The mean: fan-out averages the tail out"
      ),
      explanation = "The upstream waits for the slowest call. The share that hits a slow call is 1 − p^N, so the tail of the lower layer is amplified.",
      doc = "RED at many layers > Tail amplification"
    ),
    ChoiceQuestion(
      text = "App latency went up. One downstream span went up by the same amount. Where is the problem?",
      answer = "In that downstream: do RED and USE of the downstream next",
      others = listOf(
        "In the app: profile the app's CPU",
        "At the LB: compare LB and app",
        "Unknown: restart the app and see"
      ),
      explanation = "When one span explains the whole rise, the problem sits behind it. Follow the arrow on the service map.",
      doc = "RED at many layers > Compare two adjacent layers"
    ),
    ChoiceQuestion(
      text = "Client latency is up. LB latency is flat. Where do you look?",
      answer = "Network, CDN, DNS, a bigger payload or slow client rendering: split client latency into DNS, TLS, TTFB, download",
      others = listOf(
        "The database",
        "The app's connection pool",
        "The LB's health checks"
      ),
      explanation = "If the LB does not see the rise, the time is lost before the LB or after the response leaves it.",
      doc = "RED at many layers > Compare two adjacent layers"
    ),
    ChoiceQuestion(
      text = "App latency is up. Every downstream span is flat. What next?",
      answer = "The problem is in the app itself: USE for host and runtime, then profiling",
      others = listOf(
        "RED of the downstreams again, they must be hiding it",
        "Scale the database",
        "Compare client and LB latency"
      ),
      explanation = "No downstream explains the rise, so the time is spent in the app: CPU, GC, event loop, locks.",
      doc = "RED at many layers > Compare two adjacent layers"
    ),
    ChoiceQuestion(
      text = "Consumer lag grows steadily. Processing time per message is flat and errors are zero. What is happening?",
      answer = "The produce rate is above the consume rate: the consumer group is saturated",
      others = listOf(
        "The consumer is failing silently",
        "The consumer got slower per message",
        "Lag always grows during the day"
      ),
      explanation = "Lag is the consumer's saturation. It grows when more comes in than goes out, even when each message is processed as fast as before.",
      doc = "RED at many layers > The async layer"
    ),
    ChoiceQuestion(
      text = "One partition's lag grows in a straight line. The other partitions are fine. Errors stay low. The likely cause?",
      answer = "A poison message retried for ever blocks that partition",
      others = listOf(
        "The producer sends only to that partition",
        "The broker lost a disk",
        "The consumer group is rebalancing"
      ),
      explanation = "A message that always fails and is never skipped blocks its partition, because partitions are processed in order.",
      doc = "RED at many layers > The async layer"
    ),
    ChoiceQuestion(
      text = "Why is lag in seconds easier to read than lag in messages?",
      answer = "10k messages is 100 s at 100 msg/s but 1 s at 10k msg/s; seconds compare directly with the users' deadline",
      others = listOf(
        "Kafka only reports lag in seconds",
        "Messages are harder to count",
        "Seconds never change with the consume rate"
      ),
      explanation = "Lag in time is about lag in messages divided by the consume rate, and it compares with an SLA.",
      doc = "RED at many layers > The async layer"
    ),

    // ---------------------------------------------------------------- USE at many layers
    ChoiceQuestion(
      text = "Which saturation signal belongs to the hypervisor or cloud instance layer?",
      answer = "CPU steal time, or running out of CPU credits on a burstable instance",
      others = listOf(
        "Event loop lag",
        "Connection pool wait time",
        "Container CPU throttling"
      ),
      explanation = "Each layer has its own saturation. Steal and credits sit at the hypervisor; throttling is the cgroup; lag is the runtime; pool wait is the app.",
      doc = "USE at many layers > Layers and their metrics"
    ),
    ChoiceQuestion(
      text = "A managed service or a third-party API as a resource: what are its USE errors?",
      answer = "429, ThrottlingException, connection refused",
      others = listOf(
        "OOMKilled",
        "GC pauses",
        "Dropped packets at the NIC"
      ),
      explanation = "Quotas show up as throttling responses and refused connections. Used over quota is their utilization.",
      doc = "USE at many layers > Layers and their metrics"
    ),
    ChoiceQuestion(
      text = "Host CPU is at 40%, but p99 went up. The container has a 1 CPU limit and the app runs 4 busy threads. What is going on?",
      answer = "CPU throttling: the quota of 100 ms per period is used up in 25 ms, then the app waits for the rest of the period",
      others = listOf(
        "The host is lying; it is really at 100%",
        "The network is slow",
        "A memory leak"
      ),
      explanation = "The container's limit is lower than the host's capacity. Host CPU looks normal while the cgroup throttles the app.",
      doc = "USE at many layers > One layer hides another"
    ),
    ChoiceQuestion(
      text = "The node has plenty of free memory, but a pod keeps getting OOMKilled. Why?",
      answer = "It hit the container's memory limit, not the node's",
      others = listOf(
        "The node is swapping",
        "The kernel has a bug",
        "The pod's disk is full"
      ),
      explanation = "Each layer has its own limit. The pod's limit is reached long before the node's memory is.",
      doc = "USE at many layers > One layer hides another"
    ),
    ChoiceQuestion(
      text = "The connection pool is at 10/10. The DB is at 30% CPU. What is the bottleneck?",
      answer = "The pool size on the app side, not the DB",
      others = listOf(
        "The DB: 30% CPU is already high",
        "The network between them",
        "Nothing: the pool is just well used"
      ),
      explanation = "A full pool with an idle DB is a software resource saturated inside the app. Mind the reverse too: raising the pool across many pods can exceed the DB's max connections.",
      doc = "USE at many layers > Common examples"
    ),
    ChoiceQuestion(
      text = "Outbound connections fail, but CPU and network throughput are low. What kind of limit is this?",
      answer = "Ephemeral ports, a full conntrack table, or a NAT gateway out of ports",
      others = listOf(
        "CPU throttling",
        "Disk I/O saturation",
        "A GC pause"
      ),
      explanation = "Some limits sit in the OS or in the cloud network path and have nothing to do with CPU or bandwidth.",
      doc = "USE at many layers > Common examples"
    ),
    ChoiceQuestion(
      text = "Service A calls service B. If you treat B as a resource of A, what is B's utilization?",
      answer = "Requests in flight at B against B's capacity",
      others = listOf(
        "B's error rate",
        "A's request rate",
        "B's CPU utilization"
      ),
      explanation = "Utilization is in-flight over capacity. Saturation is A's requests waiting for B. Errors are the errors B returns.",
      doc = "USE at many layers > A service as a resource"
    ),
    ChoiceQuestion(
      text = "Why write the limit (quota, max) next to every utilization metric?",
      answer = "Utilization only has a meaning when you know the denominator of that same layer",
      others = listOf(
        "Grafana needs it to draw the panel",
        "So that alerts never fire",
        "It is a documentation habit with no effect on the reading"
      ),
      explanation = "CPU at 0.9 cores is 45% of a 2-core limit and 180% of a 0.5-core limit. Without the limit the number says nothing.",
      doc = "USE at many layers > How to do it"
    ),

    // ---------------------------------------------------------------- Linking RED and USE
    ChoiceQuestion(
      text = "In Little's Law L = λ × W, what are the three terms?",
      answer = "L requests in flight, λ the request rate, W the mean time a request spends in the system",
      others = listOf(
        "L latency, λ load, W workers",
        "L pool size, λ errors per second, W wait time",
        "L lag, λ partitions, W consumers"
      ),
      explanation = "Rate times mean duration gives the mean concurrency. Compare it with the pool or thread count.",
      doc = "Linking RED and USE > Little's Law"
    ),
    ChoiceQuestion(
      text = "Is Little's Law a good tool for tail latency (p99)?",
      answer = "No: it uses averages for a system in steady state; good for capacity estimates, not for the tail",
      others = listOf(
        "Yes: it gives p99 directly",
        "Yes, if you use the max rate",
        "No: it only works for queues, not for services"
      ),
      explanation = "Little's Law links means. For p99 you need the distribution or queueing theory.",
      doc = "Linking RED and USE > Little's Law"
    ),
    ChoiceQuestion(
      text = "Why does utilization of 60 to 70% already count as a possible problem?",
      answer = "In a single-server model response time grows as 1/(1 − U); the curve bends hard after 60 to 70%",
      others = listOf(
        "Because monitoring tools round numbers up",
        "Because hardware fails above 70%",
        "It does not; only 100% matters"
      ),
      explanation = "At 80% utilization the response time is 5 times the service time; at 90%, 10 times. Latency rises well before the resource reports full.",
      doc = "Linking RED and USE > Why latency rises before 100%"
    ),
    ChoiceQuestion(
      text = "Rate and duration are rising together. Is that proof of a load problem?",
      answer = "No: clients that time out and retry raise the rate too; separate new traffic from retry traffic first",
      others = listOf(
        "Yes: more requests always means more users",
        "No: rate never matters",
        "Yes, if duration rose first"
      ),
      explanation = "In the causal chain, saturation makes requests slow, clients retry, and the retries raise the rate. The loop feeds itself.",
      doc = "Linking RED and USE > Causal chain"
    ),
    ChoiceQuestion(
      text = "In the causal chain, what makes the layer above a saturated resource saturate too?",
      answer = "Duration rises, so by Little's Law more requests are in flight, and the pools and threads above are held longer",
      others = listOf(
        "Errors propagate upward as exceptions",
        "The LB sends more traffic to the slow service",
        "Nothing; saturation stays in one layer"
      ),
      explanation = "Longer requests mean more concurrency at the same rate. That fills the pools one layer up.",
      doc = "Linking RED and USE > Causal chain"
    ),
    ChoiceQuestion(
      text = "In the combined process, the rate is flat. What is the next step?",
      answer = "Compare RED of two adjacent layers to find the first layer where the numbers drift",
      others = listOf(
        "Workload characterization: who sends the load",
        "Scale out and watch",
        "Profile the CPU right away"
      ),
      explanation = "Rate flat means suspect architecture. Step 4 locates the layer; step 5 runs USE on that layer's resource stack.",
      doc = "Linking RED and USE > The combined process"
    ),
    ChoiceQuestion(
      text = "After USE over the whole resource stack, nothing is saturated. What do you suspect?",
      answer = "Locks, waits on a downstream, or a new code path: use latency analysis, the critical path of a trace, and a before/after comparison of the deploy",
      others = listOf(
        "A hardware fault",
        "Load: scale out",
        "The monitoring is broken"
      ),
      explanation = "Not every slowdown is a saturated resource. Waiting on a lock or a slow dependency shows no utilization.",
      doc = "Linking RED and USE > The combined process"
    ),
    ChoiceQuestion(
      text = "The fix is deployed. How do you confirm it worked?",
      answer = "RED: duration and errors back to baseline. USE: saturation back to 0",
      others = listOf(
        "CPU utilization is lower",
        "No new tickets for an hour",
        "The deploy pipeline is green"
      ),
      explanation = "Both views must recover. A resource still saturated will bring the symptom back.",
      doc = "Linking RED and USE > The combined process"
    ),

    // ---------------------------------------------------------------- Combining with other methods
    ChoiceQuestion(
      text = "Which methods answer the question 'why is that resource saturated'?",
      answer = "CPU profiling, off-CPU analysis, thread state analysis, latency analysis",
      others = listOf(
        "RED at the user-facing layer",
        "Workload characterization",
        "SLOs and error budgets"
      ),
      explanation = "RED and USE find the place and the resource. The deeper methods find the reason.",
      doc = "Combining with other methods > By question"
    ),
    ChoiceQuestion(
      text = "Which methods answer the question 'when will it saturate again'?",
      answer = "Queueing theory, capacity planning, load tests that ramp up",
      others = listOf(
        "Distributed tracing",
        "The USE checklist",
        "Static performance tuning"
      ),
      explanation = "Predicting the next saturation needs a model of load against capacity, not a snapshot.",
      doc = "Combining with other methods > By question"
    ),
    ChoiceQuestion(
      text = "The request rate went up. What are the four questions of workload characterization?",
      answer = "Who causes the load, why, what it looks like (endpoints, mix), and how it changes over time",
      others = listOf(
        "Which host, which pod, which container, which process",
        "CPU, memory, disk, network",
        "Rate, errors, duration, saturation"
      ),
      explanation = "Split the rate by those dimensions and compare with the baseline. It also finds work that should not exist, like a crawler or an N+1.",
      doc = "Combining with other methods > Workload characterization"
    ),
    ChoiceQuestion(
      text = "How do the four golden signals relate to RED?",
      answer = "They are RED plus the service's saturation",
      others = listOf(
        "They replace RED with host metrics",
        "They are USE for services",
        "They are unrelated"
      ),
      explanation = "Latency, traffic, errors (that is RED) plus saturation, one set per service.",
      doc = "Combining with other methods > Sibling metric sets"
    ),
    ChoiceQuestion(
      text = "What does an SLO add to R, E and D?",
      answer = "A commitment: the share of good requests (for example no error and under 300 ms), with an error budget and burn-rate alerts built on it",
      others = listOf(
        "A new metric unrelated to RED",
        "Hardware limits",
        "A dashboard layout"
      ),
      explanation = "An SLI turns RED into a share of good requests; the SLO sets the target; burn rate is how fast you spend the budget.",
      doc = "Combining with other methods > Sibling metric sets"
    ),
    ChoiceQuestion(
      text = "You found the slow part. By the performance mantras, which fix do you consider first?",
      answer = "Do not do it at all; then do it once; then do less",
      others = listOf(
        "Do it in parallel",
        "Do it cheaper",
        "Do it when nobody waits"
      ),
      explanation = "The order is: do not do it, do it once, do less, do it later, do it when nobody waits, do it in parallel, do it cheaper.",
      doc = "Combining with other methods > Performance mantras"
    ),
    ChoiceQuestion(
      text = "Duration is up but CPU is not. Which deeper method fits?",
      answer = "Off-CPU analysis: the threads wait on locks, I/O or the network",
      others = listOf(
        "CPU profiling and flame graphs",
        "A load test that ramps up",
        "Cache tuning"
      ),
      explanation = "When the time is spent waiting, not computing, you need to see what the threads wait on.",
      doc = "Combining with other methods > Deeper methods"
    ),
    ChoiceQuestion(
      text = "What is 'Linux performance analysis in 60 seconds'?",
      answer = "Ten commands (uptime, dmesg, vmstat, mpstat, pidstat, iostat, free, sar, top): a fast USE pass over one host",
      others = listOf(
        "A one-minute CPU profile",
        "A load test",
        "Reading the last 60 seconds of logs"
      ),
      explanation = "It walks the host's resources quickly: CPU, memory, disk, network, in USE terms.",
      doc = "Combining with other methods > Deeper methods"
    ),
    ChoiceQuestion(
      text = "Why draw deploys, config changes and feature flags on the RED charts?",
      answer = "Many 'architecture' incidents start exactly at a change; the marker turns a guess into a before/after comparison",
      others = listOf(
        "Grafana requires it",
        "To make the charts prettier",
        "To blame the team that deployed"
      ),
      explanation = "Change events on the charts are one of the deeper methods. In the lab some markers are decoys, so still check the numbers.",
      doc = "Combining with other methods > Deeper methods"
    ),
    ChoiceQuestion(
      text = "An engineer checks only the CPU dashboard they know, finds nothing, and says the system is fine. Which anti-method is that?",
      answer = "Streetlight: only looking where the light is",
      others = listOf(
        "Random change",
        "Blame someone else",
        "Drill-down"
      ),
      explanation = "The RED and USE checklists exist to work against this: they force a full pass over services and resources.",
      doc = "Combining with other methods > Anti-methods"
    ),
    ChoiceQuestion(
      text = "'It must be the network', with no evidence. What is the cure for this anti-method?",
      answer = "Compare two adjacent layers: the numbers show where the time is lost",
      others = listOf(
        "Open a ticket with the network team",
        "Restart the service",
        "Raise the timeout"
      ),
      explanation = "Blaming someone else passes the work to another team. Comparing adjacent layers gives evidence instead.",
      doc = "Combining with other methods > Anti-methods"
    ),

    // ---------------------------------------------------------------- In this lab
    ChoiceQuestion(
      text = "In `make answer`, the Nature question (load, architecture or amplified rate) tests which step of the process?",
      answer = "Step 3: reading the rate",
      others = listOf(
        "Step 1: the problem statement",
        "Step 5: USE on the resource stack",
        "Step 8: confirming the fix"
      ),
      explanation = "Nature is the rate step, Location is step 4 (the first layer that drifts), Signal is steps 4 and 5, Cause is steps 5 to 7.",
      doc = "In this lab"
    ),
    ChoiceQuestion(
      text = "Where in the redlab dashboards do you find the DB pool in use, max and waiting?",
      answer = "Dashboard 02 (USE by layer), row 1, Software resources",
      others = listOf(
        "Dashboard 01, row 1, Client",
        "Dashboard 02, row 5, Postgres",
        "Explore > Tempo"
      ),
      explanation = "The pool is a software resource inside api. Postgres' own view (row 5) shows connections by state, not the app's pool.",
      doc = "In this lab"
    ),
    ChoiceQuestion(
      text = "An orange vertical line appears on a chart 2 minutes before the ticket. What is it?",
      answer = "An annotation: a change event that may be a real deploy or a decoy, so check the numbers before trusting it",
      others = listOf(
        "The moment the fault started, always",
        "A Grafana rendering glitch",
        "An alert firing"
      ),
      explanation = "Some annotations are real, some are decoys, and a ticket that says nobody deployed can be wrong.",
      doc = "In this lab"
    ),

    // ---------------------------------------------------------------- Applying on Datadog
    ChoiceQuestion(
      text = "Which Datadog metric shows the 5xx the ALB creates itself, separate from the 5xx of the targets?",
      answer = "aws.applicationelb.httpcode_elb_5xx (the targets' errors are httpcode_target_5xx)",
      others = listOf(
        "trace.servlet.request.errors",
        "system.net.tcp.retrans_segs",
        "aws.applicationelb.request_count"
      ),
      explanation = "ELB 5xx are errors the LB produced; target 5xx came from the backend. The split is the LB layer's RED.",
      doc = "Applying on Datadog > Data sources by layer"
    ),
    ChoiceQuestion(
      text = "On the APM service page latency looks fine, but users wait much longer. What does the service page latency leave out?",
      answer = "The time a request waited before entering the app; compare with the ALB's target_response_time",
      others = listOf(
        "Downstream calls",
        "Errors",
        "Nothing; the users are wrong"
      ),
      explanation = "The service page shows the entry span. The queue ahead of the app is visible only from the LB side.",
      doc = "Applying on Datadog > Common misreadings"
    ),
    ChoiceQuestion(
      text = "Trace ingestion is sampled at 10%. Can you trust the hits and error counts on the APM service page?",
      answer = "Yes: trace metrics are computed over all traffic; only the list of traces you can open is the sampled part",
      others = listOf(
        "No: multiply everything by 10",
        "No: sampled metrics are useless",
        "Only the latency, not the counts"
      ),
      explanation = "Unless sampling happens inside the app's SDK, trace metrics see every request.",
      doc = "Applying on Datadog > Common misreadings"
    ),
    ChoiceQuestion(
      text = "You want the p99 across 50 hosts in Datadog. Which metric type do you need?",
      answer = "A distribution metric, which computes the percentile over all values",
      others = listOf(
        "A gauge of each host's p99, averaged",
        "A count metric",
        "A rate metric"
      ),
      explanation = "The average of each host's p99 is wrong. A distribution keeps the values and computes the real percentile.",
      doc = "Applying on Datadog > Common misreadings"
    ),
    ChoiceQuestion(
      text = "On a service dashboard you put LB latency and APM latency on the same chart. What is the gap between the two lines?",
      answer = "The queueing time before the app accepts the request",
      others = listOf(
        "Clock skew between the two sources",
        "The downstream call time",
        "Network time to the user"
      ),
      explanation = "LB latency includes the wait; APM starts after it. The gap is USE saturation drawn as RED duration.",
      doc = "Applying on Datadog > Dashboard layout for one service"
    ),
    ChoiceQuestion(
      text = "Which signals should page the on-call engineer?",
      answer = "Symptoms at the user-facing layer: SLO burn rate, p99 duration, error rate",
      others = listOf(
        "Pool utilization above 80%",
        "Any CPU throttling",
        "Disk usage above 70%"
      ),
      explanation = "Page on RED symptoms. USE signals are early warnings or tickets, except saturation that predicts a certain incident, like a disk that fills within 2 hours.",
      doc = "Applying on Datadog > Monitors"
    ),

    // ---------------------------------------------------------------- End-to-end investigation example
    ChoiceQuestion(
      text = "In the example, the Postgres span seen from the app is 1.8 s while DBM shows a query p99 of 15 ms. What does the gap mean?",
      answer = "The wait is on the app side (the connection pool), not inside the DB",
      others = listOf(
        "DBM is wrong",
        "The DB is the bottleneck",
        "The network to the DB is slow"
      ),
      explanation = "The DB executes fast. The app waits 1.7 s for a connection: pool 20/20, wait time p99 1.7 s.",
      doc = "End-to-end investigation example > Numbers by layer"
    ),
    ChoiceQuestion(
      text = "In the example, 150 rps per pod and a hold of 140 ms give 21 connections against a pool of 20. What was the right fix?",
      answer = "Roll back now; then call recommendation-service outside the transaction and merge the N+1 into one query",
      others = listOf(
        "Raise the pool to 40 on every pod",
        "Add 6 more pods",
        "Give Postgres more CPU"
      ),
      explanation = "'Do less' (one query) and 'do it later' (call outside the transaction) cut the hold time. Raising the pool only hides the problem.",
      doc = "End-to-end investigation example > Steps"
    ),
    ChoiceQuestion(
      text = "Why is raising the pool size not the fix in the example?",
      answer = "6 pods × 40 survives, but 30 pods × 40 is 1,200 connections, above the DB's max of 1,000; the hold time is the real problem",
      others = listOf(
        "Because pools cannot be resized",
        "Because the DB was already at 100% CPU",
        "Because the pool was not full"
      ),
      explanation = "The saturated resource is a software resource inside the app. Fix the hold time, not the limit.",
      doc = "End-to-end investigation example > Lessons from the example"
    ),

    // ---------------------------------------------------------------- Cheat sheet
    ChoiceQuestion(
      text = "Complete the sentence: RED finds the place, USE finds the resource, ...",
      answer = "... the other methods find the reason",
      others = listOf(
        "... the LB finds the error",
        "... Little's Law finds the fix",
        "... the deploy finds the cause"
      ),
      explanation = "RED and USE are the first pass. Profiling, off-CPU analysis, latency analysis and traces give the root cause.",
      doc = "Cheat sheet"
    )
  )
}
