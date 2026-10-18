package com.c0x12c.incidentlab.labctl.control

/** The services that take faults, by compose service name, and where their admin endpoint answers. */
object Services {
  val ENDPOINTS: Map<String, String> = mapOf(
    "api" to "http://api:8000",
    "pricing" to "http://pricing:8001",
    "worker" to "http://worker:8002",
    "loadgen" to "http://loadgen:8003",
    "noisy" to "http://noisy:8005",
    "pgobserver" to "http://pgobserver:8004"
  )

  /** Container settings the daemon owns even with no exercise running. */
  val BASE_DOCKER: Map<String, Map<String, Any?>> = mapOf(
    "api" to mapOf("cpus" to 2.0),
    "postgres" to mapOf(CgroupApplier.WRITE_IOPS to CgroupApplier.UNLIMITED)
  )
}
