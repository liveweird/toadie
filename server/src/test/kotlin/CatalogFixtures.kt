package ch.nokillswit

import ch.nokillswit.catalog.CatalogFile
import ch.nokillswit.catalog.CatalogFileMetadata
import ch.nokillswit.catalog.CatalogFileResponse
import ch.nokillswit.catalog.EntitySpec
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import java.util.UUID

// The shared catalog test fixtures — grammar-valid, collision-free by unique suffix, consumed
// by every catalog-adjacent suite (files/cross-check/graph/round-trip/audit).

/** Unique, grammar-valid entity name so parallel tests and re-runs never collide on identity. */
fun uniqueEntityName(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

/**
 * A unique throwaway namespace, registered as an active namespaces-dictionary entry first —
 * catalog writes reject undefined namespaces, so a test minting a namespace to create files
 * in must go through this (or [TestNamespaces.ensure] for fixed values).
 */
suspend fun uniqueNamespace(prefix: String): String =
    uniqueEntityName(prefix).also { TestNamespaces.ensure(it) }

/**
 * A unique throwaway label key, registered in the label registry first — catalog writes
 * reject unregistered labels, so a test applying labels to files must go through this.
 */
suspend fun uniqueLabel(
    prefix: String,
    values: List<String> = listOf("v1", "v2"),
    kinds: List<String> = listOf("Component"),
): String = uniqueEntityName(prefix).also { TestLabels.ensure(it, values, kinds) }

fun componentFile(
    name: String,
    namespace: String = "default",
    title: String? = null,
    type: String = "service",
    lifecycle: String = "production",
    owner: String = "group:default/platform",
) = CatalogFile(
    metadata = CatalogFileMetadata(name = name, namespace = namespace, title = title),
    spec = EntitySpec(type = type, lifecycle = lifecycle, owner = owner),
)

fun groupFile(
    name: String,
    namespace: String = "default",
    children: List<String> = emptyList(),
    members: List<String> = emptyList(),
    parent: String? = null,
) = CatalogFile(
    kind = "Group",
    metadata = CatalogFileMetadata(name = name, namespace = namespace),
    spec = EntitySpec(type = "team", children = children, members = members, parent = parent),
)

fun userFile(name: String, namespace: String = "default", memberOf: List<String> = emptyList()) = CatalogFile(
    kind = "User",
    metadata = CatalogFileMetadata(name = name, namespace = namespace),
    spec = EntitySpec(memberOf = memberOf),
)

fun apiFile(name: String, namespace: String = "default", owner: String = "group:default/platform") = CatalogFile(
    kind = "API",
    metadata = CatalogFileMetadata(name = name, namespace = namespace),
    spec = EntitySpec(type = "openapi", lifecycle = "production", owner = owner, definition = "openapi: 3.0.0"),
)

suspend fun HttpClient.createCatalogFile(file: CatalogFile): CatalogFileResponse =
    postJson("/api/v1/catalog-files", file).body()
