package ch.nokillswit

import ch.nokillswit.catalog.CatalogFile
import ch.nokillswit.catalog.CatalogFileMetadata
import ch.nokillswit.catalog.CatalogFilePageResponse
import ch.nokillswit.catalog.CatalogFileResponse
import ch.nokillswit.catalog.CatalogLink
import ch.nokillswit.catalog.EntitySpec
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogFileTest {


    @Test
    fun `a regular user can create, read, list, update and delete a catalog file`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")
        val name = uniqueEntityName("svc")

        val created = client.postJson(CATALOG_FILES_PATH, componentFile(name, title = "My Service"))
        assertEquals(HttpStatusCode.Created, created.status)
        val body = created.body<CatalogFileResponse>()
        assertEquals(name, body.metadata.name)
        assertEquals("default", body.metadata.namespace)
        assertEquals("My Service", body.metadata.title)
        assertEquals("service", body.spec.type)
        assertEquals("group:default/platform", body.spec.owner)
        assertFalse(body.creatorDeleted)
        assertTrue(body.createdAt > 0)
        assertEquals(body.createdAt, body.updatedAt)
        assertEquals("$CATALOG_FILES_PATH/${body.id}", created.headers[HttpHeaders.Location])

        val fetched = client.get("$CATALOG_FILES_PATH/${body.id}")
        assertEquals(HttpStatusCode.OK, fetched.status)
        assertEquals(name, fetched.body<CatalogFileResponse>().metadata.name)

        val listed = client.get("$CATALOG_FILES_PATH?name=$name").body<CatalogFilePageResponse>()
        assertEquals(1L, listed.total)
        val row = listed.items.single()
        assertEquals(body.id, row.id)
        assertEquals("My Service", row.title)
        assertEquals("production", row.lifecycle)
        assertEquals(body.creatorName, row.creatorName)

        val updated = client.putJson("$CATALOG_FILES_PATH/${body.id}", componentFile(name, title = "Renamed", lifecycle = "deprecated"))
        assertEquals(HttpStatusCode.NoContent, updated.status)
        val reFetched = client.get("$CATALOG_FILES_PATH/${body.id}").body<CatalogFileResponse>()
        assertEquals("Renamed", reFetched.metadata.title)
        assertEquals("deprecated", reFetched.spec.lifecycle)
        assertTrue(reFetched.updatedAt >= reFetched.createdAt)

        assertEquals(HttpStatusCode.NoContent, client.delete("$CATALOG_FILES_PATH/${body.id}").status)
        assertEquals(HttpStatusCode.NotFound, client.get("$CATALOG_FILES_PATH/${body.id}").status)
    }

    @Test
    fun `every supported kind creates and round-trips`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")
        val ns = uniqueNamespace("kinds")
        // Reference targets first — writes enforce resolution (all names live in this test's
        // unique namespace, so fixed values never collide across tests or runs).
        client.createCatalogFile(groupFile("team-a", namespace = ns))
        client.createCatalogFile(userFile("someone", namespace = ns))
        for (domainName in listOf("commerce", "payments")) {
            client.createCatalogFile(
                CatalogFile(
                    kind = "Domain",
                    metadata = CatalogFileMetadata(name = domainName, namespace = ns),
                    spec = EntitySpec(owner = "team-a"),
                ),
            )
        }
        client.createCatalogFile(
            CatalogFile(
                kind = "Resource",
                metadata = CatalogFileMetadata(name = "other-db", namespace = ns),
                spec = EntitySpec(type = "database", owner = "team-a"),
            ),
        )
        val files = listOf(
            componentFile(uniqueEntityName("comp"), namespace = ns),
            apiFile(uniqueEntityName("api"), namespace = ns),
            CatalogFile(
                kind = "System",
                metadata = CatalogFileMetadata(name = uniqueEntityName("sys"), namespace = ns),
                spec = EntitySpec(owner = "team-a", domain = "payments", type = "product"),
            ),
            CatalogFile(
                kind = "Domain",
                metadata = CatalogFileMetadata(name = uniqueEntityName("dom"), namespace = ns),
                spec = EntitySpec(owner = "team-a", subdomainOf = "commerce"),
            ),
            CatalogFile(
                kind = "Resource",
                metadata = CatalogFileMetadata(name = uniqueEntityName("res"), namespace = ns),
                spec = EntitySpec(type = "database", owner = "team-a", dependsOn = listOf("resource:other-db")),
            ),
            groupFile(uniqueEntityName("grp"), namespace = ns, members = listOf("user:$ns/someone")),
            userFile(uniqueEntityName("usr"), namespace = ns, memberOf = listOf("team-a")),
        )
        for (file in files) {
            val created = client.createCatalogFile(file)
            assertEquals(file.kind, created.kind)
            val fetched = client.get("$CATALOG_FILES_PATH/${created.id}").body<CatalogFileResponse>()
            assertEquals(file.spec, fetched.spec, "spec must round-trip for kind ${file.kind}")
            assertEquals(file.kind, fetched.kind)
        }
        // Group children and User memberOf survive as PRESENT-and-empty, never null.
        val group = files.first { it.kind == "Group" }
        val fetchedGroup = client.get("$CATALOG_FILES_PATH?namespace=$ns&kind=group&name=${group.metadata.name}")
            .body<CatalogFilePageResponse>()
        assertEquals(listOf(group.metadata.name), fetchedGroup.items.map { it.name })
    }

    @Test
    fun `per-kind rules reject missing required and foreign fields`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")

        suspend fun status(file: CatalogFile): HttpStatusCode = client.postJson(CATALOG_FILES_PATH, file).status

        val n = { uniqueEntityName("bad") }
        // Unknown kind.
        assertEquals(HttpStatusCode.BadRequest, status(componentFile(n()).copy(kind = "Gadget")))
        // API without its definition.
        assertEquals(HttpStatusCode.BadRequest, status(apiFile(n()).let { f -> f.copy(spec = f.spec.copy(definition = null)) }))
        // Group without a children list (empty is fine, absent is not).
        assertEquals(HttpStatusCode.BadRequest, status(groupFile(n()).let { f -> f.copy(spec = f.spec.copy(children = null)) }))
        // User without a memberOf list.
        assertEquals(HttpStatusCode.BadRequest, status(userFile(n()).let { f -> f.copy(spec = f.spec.copy(memberOf = null)) }))
        // A field foreign to the kind: a Component with a definition, a User with an owner.
        assertEquals(
            HttpStatusCode.BadRequest,
            status(componentFile(n()).let { f -> f.copy(spec = f.spec.copy(definition = "nope")) }),
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            status(userFile(n()).let { f -> f.copy(spec = f.spec.copy(owner = "team-a")) }),
        )
        // Profile rules: picture must be an absolute URI.
        assertEquals(
            HttpStatusCode.BadRequest,
            status(
                userFile(n()).let { f ->
                    f.copy(spec = f.spec.copy(profile = ch.nokillswit.catalog.EntityProfile(picture = "/relative.png")))
                },
            ),
        )
        // A case-variant kind is canonicalized, not rejected.
        val created = client.postJson(CATALOG_FILES_PATH, groupFile(uniqueEntityName("cased")).copy(kind = "gRoUp"))
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("Group", created.body<CatalogFileResponse>().kind)
    }

    @Test
    fun `kind can be changed by an update, and identity collisions still 409`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")
        val ns = uniqueNamespace("flip")
        val name = uniqueEntityName("chameleon")
        val id = client.createCatalogFile(componentFile(name, namespace = ns)).id

        // Component → API (a full replace with a new kind).
        val flipped = client.putJson("$CATALOG_FILES_PATH/$id", apiFile(name, namespace = ns))
        assertEquals(HttpStatusCode.NoContent, flipped.status)
        assertEquals("API", client.get("$CATALOG_FILES_PATH/$id").body<CatalogFileResponse>().kind)

        // Same name, kind Group — a DIFFERENT identity, so it coexists…
        val groupId = client.createCatalogFile(groupFile(name, namespace = ns)).id
        // …but flipping the group to kind API collides with the flipped file's identity.
        val collision = client.putJson("$CATALOG_FILES_PATH/$groupId", apiFile(name, namespace = ns))
        assertEquals(HttpStatusCode.Conflict, collision.status)
    }

    @Test
    fun `the list filters by kind and rejects unknown kinds`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")
        val ns = uniqueNamespace("kfilter")
        client.createCatalogFile(componentFile(uniqueEntityName("c"), namespace = ns))
        client.createCatalogFile(groupFile(uniqueEntityName("g"), namespace = ns))

        val groups = client.get("$CATALOG_FILES_PATH?namespace=$ns&kind=GROUP").body<CatalogFilePageResponse>()
        assertEquals(listOf("Group"), groups.items.map { it.kind })
        val all = client.get("$CATALOG_FILES_PATH?namespace=$ns&sort=kind").body<CatalogFilePageResponse>()
        assertEquals(listOf("Component", "Group"), all.items.map { it.kind })
        // Repetition is the any-of/IN idiom on kind; an unknown value 400s wherever it sits.
        val union = client.get("$CATALOG_FILES_PATH?namespace=$ns&kind=Group&kind=Component&sort=kind")
            .body<CatalogFilePageResponse>()
        assertEquals(listOf("Component", "Group"), union.items.map { it.kind })
        assertEquals(HttpStatusCode.BadRequest, client.get("$CATALOG_FILES_PATH?kind=Gadget").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$CATALOG_FILES_PATH?kind=Group&kind=Gadget").status)
    }

    @Test
    fun `the list filters by exact tag and items carry their tags`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")
        val prefix = uniqueEntityName("tagf")
        val ns = uniqueNamespace("tagfns")
        val tagA = uniqueTag("tf-a")
        val tagB = uniqueTag("tf-b")
        uniqueTagCategory("tagfcat", tags = listOf(tagA, tagB), kinds = listOf("Component"))
        suspend fun tagged(name: String, tags: List<String>) = client.createCatalogFile(
            componentFile(name, namespace = ns).let { it.copy(metadata = it.metadata.copy(tags = tags)) },
        )
        tagged("$prefix-a", listOf(tagA))
        tagged("$prefix-b", listOf(tagA, tagB))
        client.createCatalogFile(componentFile("$prefix-c", namespace = ns))

        // Exact membership: only the file carrying tagB; the item ships its full tag list.
        val byTagB = client.get("$CATALOG_FILES_PATH?namespace=$ns&tag=$tagB&sort=name")
            .body<CatalogFilePageResponse>()
        assertEquals(listOf("$prefix-b"), byTagB.items.map { it.name })
        assertEquals(listOf(tagA, tagB), byTagB.items.single().tags)

        // Case-folded (tags are stored lowercase); a shared tag matches both carriers.
        val byTagA = client.get("$CATALOG_FILES_PATH?namespace=$ns&tag=${tagA.uppercase()}&sort=name")
            .body<CatalogFilePageResponse>()
        assertEquals(listOf("$prefix-a", "$prefix-b"), byTagA.items.map { it.name })

        // An unregistered-nowhere tag matches nothing; blank is ignored (all three rows).
        val none = client.get("$CATALOG_FILES_PATH?namespace=$ns&tag=${uniqueTag("tf-none")}")
            .body<CatalogFilePageResponse>()
        assertEquals(0, none.items.size)
        val blank = client.get("$CATALOG_FILES_PATH?namespace=$ns&tag=").body<CatalogFilePageResponse>()
        assertEquals(3, blank.items.size)
    }

    @Test
    fun `the list filters by type and lifecycle, case-folded, absent fields never matching`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")
        val ns = uniqueNamespace("tlfilter")
        val svc = uniqueEntityName("svc")
        val lib = uniqueEntityName("lib")
        val grp = uniqueEntityName("grp")
        client.createCatalogFile(componentFile(svc, namespace = ns, type = "service", lifecycle = "production"))
        client.createCatalogFile(componentFile(lib, namespace = ns, type = "library", lifecycle = "experimental"))
        client.createCatalogFile(groupFile(grp, namespace = ns)) // type "team", NO lifecycle

        suspend fun names(query: String): List<String> =
            client.get("$CATALOG_FILES_PATH?namespace=$ns&sort=name&$query")
                .body<CatalogFilePageResponse>().items.map { it.name }

        assertEquals(listOf(svc), names("type=SERVICE"))
        assertEquals(listOf(grp), names("type=team"))
        assertEquals(listOf(lib), names("lifecycle=EXPERIMENTAL"))
        // No file carries this lifecycle — the group's ABSENT lifecycle matches nothing.
        assertEquals(emptyList(), names("lifecycle=deprecated"))
        // Blank filter values mean "no filter".
        assertEquals(3, names("type=&lifecycle=").size)
    }

    @Test
    fun `the owner filter resolves every stored spelling to the picked entity`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")
        val ns = uniqueNamespace("ownfa")
        val otherNs = uniqueNamespace("ownfb")
        client.createCatalogFile(groupFile("owners-team", namespace = ns))
        client.createCatalogFile(groupFile("owners-team", namespace = otherNs))
        client.createCatalogFile(userFile("someone", namespace = ns))
        val p = uniqueEntityName("ow")
        // The four spellings that all RESOLVE to group:$ns/owners-team…
        client.createCatalogFile(componentFile("$p-bare", namespace = ns, owner = "owners-team"))
        client.createCatalogFile(componentFile("$p-nsname", namespace = ns, owner = "$ns/owners-team"))
        client.createCatalogFile(componentFile("$p-kindname", namespace = ns, owner = "group:owners-team"))
        client.createCatalogFile(componentFile("$p-full", namespace = ns, owner = "group:$ns/owners-team"))
        // …the SAME bare spelling in another namespace resolves to a DIFFERENT entity…
        client.createCatalogFile(componentFile("$p-elsewhere", namespace = otherNs, owner = "owners-team"))
        // …and a user owner is its own identity.
        client.createCatalogFile(componentFile("$p-usr", namespace = ns, owner = "user:someone"))
        client.createCatalogFile(componentFile("$p-usrfull", namespace = ns, owner = "user:$ns/someone"))

        suspend fun names(query: String): List<String> =
            client.get("$CATALOG_FILES_PATH?sort=name&$query").body<CatalogFilePageResponse>().items.map { it.name }

        val spellings = listOf("$p-bare", "$p-full", "$p-kindname", "$p-nsname")
        assertEquals(spellings, names("owner=group:$ns/owners-team"))
        // Case-insensitive, and the param itself may use a short form (kind defaults to group).
        assertEquals(spellings, names("owner=GROUP:${ns.uppercase()}/OWNERS-TEAM"))
        assertEquals(listOf("$p-elsewhere"), names("owner=$otherNs/owners-team"))
        assertEquals(listOf("$p-usr", "$p-usrfull"), names("owner=user:$ns/someone"))
        // Unparsable reference → 400.
        assertEquals(HttpStatusCode.BadRequest, client.get("$CATALOG_FILES_PATH?owner=a:b:c").status)
    }

    @Test
    fun `the label filters check key presence and any-of values`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")
        val ns = uniqueNamespace("lblf")
        val key = uniqueLabel("lblf", values = listOf("v1", "v2"))
        val p = uniqueEntityName("lf")
        suspend fun labeled(name: String, value: String?) = client.createCatalogFile(
            componentFile(name, namespace = ns).let {
                it.copy(metadata = it.metadata.copy(labels = value?.let { v -> mapOf(key to v) } ?: emptyMap()))
            },
        )
        labeled("$p-v1", "v1")
        labeled("$p-v2", "v2")
        labeled("$p-none", null)

        suspend fun names(query: String): List<String> =
            client.get("$CATALOG_FILES_PATH?namespace=$ns&sort=name&$query")
                .body<CatalogFilePageResponse>().items.map { it.name }

        assertEquals(listOf("$p-v1", "$p-v2"), names("label=$key"))
        assertEquals(listOf("$p-v1"), names("label=$key&labelValue=v1"))
        // Repetition is the documented IN idiom on labelValue, case-folded.
        assertEquals(listOf("$p-v1", "$p-v2"), names("label=$key&labelValue=V1&labelValue=v2"))
        assertEquals(emptyList(), names("label=$key&labelValue=v3"))
        // labelValue without label → 400; a repeated label key stays the scalar-param 400.
        assertEquals(HttpStatusCode.BadRequest, client.get("$CATALOG_FILES_PATH?labelValue=v1").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$CATALOG_FILES_PATH?label=a&label=b").status)
    }

    @Test
    fun `the full metadata surface round-trips`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")
        val name = uniqueEntityName("full")
        TestNamespaces.ensure("team-a")
        TestLabels.ensure("example.com/tier", listOf("backend"), listOf("Component"))
        TestAnnotationKeys.ensure("github.com/project-slug", listOf("Component"))
        TestTagCategories.ensure("Languages", listOf("java", "c++"), listOf("Component"))
        // Every referenced target must be STORED (fixed names in the SHARED team-a/default
        // namespaces — ensured idempotently, a 409 from an earlier run is fine).
        suspend fun ensure(target: CatalogFile) {
            val status = client.postJson(CATALOG_FILES_PATH, target).status
            assertTrue(
                status == HttpStatusCode.Created || status == HttpStatusCode.Conflict,
                "ensuring ${target.metadata.name}: $status",
            )
        }
        ensure(groupFile("team-a", namespace = "team-a"))
        ensure(
            CatalogFile(
                kind = "System",
                metadata = CatalogFileMetadata(name = "payments", namespace = "team-a"),
                spec = EntitySpec(owner = "team-a"),
            ),
        )
        ensure(apiFile("loaded-api", namespace = "team-a", owner = "team-a"))
        ensure(apiFile("billing-api"))
        ensure(
            CatalogFile(
                kind = "Resource",
                metadata = CatalogFileMetadata(name = "loaded-db"),
                spec = EntitySpec(type = "database", owner = "group:default/platform"),
            ),
        )
        ensure(componentFile("parent-svc"))
        ensure(componentFile("consumer-svc", namespace = "team-a", owner = "team-a"))
        val file = CatalogFile(
            metadata = CatalogFileMetadata(
                name = name,
                namespace = "team-a",
                title = "Fully Loaded",
                description = "Everything the format allows.",
                labels = mapOf("example.com/tier" to "backend"),
                annotations = mapOf("github.com/project-slug" to "acme/loaded"),
                tags = listOf("java", "c++"),
                links = listOf(CatalogLink(url = "https://example.com/dash", title = "Dashboard", icon = "dashboard")),
            ),
            spec = EntitySpec(
                type = "service",
                lifecycle = "experimental",
                owner = "team-a",
                system = "system:payments",
                subcomponentOf = "component:default/parent-svc",
                providesApis = listOf("loaded-api"),
                consumesApis = listOf("api:default/billing-api"),
                dependsOn = listOf("resource:default/loaded-db"),
                dependencyOf = listOf("component:consumer-svc"),
            ),
        )
        val created = client.createCatalogFile(file)
        val fetched = jsonClientBody(client, created.id)
        assertEquals(file.metadata, fetched.metadata)
        assertEquals(file.spec, fetched.spec)
    }

    private suspend fun jsonClientBody(client: HttpClient, id: UInt): CatalogFileResponse =
        client.get("$CATALOG_FILES_PATH/$id").body()

    @Test
    fun `create and update reject payloads violating the descriptor format`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")

        suspend fun createStatus(file: CatalogFile): HttpStatusCode = client.postJson(CATALOG_FILES_PATH, file).status

        val ok = uniqueEntityName("valid")
        // metadata.name grammar
        assertEquals(HttpStatusCode.BadRequest, createStatus(componentFile("has space")))
        assertEquals(HttpStatusCode.BadRequest, createStatus(componentFile("-leading-dash")))
        assertEquals(HttpStatusCode.BadRequest, createStatus(componentFile("double..dot")))
        assertEquals(HttpStatusCode.BadRequest, createStatus(componentFile("x".repeat(64))))
        // metadata.namespace grammar (uppercase input is folded, so use a truly invalid one)
        assertEquals(HttpStatusCode.BadRequest, createStatus(componentFile(ok, namespace = "under_score")))
        // tags
        assertEquals(
            HttpStatusCode.BadRequest,
            createStatus(componentFile(ok).let { it.copy(metadata = it.metadata.copy(tags = listOf("Uppercase"))) }),
        )
        // label key and value
        assertEquals(
            HttpStatusCode.BadRequest,
            createStatus(
                componentFile(ok).let { it.copy(metadata = it.metadata.copy(labels = mapOf("a/b/c" to "x"))) },
            ),
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            createStatus(
                componentFile(ok).let { it.copy(metadata = it.metadata.copy(labels = mapOf("tier" to "has space"))) },
            ),
        )
        // server-written annotation key
        assertEquals(
            HttpStatusCode.BadRequest,
            createStatus(
                componentFile(ok).let {
                    it.copy(metadata = it.metadata.copy(annotations = mapOf("backstage.io/orphan" to "true")))
                },
            ),
        )
        // link url must be an absolute URI; icon follows the name grammar
        assertEquals(
            HttpStatusCode.BadRequest,
            createStatus(
                componentFile(ok).let {
                    it.copy(metadata = it.metadata.copy(links = listOf(CatalogLink(url = "/relative/path"))))
                },
            ),
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            createStatus(
                componentFile(ok).let {
                    it.copy(
                        metadata = it.metadata.copy(
                            links = listOf(CatalogLink(url = "https://x.example", icon = "bad icon")),
                        ),
                    )
                },
            ),
        )
        // spec basics
        assertEquals(HttpStatusCode.BadRequest, createStatus(componentFile(ok, type = "  ")))
        assertEquals(HttpStatusCode.BadRequest, createStatus(componentFile(ok, lifecycle = "two words")))
        // entity-reference grammar
        assertEquals(HttpStatusCode.BadRequest, createStatus(componentFile(ok, owner = "a:b:c")))
        assertEquals(HttpStatusCode.BadRequest, createStatus(componentFile(ok, owner = "group:ns/x/y")))
        assertEquals(HttpStatusCode.BadRequest, createStatus(componentFile(ok, owner = "group:default/")))
        assertEquals(
            HttpStatusCode.BadRequest,
            createStatus(componentFile(ok).let { it.copy(spec = it.spec.copy(dependsOn = listOf("bad ref"))) }),
        )

        // The same validation guards PUT.
        val existing = client.createCatalogFile(componentFile(uniqueEntityName("put")))
        val put = client.putJson("$CATALOG_FILES_PATH/${existing.id}", componentFile("has space"))
        assertEquals(HttpStatusCode.BadRequest, put.status)
    }

    @Test
    fun `entity identity is case-insensitively unique among active files`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")
        val name = uniqueEntityName("Uniq")

        val first = client.createCatalogFile(componentFile(name))

        // Case-variant duplicate in the same namespace → 409 via the partial unique index.
        val duplicate = client.postJson(CATALOG_FILES_PATH, componentFile(name.lowercase()))
        assertEquals(HttpStatusCode.Conflict, duplicate.status)

        // Same name in another namespace is a different identity.
        TestNamespaces.ensure("team-b")
        val otherNamespace = client.postJson(CATALOG_FILES_PATH, componentFile(name, namespace = "team-b"))
        assertEquals(HttpStatusCode.Created, otherNamespace.status)

        // Renaming onto an active identity conflicts too.
        val renamed = client.putJson("$CATALOG_FILES_PATH/${otherNamespace.body<CatalogFileResponse>().id}", componentFile(name))
        assertEquals(HttpStatusCode.Conflict, renamed.status)

        // Soft-deleting frees the identity for reuse.
        assertEquals(HttpStatusCode.NoContent, client.delete("$CATALOG_FILES_PATH/${first.id}").status)
        val reused = client.postJson(CATALOG_FILES_PATH, componentFile(name))
        assertEquals(HttpStatusCode.Created, reused.status)
    }

    @Test
    fun `namespace is folded to lowercase and blank means default`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")

        TestNamespaces.ensure("team-a") // the dictionary stores folded values — "TEAM-A" folds onto it
        val folded = client.createCatalogFile(componentFile(uniqueEntityName("fold"), namespace = "TEAM-A"))
        assertEquals("team-a", folded.metadata.namespace)

        val blank = client.createCatalogFile(componentFile(uniqueEntityName("blank"), namespace = "  "))
        assertEquals("default", blank.metadata.namespace)
    }

    @Test
    fun `a blank namespace resolves to the ADMIN-flagged default entry`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("nsdflt")
        val custom = uniqueEntityName("dfltns")
        TestNamespaces.withDefaultNamespace(custom) {
            val created = client.createCatalogFile(componentFile(uniqueEntityName("blankdoc"), namespace = ""))
            assertEquals(custom, created.metadata.namespace, "blank resolves to the flagged entry, not the literal")
        }
    }

    @Test
    fun `a blank namespace is 400 while no entry is flagged as default`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("nsnodflt")
        val snapshot = TestNamespaces.snapshotValues()
        try {
            TestNamespaces.replaceDocument(emptyList()) // the empty document has no default
            val response = client.postJson(
                CATALOG_FILES_PATH,
                componentFile(uniqueEntityName("nodflt"), namespace = ""),
            )
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.body<ProblemDetail>().detail!!.contains("No default namespace"))
        } finally {
            TestNamespaces.replaceDocument(snapshot)
        }
    }

    @Test
    fun `an undefined namespace is rejected on create and update - strict, no grandfathering`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("nsenforce")

        // Grammar-valid but not a dictionary entry → 400 on create.
        val undefined = uniqueEntityName("ghost")
        val create = client.postJson(
            CATALOG_FILES_PATH,
            componentFile(uniqueEntityName("nsc"), namespace = undefined),
        )
        assertEquals(HttpStatusCode.BadRequest, create.status)
        assertTrue(create.body<ProblemDetail>().detail!!.contains("not a defined namespace"))

        // A stored file whose namespace was since REMOVED from the dictionary blocks on save
        // (the deliberate strict rule) — re-adding the namespace unblocks it.
        val ns = uniqueNamespace("nsgone")
        val created = client.createCatalogFile(componentFile(uniqueEntityName("nsu"), namespace = ns))
        TestNamespaces.remove(ns)
        val update = client.putJson(
            "$CATALOG_FILES_PATH/${created.id}",
            componentFile(created.metadata.name, namespace = ns),
        )
        assertEquals(HttpStatusCode.BadRequest, update.status)
        TestNamespaces.ensure(ns)
        val unblocked = client.putJson(
            "$CATALOG_FILES_PATH/${created.id}",
            componentFile(created.metadata.name, namespace = ns),
        )
        assertEquals(HttpStatusCode.NoContent, unblocked.status)
    }

    @Test
    fun `labels are enforced against the ADMIN registry - key, kind, and value`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("lblenforce")

        fun labeled(name: String, labels: Map<String, String>) =
            componentFile(name).let { it.copy(metadata = it.metadata.copy(labels = labels)) }

        // Grammar-valid but unregistered key → 400.
        val ghost = uniqueEntityName("lblghost")
        val unknown = client.postJson(CATALOG_FILES_PATH, labeled(uniqueEntityName("lku"), mapOf(ghost to "x")))
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        assertTrue(unknown.body<ProblemDetail>().detail!!.contains("not a defined label"))

        // Registered, but for another kind → 400.
        val apiOnly = uniqueLabel("lblapi", values = listOf("backend"), kinds = listOf("API"))
        val wrongKind = client.postJson(
            CATALOG_FILES_PATH,
            labeled(uniqueEntityName("lkk"), mapOf(apiOnly to "backend")),
        )
        assertEquals(HttpStatusCode.BadRequest, wrongKind.status)
        assertTrue(wrongKind.body<ProblemDetail>().detail!!.contains("cannot be applied to kind"))

        // Registered for the kind, but a value outside the closed list → 400.
        val lbl = uniqueLabel("lblok", values = listOf("backend", "frontend"), kinds = listOf("Component"))
        val wrongValue = client.postJson(
            CATALOG_FILES_PATH,
            labeled(uniqueEntityName("lkv"), mapOf(lbl to "database")),
        )
        assertEquals(HttpStatusCode.BadRequest, wrongValue.status)
        assertTrue(wrongValue.body<ProblemDetail>().detail!!.contains("is not allowed for label"))

        // The allowed combination stores and round-trips.
        val created = client.createCatalogFile(labeled(uniqueEntityName("lok"), mapOf(lbl to "backend")))
        assertEquals("backend", created.metadata.labels[lbl])
    }

    @Test
    fun `a removed label blocks a resave - strict, no grandfathering - until re-registered`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("lblgone")
        val lbl = uniqueLabel("lblgone", values = listOf("backend"), kinds = listOf("Component"))
        val name = uniqueEntityName("lblg")
        val file = componentFile(name).let { it.copy(metadata = it.metadata.copy(labels = mapOf(lbl to "backend"))) }
        val created = client.createCatalogFile(file)

        TestLabels.remove(lbl)
        assertEquals(
            HttpStatusCode.BadRequest,
            client.putJson("$CATALOG_FILES_PATH/${created.id}", file).status,
            "a stored file whose label was removed from the registry must block on save",
        )
        TestLabels.ensure(lbl, listOf("backend"), listOf("Component"))
        assertEquals(HttpStatusCode.NoContent, client.putJson("$CATALOG_FILES_PATH/${created.id}", file).status)
    }

    @Test
    fun `tags are enforced against the ADMIN tag categories - registration and kind`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("tagenforce")

        fun tagged(name: String, tags: List<String>) =
            componentFile(name).let { it.copy(metadata = it.metadata.copy(tags = tags)) }

        // Grammar-valid but unregistered tag → 400.
        val ghost = uniqueTag("tagghost")
        val unknown = client.postJson(CATALOG_FILES_PATH, tagged(uniqueEntityName("tgu"), listOf(ghost)))
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        assertTrue(unknown.body<ProblemDetail>().detail!!.contains("is not a defined tag"))

        // Registered, but the category is for another kind → 400 naming tag AND category.
        val apiTag = uniqueTag("tagapi")
        val apiCategory = uniqueTagCategory("tagcatapi", tags = listOf(apiTag), kinds = listOf("API"))
        val wrongKind = client.postJson(CATALOG_FILES_PATH, tagged(uniqueEntityName("tgk"), listOf(apiTag)))
        assertEquals(HttpStatusCode.BadRequest, wrongKind.status)
        assertTrue(wrongKind.body<ProblemDetail>().detail!!.contains("category '$apiCategory'"))
        assertTrue(wrongKind.body<ProblemDetail>().detail!!.contains("cannot be applied to kind"))

        // Registered for the kind → stores and round-trips.
        val okTag = uniqueTag("tagok")
        uniqueTagCategory("tagcatok", tags = listOf(okTag), kinds = listOf("Component"))
        val created = client.createCatalogFile(tagged(uniqueEntityName("tgo"), listOf(okTag)))
        assertEquals(listOf(okTag), created.metadata.tags)
    }

    @Test
    fun `a removed tag category blocks a resave - strict, no grandfathering - until re-added`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("taggone")
        val t = uniqueTag("taggone")
        val category = uniqueTagCategory("tagcatgone", tags = listOf(t), kinds = listOf("Component"))
        val name = uniqueEntityName("tgg")
        val file = componentFile(name).let { it.copy(metadata = it.metadata.copy(tags = listOf(t))) }
        val created = client.createCatalogFile(file)

        TestTagCategories.remove(category)
        assertEquals(
            HttpStatusCode.BadRequest,
            client.putJson("$CATALOG_FILES_PATH/${created.id}", file).status,
            "a stored file whose tag category was removed must block on save",
        )
        TestTagCategories.ensure(category, listOf(t), listOf("Component"))
        assertEquals(HttpStatusCode.NoContent, client.putJson("$CATALOG_FILES_PATH/${created.id}", file).status)
    }

    @Test
    fun `references are enforced on save - missing, wrong kind, and kind-less all 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("refenforce")
        val ns = uniqueNamespace("refns")

        // MISSING: a grammar-valid owner nobody stores.
        val ghostOwner = client.postJson(
            CATALOG_FILES_PATH,
            componentFile(uniqueEntityName("rm"), namespace = ns, owner = uniqueEntityName("ghost-team")),
        )
        assertEquals(HttpStatusCode.BadRequest, ghostOwner.status)
        assertTrue(ghostOwner.body<ProblemDetail>().detail!!.contains("does not resolve to a stored entity"))

        // WRONG_KIND: a stored Component named explicitly in owner (Group/User only).
        val comp = uniqueEntityName("rc")
        client.createCatalogFile(componentFile(comp, namespace = ns))
        val wrongKind = client.postJson(
            CATALOG_FILES_PATH,
            componentFile(uniqueEntityName("rw"), namespace = ns, owner = "component:$ns/$comp"),
        )
        assertEquals(HttpStatusCode.BadRequest, wrongKind.status)
        assertTrue(wrongKind.body<ProblemDetail>().detail!!.contains("must target Group or User"))

        // KIND_REQUIRED: a kind-less dependsOn entry, even when a component of that name exists.
        val kindless = client.postJson(
            CATALOG_FILES_PATH,
            componentFile(uniqueEntityName("rk"), namespace = ns).let {
                it.copy(spec = it.spec.copy(dependsOn = listOf(comp)))
            },
        )
        assertEquals(HttpStatusCode.BadRequest, kindless.status)
        assertTrue(kindless.body<ProblemDetail>().detail!!.contains("needs an explicit kind"))

        // Violations AGGREGATE into one detail.
        val both = client.postJson(
            CATALOG_FILES_PATH,
            componentFile(uniqueEntityName("ra"), namespace = ns, owner = uniqueEntityName("nope")).let {
                it.copy(spec = it.spec.copy(dependsOn = listOf(comp)))
            },
        )
        assertEquals(HttpStatusCode.BadRequest, both.status)
        val detail = both.body<ProblemDetail>().detail!!
        assertTrue(detail.contains("spec.owner") && detail.contains("spec.dependsOn"))

        // A User resolves owner too (Group OR User are the allowed kinds).
        val person = uniqueEntityName("person")
        client.createCatalogFile(userFile(person, namespace = ns))
        val userOwned = client.postJson(
            CATALOG_FILES_PATH,
            componentFile(uniqueEntityName("ru"), namespace = ns, owner = "user:$ns/$person"),
        )
        assertEquals(HttpStatusCode.Created, userOwned.status)

        // SELF_REFERENCE on create: the self message, not MISSING (the identity isn't stored yet).
        val selfName = uniqueEntityName("rs")
        val selfCreate = client.postJson(
            CATALOG_FILES_PATH,
            componentFile(selfName, namespace = ns).let {
                it.copy(spec = it.spec.copy(subcomponentOf = "component:$ns/$selfName"))
            },
        )
        assertEquals(HttpStatusCode.BadRequest, selfCreate.status)
        assertTrue(selfCreate.body<ProblemDetail>().detail!!.contains("must not point at the entity itself"))
    }

    @Test
    fun `a deleted reference target blocks the referrer's resave until recreated`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("refgone")
        val ns = uniqueNamespace("refgns")
        val team = uniqueEntityName("team")
        val teamId = client.createCatalogFile(groupFile(team, namespace = ns)).id
        val file = componentFile(uniqueEntityName("rg"), namespace = ns, owner = team)
        val created = client.createCatalogFile(file)

        // Deletion is ALLOWED (the ref goes dangling); the referrer blocks on its next save.
        assertEquals(HttpStatusCode.NoContent, client.delete("$CATALOG_FILES_PATH/$teamId").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            client.putJson("$CATALOG_FILES_PATH/${created.id}", file).status,
            "a stored file whose reference target was deleted must block on save",
        )
        client.createCatalogFile(groupFile(team, namespace = ns))
        assertEquals(HttpStatusCode.NoContent, client.putJson("$CATALOG_FILES_PATH/${created.id}", file).status)
    }

    @Test
    fun `an update may not reference the file's own identity`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("refself")
        val ns = uniqueNamespace("refsns")
        val name = uniqueEntityName("selfish")
        val created = client.createCatalogFile(componentFile(name, namespace = ns))
        // The identity IS active (its own row), but self-references are forbidden outright —
        // full and short forms alike (the short form resolves within the file's namespace).
        for (ref in listOf("component:$ns/$name", name)) {
            val selfRef = componentFile(name, namespace = ns).let {
                it.copy(spec = it.spec.copy(subcomponentOf = ref))
            }
            val response = client.putJson("$CATALOG_FILES_PATH/${created.id}", selfRef)
            assertEquals(HttpStatusCode.BadRequest, response.status, "self-reference '$ref' must 400")
            assertTrue(response.body<ProblemDetail>().detail!!.contains("must not point at the entity itself"))
        }
    }

    @Test
    fun `list filters by name and namespace, sorts and paginates`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")
        val prefix = uniqueEntityName("page")
        TestNamespaces.ensure("ns-one", "ns-two")
        client.createCatalogFile(componentFile("$prefix-a", namespace = "ns-one"))
        client.createCatalogFile(componentFile("$prefix-b", namespace = "ns-two"))
        client.createCatalogFile(componentFile("$prefix-c", namespace = "ns-one"))

        val all = client.get("$CATALOG_FILES_PATH?name=$prefix&sort=name").body<CatalogFilePageResponse>()
        assertEquals(3L, all.total)
        assertEquals(listOf("$prefix-a", "$prefix-b", "$prefix-c"), all.items.map { it.name })

        // The namespace filter is exact and case-insensitive (stored lowercase, query folded).
        val nsOne = client.get("$CATALOG_FILES_PATH?name=$prefix&namespace=NS-ONE&sort=name")
            .body<CatalogFilePageResponse>()
        assertEquals(listOf("$prefix-a", "$prefix-c"), nsOne.items.map { it.name })

        val desc = client.get("$CATALOG_FILES_PATH?name=$prefix&sort=-name&page=1&pageSize=2")
            .body<CatalogFilePageResponse>()
        assertEquals(3L, desc.total)
        assertEquals(1, desc.page)
        assertEquals(2, desc.pageSize)
        assertEquals(listOf("$prefix-c", "$prefix-b"), desc.items.map { it.name })
        val page2 = client.get("$CATALOG_FILES_PATH?name=$prefix&sort=-name&page=2&pageSize=2")
            .body<CatalogFilePageResponse>()
        assertEquals(listOf("$prefix-a"), page2.items.map { it.name })
    }

    @Test
    fun `list name filter folds diacritics on the query side`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")
        // Stored names are grammar-constrained ASCII — the folding is proven from the query
        // side: a diacritic search term must match its ASCII base form.
        val name = "zolw-${uniqueEntityName("dia")}"
        client.createCatalogFile(componentFile(name))

        val page = client.get(CATALOG_FILES_PATH) {
            url.parameters.append("name", name.replace("zolw", "żółw"))
        }.body<CatalogFilePageResponse>()
        assertEquals(1L, page.total)
        assertEquals(name, page.items.single().name)
    }

    @Test
    fun `list rejects malformed query parameters`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")

        assertEquals(HttpStatusCode.BadRequest, client.get("$CATALOG_FILES_PATH?page=0").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$CATALOG_FILES_PATH?page=abc").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$CATALOG_FILES_PATH?pageSize=0").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$CATALOG_FILES_PATH?pageSize=101").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$CATALOG_FILES_PATH?pageSize=abc").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$CATALOG_FILES_PATH?sort=owner").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$CATALOG_FILES_PATH?sort=,name").status)
    }

    @Test
    fun `soft-deleted file is invisible to read, update, delete and list`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")
        val name = uniqueEntityName("gone")
        val created = client.createCatalogFile(componentFile(name))

        assertEquals(HttpStatusCode.NoContent, client.delete("$CATALOG_FILES_PATH/${created.id}").status)

        assertEquals(HttpStatusCode.NotFound, client.get("$CATALOG_FILES_PATH/${created.id}").status)
        val put = client.putJson("$CATALOG_FILES_PATH/${created.id}", componentFile(name))
        assertEquals(HttpStatusCode.NotFound, put.status)
        // Idempotent: a second delete finds no active row → 404.
        assertEquals(HttpStatusCode.NotFound, client.delete("$CATALOG_FILES_PATH/${created.id}").status)
        val page = client.get("$CATALOG_FILES_PATH?name=$name").body<CatalogFilePageResponse>()
        assertEquals(0L, page.total)
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `update and delete of a non-existent file return 404`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")
        val put = client.putJson("$CATALOG_FILES_PATH/999999", componentFile(uniqueEntityName("ghost")))
        assertEquals(HttpStatusCode.NotFound, put.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("$CATALOG_FILES_PATH/999999").status)
    }

    @Test
    fun `catalog file endpoints require authentication`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        val endpoints = listOf(
            HttpMethod.Get to CATALOG_FILES_PATH,
            HttpMethod.Post to CATALOG_FILES_PATH,
            HttpMethod.Get to "$CATALOG_FILES_PATH/1",
            HttpMethod.Put to "$CATALOG_FILES_PATH/1",
            HttpMethod.Delete to "$CATALOG_FILES_PATH/1",
        )
        for ((verb, path) in endpoints) {
            val response: HttpResponse = client.request(path) { method = verb }
            assertEquals(HttpStatusCode.Unauthorized, response.status, "$verb $path expected 401")
        }
    }

    @Test
    fun `creator enrichment survives the creator's soft-deletion`() = testApplication {
        usePostgresTestcontainer()
        val creatorEmail = uniqueEmail("creator")
        val creatorId = TestUsers.seed(email = creatorEmail, password = "pw", name = "Casey Creator", role = UserRole.USER)
        val creatorClient = authedClient(creatorEmail, "pw")
        val name = uniqueEntityName("orphaned")
        val created = creatorClient.createCatalogFile(componentFile(name))
        assertEquals(creatorId, created.createdBy)
        assertEquals("Casey Creator", created.creatorName)

        TestUsers.softDelete(creatorId)

        val reader = seededClient("reader")
        val fetched = reader.get("$CATALOG_FILES_PATH/${created.id}").body<CatalogFileResponse>()
        assertEquals("Casey Creator", fetched.creatorName)
        assertTrue(fetched.creatorDeleted)
        val row = reader.get("$CATALOG_FILES_PATH?name=$name").body<CatalogFilePageResponse>().items.single()
        assertTrue(row.creatorDeleted)
    }

    @Test
    fun `service treats a blank name filter as absent`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")
        val name = uniqueEntityName("blankfilter")
        val created = client.createCatalogFile(componentFile(name))

        // The name contains no space, so a literally-applied blank filter (LIKE '% %') would
        // exclude it — its presence proves blank means "no filter" at the service layer too.
        val page = TestCatalogFiles.service.list(
            ch.nokillswit.catalog.CatalogFileListFilter(name = " ", namespace = null),
            ch.nokillswit.infra.paging.PageRequest(
                page = 1,
                pageSize = 100,
                sort = listOf(ch.nokillswit.infra.paging.SortField("id", descending = true)),
            ),
        )
        assertTrue(page.items.any { it.id == created.id })
        assertNull(page.items.first { it.id == created.id }.title)
    }

    @Test
    fun `spec type is enforced against the kind's dictionary - strict, on create and update`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("typenf")

        // An unregistered type is a 400 naming the rule (the seeded Component list is the gate).
        val bad = client.postJson(
            CATALOG_FILES_PATH,
            componentFile(uniqueEntityName("typenf"), type = "never-registered-xyz"),
        )
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertTrue(bad.body<ProblemDetail>().detail!!.contains("not an allowed type"))

        // A type appended to the kind's dictionary becomes saveable; when it is removed again,
        // the STORED file goes strict-invalid on its next save (no grandfathering).
        val seededTypes = TestEntityTypes.current("Component")!!.types
        val extra = uniqueEntityName("typex").lowercase()
        TestEntityTypes.withKindTypes("Component", seededTypes + extra) {
            val file = componentFile(uniqueEntityName("typok"), type = extra)
            val created = client.createCatalogFile(file)

            TestEntityTypes.withKindTypes("Component", seededTypes) {
                val update = client.putJson("$CATALOG_FILES_PATH/${created.id}", file)
                assertEquals(HttpStatusCode.BadRequest, update.status)
                assertTrue(update.body<ProblemDetail>().detail!!.contains("not an allowed type"))
            }
        }
    }

    @Test
    fun `annotation keys are enforced against the registry - strict, on create and update`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("annenf")
        fun annotated(name: String, key: String) = componentFile(name).let {
            it.copy(metadata = it.metadata.copy(annotations = mapOf(key to "free value, any format!")))
        }

        // An unregistered key is a 400 naming the rule.
        val bad = client.postJson(
            CATALOG_FILES_PATH,
            annotated(uniqueEntityName("annenf"), "never-registered.example.com/${uniqueEntityName("k")}"),
        )
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertTrue(bad.body<ProblemDetail>().detail!!.contains("not a registered annotation key"))

        // A registered key whose kinds exclude the file's kind is a 400 too.
        val groupOnly = uniqueAnnotationKey("anngrp", kinds = listOf("Group"))
        val wrongKind = client.postJson(CATALOG_FILES_PATH, annotated(uniqueEntityName("annwk"), groupOnly))
        assertEquals(HttpStatusCode.BadRequest, wrongKind.status)
        assertTrue(wrongKind.body<ProblemDetail>().detail!!.contains("cannot be applied to kind"))

        // A registered Component key works; once removed, the STORED file goes strict-invalid
        // on its next save (no grandfathering). Values stay free — no registry check on them.
        val allowed = uniqueAnnotationKey("annok")
        val file = annotated(uniqueEntityName("annok"), allowed)
        val created = client.createCatalogFile(file)
        TestAnnotationKeys.remove(allowed)
        val update = client.putJson("$CATALOG_FILES_PATH/${created.id}", file)
        assertEquals(HttpStatusCode.BadRequest, update.status)
        assertTrue(update.body<ProblemDetail>().detail!!.contains("not a registered annotation key"))
    }

    @Test
    fun `spec lifecycle is enforced against the global dictionary - strict, on create and update`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("lcenf")

            // An unregistered lifecycle is a 400 naming the rule (the V16 seed is the gate).
            val bad = client.postJson(
                CATALOG_FILES_PATH,
                componentFile(uniqueEntityName("lcenf"), lifecycle = "never-registered-xyz"),
            )
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertTrue(bad.body<ProblemDetail>().detail!!.contains("not an allowed lifecycle"))

            // A value appended to the dictionary becomes saveable; once removed again, the
            // STORED file goes strict-invalid on its next save (no grandfathering).
            val extra = uniqueEntityName("lcx").lowercase()
            TestLifecycles.ensure(extra)
            try {
                val file = componentFile(uniqueEntityName("lcok"), lifecycle = extra)
                val created = client.createCatalogFile(file)
                TestLifecycles.remove(extra)
                val update = client.putJson("$CATALOG_FILES_PATH/${created.id}", file)
                assertEquals(HttpStatusCode.BadRequest, update.status)
                assertTrue(update.body<ProblemDetail>().detail!!.contains("not an allowed lifecycle"))
            } finally {
                TestLifecycles.remove(extra)
            }
        }

    @Test
    fun `a kind without a dictionary allows no type at all - but an optional type may stay absent`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("typeless")
            fun systemFile(name: String, type: String?) = CatalogFile(
                kind = "System",
                metadata = CatalogFileMetadata(name = name),
                spec = EntitySpec(type = type, owner = "group:default/platform"),
            )
            TestEntityTypes.withKindTypes("System", null) {
                val withType = client.postJson(
                    CATALOG_FILES_PATH,
                    systemFile(uniqueEntityName("typeless"), type = "product"),
                )
                assertEquals(HttpStatusCode.BadRequest, withType.status)
                assertTrue(withType.body<ProblemDetail>().detail!!.contains("No types are defined"))

                // System's type is OPTIONAL — a type-less file saves fine without a dictionary.
                val without = client.postJson(
                    CATALOG_FILES_PATH,
                    systemFile(uniqueEntityName("typeless"), type = null),
                )
                assertEquals(HttpStatusCode.Created, without.status)
            }
        }

    @Test
    fun `allowInvalid waives the soft checks - the strict 400 aggregates them, the waiver stores`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("catalogwaiver")
        val ghostLabel = uniqueEntityName("waivelbl")
        val ghostAnnotation = uniqueEntityName("waiveanno")
        val file = componentFile(
            uniqueEntityName("waived"),
            owner = uniqueEntityName("waiveghost"),
            type = "no-such-type",
            lifecycle = "no-such-lifecycle",
        ).let {
            it.copy(
                metadata = it.metadata.copy(
                    labels = mapOf(ghostLabel to "x"),
                    annotations = mapOf(ghostAnnotation to "v"),
                    tags = listOf(uniqueTag("waivetag")),
                ),
            )
        }

        // Strict default: ONE aggregated 400 naming every soft finding.
        val strict = client.postJson(CATALOG_FILES_PATH, file)
        assertEquals(HttpStatusCode.BadRequest, strict.status)
        val detail = strict.body<ProblemDetail>().detail!!
        assertTrue(detail.contains("does not resolve to a stored entity"))
        assertTrue(detail.contains("not a defined label"))
        assertTrue(detail.contains("not a registered annotation key"))
        assertTrue(detail.contains("is not a defined tag"))
        assertTrue(detail.contains("is not an allowed type"))
        assertTrue(detail.contains("is not an allowed lifecycle"))

        // Waived: the identical document stores; strict update still rejects, waived passes.
        val created = client.postJson("$CATALOG_FILES_PATH?allowInvalid=true", file)
        assertEquals(HttpStatusCode.Created, created.status)
        val id = created.body<CatalogFileResponse>().id
        assertEquals(HttpStatusCode.BadRequest, client.putJson("$CATALOG_FILES_PATH/$id", file).status)
        assertEquals(
            HttpStatusCode.NoContent,
            client.putJson("$CATALOG_FILES_PATH/$id?allowInvalid=true", file).status,
        )
        client.delete("$CATALOG_FILES_PATH/$id")
    }

    @Test
    fun `allowInvalid never waives the hard checks - structure and namespace resolution`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloghard")

        // Structural: a missing required spec.type is a 400 regardless of the waiver.
        val structural = componentFile(uniqueEntityName("hardstruct")).let { it.copy(spec = it.spec.copy(type = null)) }
        val structuralResponse = client.postJson("$CATALOG_FILES_PATH?allowInvalid=true", structural)
        assertEquals(HttpStatusCode.BadRequest, structuralResponse.status)
        assertTrue(structuralResponse.body<ProblemDetail>().detail!!.contains("spec.type is required"))

        // Namespace: an undefined namespace is a 400 regardless of the waiver.
        val undefinedNamespace = componentFile(
            uniqueEntityName("hardns"),
            namespace = uniqueEntityName("ghostns").lowercase(),
        )
        val namespaceResponse = client.postJson("$CATALOG_FILES_PATH?allowInvalid=true", undefinedNamespace)
        assertEquals(HttpStatusCode.BadRequest, namespaceResponse.status)
        assertTrue(namespaceResponse.body<ProblemDetail>().detail!!.contains("not a defined namespace"))
    }

    @Test
    fun `a waived save audits the waivedFindings count - a clean save carries no such field`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("catalogwaudit")
        withAuditCapture { capture ->
            val waived = client.postJson(
                "$CATALOG_FILES_PATH?allowInvalid=true",
                componentFile(uniqueEntityName("wauditsvc"), owner = uniqueEntityName("wauditghost")),
            )
            assertEquals(HttpStatusCode.Created, waived.status)
            val waivedId = waived.body<CatalogFileResponse>().id
            val waivedEvent = capture.awaitEvent {
                it.message == "catalog_file.created" && it.hasKeyValue("catalogFileId", waivedId.toLong())
            }
            assertNotNull(waivedEvent)
            assertTrue(waivedEvent.hasKeyValue("waivedFindings", 1))

            val clean = client.postJson(CATALOG_FILES_PATH, componentFile(uniqueEntityName("wauditclean")))
            assertEquals(HttpStatusCode.Created, clean.status)
            val cleanId = clean.body<CatalogFileResponse>().id
            val cleanEvent = capture.awaitEvent {
                it.message == "catalog_file.created" && it.hasKeyValue("catalogFileId", cleanId.toLong())
            }
            assertNotNull(cleanEvent)
            assertFalse(cleanEvent.keyValuePairs.orEmpty().any { it.key == "waivedFindings" })

            client.delete("$CATALOG_FILES_PATH/$waivedId")
            client.delete("$CATALOG_FILES_PATH/$cleanId")
        }
    }
}
