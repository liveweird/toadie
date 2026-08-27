package ch.nokillswit.infra.db

import ch.nokillswit.auth.TokenBlocklistService
import ch.nokillswit.auth.TokenBlocklistServiceKey
import ch.nokillswit.catalog.CatalogFileService
import ch.nokillswit.catalog.CatalogFileServiceKey
import ch.nokillswit.dictionaries.DictionaryService
import ch.nokillswit.dictionaries.DictionaryServiceKey
import ch.nokillswit.labels.LabelService
import ch.nokillswit.labels.LabelServiceKey
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
    attributes.put(CatalogFileServiceKey, CatalogFileService(database))
    attributes.put(DictionaryServiceKey, DictionaryService(database))
    attributes.put(LabelServiceKey, LabelService(database))
    attributes.put(TokenBlocklistServiceKey, TokenBlocklistService(database))
}
