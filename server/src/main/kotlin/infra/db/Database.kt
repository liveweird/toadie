package ch.nokillswit.infra.db

import ch.nokillswit.annotations.AnnotationKeyService
import ch.nokillswit.annotations.AnnotationKeyServiceKey
import ch.nokillswit.blueprints.BlueprintService
import ch.nokillswit.blueprints.BlueprintServiceKey
import ch.nokillswit.auth.TokenBlocklistService
import ch.nokillswit.auth.TokenBlocklistServiceKey
import ch.nokillswit.auth.AuthSessionService
import ch.nokillswit.auth.AuthSessionServiceKey
import ch.nokillswit.auth.PasswordResetService
import ch.nokillswit.auth.PasswordResetServiceKey
import ch.nokillswit.catalog.CatalogFileEventService
import ch.nokillswit.catalog.CatalogFileEventServiceKey
import ch.nokillswit.catalog.CatalogFileService
import ch.nokillswit.catalog.CatalogFileServiceKey
import ch.nokillswit.dictionaries.DictionaryService
import ch.nokillswit.dictionaries.DictionaryServiceKey
import ch.nokillswit.entities.EntityService
import ch.nokillswit.entities.EntityServiceKey
import ch.nokillswit.labels.LabelService
import ch.nokillswit.labels.LabelServiceKey
import ch.nokillswit.lenses.LensService
import ch.nokillswit.lenses.LensServiceKey
import ch.nokillswit.tags.TagCategoryService
import ch.nokillswit.tags.TagCategoryServiceKey
import ch.nokillswit.types.EntityTypesService
import ch.nokillswit.types.EntityTypesServiceKey
import ch.nokillswit.users.GraphLayoutService
import ch.nokillswit.users.GraphLayoutServiceKey
import ch.nokillswit.users.UserService
import ch.nokillswit.users.UserServiceKey
import io.ktor.server.application.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/**
 * The DI composition root: connects the one R2DBC database and publishes every service into
 * [Application.attributes]. Feature modules read their services back via the AttributeKey —
 * application.yaml runs this module before any route module, so the keys are always present.
 */
suspend fun Application.configureDatabase() {
    val database = R2dbcDatabase.connect(
        url = environment.config.property("postgres.r2dbcUrl").getString(),
        user = environment.config.property("postgres.user").getString(),
        password = environment.config.property("postgres.password").getString(),
    )
    attributes.put(UserServiceKey, UserService(database))
    attributes.put(GraphLayoutServiceKey, GraphLayoutService(database))
    val catalogFileEventService = CatalogFileEventService(database)
    attributes.put(CatalogFileServiceKey, CatalogFileService(database, catalogFileEventService))
    attributes.put(CatalogFileEventServiceKey, catalogFileEventService)
    attributes.put(DictionaryServiceKey, DictionaryService(database))
    attributes.put(LabelServiceKey, LabelService(database))
    attributes.put(LensServiceKey, LensService(database))
    attributes.put(TagCategoryServiceKey, TagCategoryService(database))
    attributes.put(EntityTypesServiceKey, EntityTypesService(database))
    attributes.put(AnnotationKeyServiceKey, AnnotationKeyService(database))
    attributes.put(BlueprintServiceKey, BlueprintService(database))
    attributes.put(EntityServiceKey, EntityService(database))
    attributes.put(TokenBlocklistServiceKey, TokenBlocklistService(database))
    attributes.put(AuthSessionServiceKey, AuthSessionService(database))
    attributes.put(PasswordResetServiceKey, PasswordResetService(
        database,
        ttlMillis = environment.config.property("security.passwordReset.tokenTtlSeconds").getString().toLong()
            .also { require(it in 1..3600) { "Password reset TTL must be between 1 and 3600 seconds" } } * 1000,
    ))
}
