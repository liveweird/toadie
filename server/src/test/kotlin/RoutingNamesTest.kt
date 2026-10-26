package ch.nokillswit

import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.application.plugin
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The enforcement of `TelemetryRegistry.admitted`'s `?` rule (`plugins/Monitoring.kt`, `.claude/docs/observability.md` "Telemetry"):
 * the registry never registers a `ktor.calls.` name containing `?`, because Ktor names an UNROUTED call's instruments after the raw
 * request URI. A ROUTED call is named after its `RoutingNode.toString()`, so a route whose own name contains `?` would silently
 * lose its request metrics. `ktor-server-resources` prints an optional query field of a `@Resource` class as `[name?]` and
 * `{param?}` / `optionalParam` print a `?` too; no Toadie route has one, and this test fails loudly if one is added.
 */
class RoutingNamesTest {
    private fun RoutingNode.walk(): Sequence<RoutingNode> = sequenceOf(this) + children.asSequence().flatMap { it.walk() }

    @Test
    fun `no routing node's name contains a question mark`() = testApplication {
        usePostgresTestcontainer()
        val nodes = application.plugin(RoutingRoot).walk().toList()
        assertTrue(nodes.size > 100, "the walk must see the real routing tree, saw ${nodes.size} nodes")
        val offenders = nodes.map { it.toString() }.filter { '?' in it }
        assertTrue(
            offenders.isEmpty(),
            "Route(s) ${offenders.take(5)} have a '?' in their name (an optional query field of a @Resource, or an optional path " +
                "parameter). TelemetryRegistry.admitted (plugins/Monitoring.kt) refuses every ktor.calls. name containing '?', so " +
                "this route's request metrics would be silently dropped: revisit that rule BEFORE adding an optional query field " +
                "to a @Resource.",
        )
    }
}
