package ch.nokillswit.users

/** Canonical email identity: trimmed + case-folded. Applied at EVERY entry point — create,
 *  login, and the lookup itself — so one mailbox is one account (`ADMIN@x` cannot create a
 *  second account beside `admin@x`, and a padded/case-variant login matches). A pure fold
 *  that never throws: login must stay a uniform 401 on garbage.
 *
 *  When user-creation endpoints arrive, port the shared `validateEmail`/`validateNameAndEmail`
 *  rules from Lettuce's users/Validation.kt alongside this. */
internal fun canonicalEmail(raw: String): String = raw.trim().lowercase()
