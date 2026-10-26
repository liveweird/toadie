package ch.nokillswit.telemetry

import ch.nokillswit.infra.catchingFailures
import java.lang.management.ManagementFactory
import java.net.InetAddress

internal const val MAX_INSTANCE_LENGTH = 100
private const val MAX_BUILD_FIELD_LENGTH = 64

/**
 * Who a sampler is, written once per process run to `telemetry_processes`: the pod/host name, the JVM's start, the build identity
 * (the image's `TOADIE_BUILD_VERSION`/`TOADIE_BUILD_COMMIT`, null when unset or empty) and the sizing facts the memory percentage
 * reads. NEVER JVM arguments, environment variables or system properties.
 */
data class ProcessIdentity(
    val instance: String,
    val startedAt: Long,
    val buildVersion: String?,
    val buildCommit: String?,
    val processors: Int,
    val heapMaxBytes: Long,
) {
    companion object {
        /** The running process. [env] is injectable (`System::getenv`), so a test can pin the build fields. */
        fun detect(env: (String) -> String? = System::getenv): ProcessIdentity = ProcessIdentity(
            instance = instanceName(env),
            startedAt = ManagementFactory.getRuntimeMXBean().startTime,
            buildVersion = buildField(env("TOADIE_BUILD_VERSION")),
            buildCommit = buildField(env("TOADIE_BUILD_COMMIT")),
            processors = Runtime.getRuntime().availableProcessors(),
            heapMaxBytes = Runtime.getRuntime().maxMemory(),
        )

        /** `HOSTNAME` (the pod name under Kubernetes, the container id under compose), else the resolver's host name. */
        internal fun instanceName(env: (String) -> String?): String {
            val host = env("HOSTNAME")?.takeIf { it.isNotBlank() }
                ?: catchingFailures({ InetAddress.getLocalHost().hostName }) { null }
            return host?.trim()?.filter { !it.isISOControl() }?.take(MAX_INSTANCE_LENGTH)?.ifBlank { null } ?: "unknown"
        }

        private fun buildField(raw: String?): String? =
            raw?.trim()?.filter { !it.isISOControl() }?.take(MAX_BUILD_FIELD_LENGTH)?.ifBlank { null }
    }
}
