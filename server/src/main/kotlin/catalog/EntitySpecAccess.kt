package ch.nokillswit.catalog

/**
 * `EntitySpec` from field-name keyed maps — the ONE place that knows how Backstage's spec fields
 * are spelled on the Kotlin model. Every name here is a Backstage descriptor field (never an
 * ontology name). It lives beside the catalog's own field table (`SPEC_FIELDS` in
 * `CatalogFileValidation.kt`) because it is the same knowledge, and `CodecsTest` fails when the two drift:
 * it walks `SPEC_SHAPES` and `EntitySpec`'s serial descriptor to prove this writer and
 * [specStrings]/[specLists] cover every field the table declares and the model has.
 */
internal fun specOf(strings: Map<String, String>, lists: Map<String, List<String>>, profile: EntityProfile?): EntitySpec = EntitySpec(
    type = strings[SPEC_TYPE],
    lifecycle = strings[SPEC_LIFECYCLE],
    owner = strings["owner"],
    system = strings["system"],
    subcomponentOf = strings["subcomponentOf"],
    providesApis = lists["providesApis"].orEmpty(),
    consumesApis = lists["consumesApis"].orEmpty(),
    dependsOn = lists["dependsOn"].orEmpty(),
    dependencyOf = lists["dependencyOf"].orEmpty(),
    definition = strings["definition"],
    profile = profile,
    parent = strings["parent"],
    children = lists["children"],
    members = lists["members"].orEmpty(),
    memberOf = lists["memberOf"],
    domain = strings["domain"],
    subdomainOf = strings["subdomainOf"],
)

/** The inverse of [specOf] for text and single-reference fields: only the fields the spec actually carries. */
internal fun specStrings(spec: EntitySpec): Map<String, String> = listOfNotNull(
    spec.type?.let { SPEC_TYPE to it },
    spec.lifecycle?.let { SPEC_LIFECYCLE to it },
    spec.owner?.let { "owner" to it },
    spec.system?.let { "system" to it },
    spec.subcomponentOf?.let { "subcomponentOf" to it },
    spec.definition?.let { "definition" to it },
    spec.parent?.let { "parent" to it },
    spec.domain?.let { "domain" to it },
    spec.subdomainOf?.let { "subdomainOf" to it },
).toMap()

/** The inverse of [specOf] for reference lists: a list the spec models as absent (`null`) is not in the map; an empty non-null list is. */
internal fun specLists(spec: EntitySpec): Map<String, List<String>> = listOfNotNull(
    "providesApis" to spec.providesApis,
    "consumesApis" to spec.consumesApis,
    "dependsOn" to spec.dependsOn,
    "dependencyOf" to spec.dependencyOf,
    spec.children?.let { "children" to it },
    "members" to spec.members,
    spec.memberOf?.let { "memberOf" to it },
).toMap()
