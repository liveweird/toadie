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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogFileTest {


    @Test
    fun `a regular user can create, read, list, update and delete a catalog file`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")
        val name = uniqueEntityName("svc")

        val created = client.postJson("/api/v1/catalog-files", componentFile(name, title = "My Service"))
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
        assertEquals("/api/v1/catalog-files/${body.id}", created.headers[HttpHeaders.Location])

        val fetched = client.get("/api/v1/catalog-files/${body.id}")
        assertEquals(HttpStatusCode.OK, fetched.status)
        assertEquals(name, fetched.body<CatalogFileResponse>().metadata.name)

        val listed = client.get("/api/v1/catalog-files?name=$name").body<CatalogFilePageResponse>()
        assertEquals(1L, listed.total)
        val row = listed.items.single()
        assertEquals(body.id, row.id)
        assertEquals("My Service", row.title)
        assertEquals("production", row.lifecycle)
        assertEquals(body.creatorName, row.creatorName)

        val updated = client.putJson("/api/v1/catalog-files/${body.id}", componentFile(name, title = "Renamed", lifecycle = "deprecated"))
        assertEquals(HttpStatusCode.NoContent, updated.status)
        val reFetched = client.get("/api/v1/catalog-files/${body.id}").body<CatalogFileResponse>()
        assertEquals("Renamed", reFetched.metadata.title)
        assertEquals("deprecated", reFetched.spec.lifecycle)
        assertTrue(reFetched.updatedAt >= reFetched.createdAt)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/catalog-files/${body.id}").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/catalog-files/${body.id}").status)
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
            val fetched = client.get("/api/v1/catalog-files/${created.id}").body<CatalogFileResponse>()
            assertEquals(file.spec, fetched.spec, "spec must round-trip for kind ${file.kind}")
            assertEquals(file.kind, fetched.kind)
        }
        // Group children and User memberOf survive as PRESENT-and-empty, never null.
        val group = files.first { it.kind == "Group" }
        val fetchedGroup = client.get("/api/v1/catalog-files?namespace=$ns&kind=group&name=${group.metadata.name}")
            .body<CatalogFilePageResponse>()
        assertEquals(listOf(group.metadata.name), fetchedGroup.items.map { it.name })
    }

    @Test
    fun `per-kind rules reject missing required and foreign fields`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")

        suspend fun status(file: CatalogFile): HttpStatusCode = client.postJson("/api/v1/catalog-files", file).status

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
        val created = client.postJson("/api/v1/catalog-files", groupFile(uniqueEntityName("cased")).copy(kind = "gRoUp"))
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
        val flipped = client.putJson("/api/v1/catalog-files/$id", apiFile(name, namespace = ns))
        assertEquals(HttpStatusCode.NoContent, flipped.status)
        assertEquals("API", client.get("/api/v1/catalog-files/$id").body<CatalogFileResponse>().kind)

        // Same name, kind Group — a DIFFERENT identity, so it coexists…
        val groupId = client.createCatalogFile(groupFile(name, namespace = ns)).id
        // …but flipping the group to kind API collides with the flipped file's identity.
        val collision = client.putJson("/api/v1/catalog-files/$groupId", apiFile(name, namespace = ns))
        assertEquals(HttpStatusCode.Conflict, collision.status)
    }

    @Test
    fun `the list filters by kind and rejects unknown kinds`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")
        val ns = uniqueNamespace("kfilter")
        client.createCatalogFile(componentFile(uniqueEntityName("c"), namespace = ns))
        client.createCatalogFile(groupFile(uniqueEntityName("g"), namespace = ns))

        val groups = client.get("/api/v1/catalog-files?namespace=$ns&kind=GROUP").body<CatalogFilePageResponse>()
        assertEquals(listOf("Group"), groups.items.map { it.kind })
        val all = client.get("/api/v1/catalog-files?namespace=$ns&sort=kind").body<CatalogFilePageResponse>()
        assertEquals(listOf("Component", "Group"), all.items.map { it.kind })
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/catalog-files?kind=Gadget").status)
    }

    @Test
    fun `the full metadata surface round-trips`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")
        val name = uniqueEntityName("full")
        TestNamespaces.ensure("team-a")
        TestLabels.ensure("example.com/tier", listOf("backend"), listOf("Component"))
        TestTagCategories.ensure("Languages", listOf("java", "c++"), listOf("Component"))
        // Every referenced target must be STORED (fixed names in the SHARED team-a/default
        // namespaces — ensured idempotently, a 409 from an earlier run is fine).
        suspend fun ensure(target: CatalogFile) {
            val status = client.postJson("/api/v1/catalog-files", target).status
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
        client.get("/api/v1/catalog-files/$id").body()

    @Test
    fun `create and update reject payloads violating the descriptor format`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")

        suspend fun createStatus(file: CatalogFile): HttpStatusCode = client.postJson("/api/v1/catalog-files", file).status

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
        val put = client.putJson("/api/v1/catalog-files/${existing.id}", componentFile("has space"))
        assertEquals(HttpStatusCode.BadRequest, put.status)
    }

    @Test
    fun `entity identity is case-insensitively unique among active files`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")
        val name = uniqueEntityName("Uniq")

        val first = client.createCatalogFile(componentFile(name))

        // Case-variant duplicate in the same namespace → 409 via the partial unique index.
        val duplicate = client.postJson("/api/v1/catalog-files", componentFile(name.lowercase()))
        assertEquals(HttpStatusCode.Conflict, duplicate.status)

        // Same name in another namespace is a different identity.
        TestNamespaces.ensure("team-b")
        val otherNamespace = client.postJson("/api/v1/catalog-files", componentFile(name, namespace = "team-b"))
        assertEquals(HttpStatusCode.Created, otherNamespace.status)

        // Renaming onto an active identity conflicts too.
        val renamed = client.putJson("/api/v1/catalog-files/${otherNamespace.body<CatalogFileResponse>().id}", componentFile(name))
        assertEquals(HttpStatusCode.Conflict, renamed.status)

        // Soft-deleting frees the identity for reuse.
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/catalog-files/${first.id}").status)
        val reused = client.postJson("/api/v1/catalog-files", componentFile(name))
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
                "/api/v1/catalog-files",
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
            "/api/v1/catalog-files",
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
            "/api/v1/catalog-files/${created.id}",
            componentFile(created.metadata.name, namespace = ns),
        )
        assertEquals(HttpStatusCode.BadRequest, update.status)
        TestNamespaces.ensure(ns)
        val unblocked = client.putJson(
            "/api/v1/catalog-files/${created.id}",
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
        val unknown = client.postJson("/api/v1/catalog-files", labeled(uniqueEntityName("lku"), mapOf(ghost to "x")))
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        assertTrue(unknown.body<ProblemDetail>().detail!!.contains("not a defined label"))

        // Registered, but for another kind → 400.
        val apiOnly = uniqueLabel("lblapi", values = listOf("backend"), kinds = listOf("API"))
        val wrongKind = client.postJson(
            "/api/v1/catalog-files",
            labeled(uniqueEntityName("lkk"), mapOf(apiOnly to "backend")),
        )
        assertEquals(HttpStatusCode.BadRequest, wrongKind.status)
        assertTrue(wrongKind.body<ProblemDetail>().detail!!.contains("cannot be applied to kind"))

        // Registered for the kind, but a value outside the closed list → 400.
        val lbl = uniqueLabel("lblok", values = listOf("backend", "frontend"), kinds = listOf("Component"))
        val wrongValue = client.postJson(
            "/api/v1/catalog-files",
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
            client.putJson("/api/v1/catalog-files/${created.id}", file).status,
            "a stored file whose label was removed from the registry must block on save",
        )
        TestLabels.ensure(lbl, listOf("backend"), listOf("Component"))
        assertEquals(HttpStatusCode.NoContent, client.putJson("/api/v1/catalog-files/${created.id}", file).status)
    }

    @Test
    fun `tags are enforced against the ADMIN tag categories - registration and kind`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("tagenforce")

        fun tagged(name: String, tags: List<String>) =
            componentFile(name).let { it.copy(metadata = it.metadata.copy(tags = tags)) }

        // Grammar-valid but unregistered tag → 400.
        val ghost = uniqueTag("tagghost")
        val unknown = client.postJson("/api/v1/catalog-files", tagged(uniqueEntityName("tgu"), listOf(ghost)))
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        assertTrue(unknown.body<ProblemDetail>().detail!!.contains("is not a defined tag"))

        // Registered, but the category is for another kind → 400 naming tag AND category.
        val apiTag = uniqueTag("tagapi")
        val apiCategory = uniqueTagCategory("tagcatapi", tags = listOf(apiTag), kinds = listOf("API"))
        val wrongKind = client.postJson("/api/v1/catalog-files", tagged(uniqueEntityName("tgk"), listOf(apiTag)))
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
            client.putJson("/api/v1/catalog-files/${created.id}", file).status,
            "a stored file whose tag category was removed must block on save",
        )
        TestTagCategories.ensure(category, listOf(t), listOf("Component"))
        assertEquals(HttpStatusCode.NoContent, client.putJson("/api/v1/catalog-files/${created.id}", file).status)
    }

    @Test
    fun `references are enforced on save - missing, wrong kind, and kind-less all 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("refenforce")
        val ns = uniqueNamespace("refns")

        // MISSING: a grammar-valid owner nobody stores.
        val ghostOwner = client.postJson(
            "/api/v1/catalog-files",
            componentFile(uniqueEntityName("rm"), namespace = ns, owner = uniqueEntityName("ghost-team")),
        )
        assertEquals(HttpStatusCode.BadRequest, ghostOwner.status)
        assertTrue(ghostOwner.body<ProblemDetail>().detail!!.contains("does not resolve to a stored entity"))

        // WRONG_KIND: a stored Component named explicitly in owner (Group/User only).
        val comp = uniqueEntityName("rc")
        client.createCatalogFile(componentFile(comp, namespace = ns))
        val wrongKind = client.postJson(
            "/api/v1/catalog-files",
            componentFile(uniqueEntityName("rw"), namespace = ns, owner = "component:$ns/$comp"),
        )
        assertEquals(HttpStatusCode.BadRequest, wrongKind.status)
        assertTrue(wrongKind.body<ProblemDetail>().detail!!.contains("must target Group or User"))

        // KIND_REQUIRED: a kind-less dependsOn entry, even when a component of that name exists.
        val kindless = client.postJson(
            "/api/v1/catalog-files",
            componentFile(uniqueEntityName("rk"), namespace = ns).let {
                it.copy(spec = it.spec.copy(dependsOn = listOf(comp)))
            },
        )
        assertEquals(HttpStatusCode.BadRequest, kindless.status)
        assertTrue(kindless.body<ProblemDetail>().detail!!.contains("needs an explicit kind"))

        // Violations AGGREGATE into one detail.
        val both = client.postJson(
            "/api/v1/catalog-files",
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
            "/api/v1/catalog-files",
            componentFile(uniqueEntityName("ru"), namespace = ns, owner = "user:$ns/$person"),
        )
        assertEquals(HttpStatusCode.Created, userOwned.status)

        // SELF_REFERENCE on create: the self message, not MISSING (the identity isn't stored yet).
        val selfName = uniqueEntityName("rs")
        val selfCreate = client.postJson(
            "/api/v1/catalog-files",
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
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/catalog-files/$teamId").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            client.putJson("/api/v1/catalog-files/${created.id}", file).status,
            "a stored file whose reference target was deleted must block on save",
        )
        client.createCatalogFile(groupFile(team, namespace = ns))
        assertEquals(HttpStatusCode.NoContent, client.putJson("/api/v1/catalog-files/${created.id}", file).status)
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
            val response = client.putJson("/api/v1/catalog-files/${created.id}", selfRef)
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

        val all = client.get("/api/v1/catalog-files?name=$prefix&sort=name").body<CatalogFilePageResponse>()
        assertEquals(3L, all.total)
        assertEquals(listOf("$prefix-a", "$prefix-b", "$prefix-c"), all.items.map { it.name })

        // The namespace filter is exact and case-insensitive (stored lowercase, query folded).
        val nsOne = client.get("/api/v1/catalog-files?name=$prefix&namespace=NS-ONE&sort=name")
            .body<CatalogFilePageResponse>()
        assertEquals(listOf("$prefix-a", "$prefix-c"), nsOne.items.map { it.name })

        val desc = client.get("/api/v1/catalog-files?name=$prefix&sort=-name&page=1&pageSize=2")
            .body<CatalogFilePageResponse>()
        assertEquals(3L, desc.total)
        assertEquals(1, desc.page)
        assertEquals(2, desc.pageSize)
        assertEquals(listOf("$prefix-c", "$prefix-b"), desc.items.map { it.name })
        val page2 = client.get("/api/v1/catalog-files?name=$prefix&sort=-name&page=2&pageSize=2")
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

        val page = client.get("/api/v1/catalog-files") {
            url.parameters.append("name", name.replace("zolw", "żółw"))
        }.body<CatalogFilePageResponse>()
        assertEquals(1L, page.total)
        assertEquals(name, page.items.single().name)
    }

    @Test
    fun `list rejects malformed query parameters`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/catalog-files?page=0").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/catalog-files?page=abc").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/catalog-files?pageSize=0").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/catalog-files?pageSize=101").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/catalog-files?pageSize=abc").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/catalog-files?sort=owner").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/catalog-files?sort=,name").status)
    }

    @Test
    fun `soft-deleted file is invisible to read, update, delete and list`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")
        val name = uniqueEntityName("gone")
        val created = client.createCatalogFile(componentFile(name))

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/catalog-files/${created.id}").status)

        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/catalog-files/${created.id}").status)
        val put = client.putJson("/api/v1/catalog-files/${created.id}", componentFile(name))
        assertEquals(HttpStatusCode.NotFound, put.status)
        // Idempotent: a second delete finds no active row → 404.
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/catalog-files/${created.id}").status)
        val page = client.get("/api/v1/catalog-files?name=$name").body<CatalogFilePageResponse>()
        assertEquals(0L, page.total)
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `update and delete of a non-existent file return 404`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cataloguser")
        val put = client.putJson("/api/v1/catalog-files/999999", componentFile(uniqueEntityName("ghost")))
        assertEquals(HttpStatusCode.NotFound, put.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/catalog-files/999999").status)
    }

    @Test
    fun `catalog file endpoints require authentication`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        val endpoints = listOf(
            HttpMethod.Get to "/api/v1/catalog-files",
            HttpMethod.Post to "/api/v1/catalog-files",
            HttpMethod.Get to "/api/v1/catalog-files/1",
            HttpMethod.Put to "/api/v1/catalog-files/1",
            HttpMethod.Delete to "/api/v1/catalog-files/1",
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
        val fetched = reader.get("/api/v1/catalog-files/${created.id}").body<CatalogFileResponse>()
        assertEquals("Casey Creator", fetched.creatorName)
        assertTrue(fetched.creatorDeleted)
        val row = reader.get("/api/v1/catalog-files?name=$name").body<CatalogFilePageResponse>().items.single()
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
}
