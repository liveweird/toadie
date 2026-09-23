# PostgreSQL storage and recovery

PostgreSQL is the only durable store. The `postgres-data` claim holds the complete workspace,
including accounts, sessions, catalog history, blueprints, and entities. Treat deletion or
replacement of that claim as data loss.

## Capacity

New deployments request **10 GiB**. This is an initial allocation, not a measured upper bound.
Record these values weekly (and before a large import):

```sql
SELECT pg_size_pretty(pg_database_size(current_database())) AS database_size;
SELECT pg_size_pretty(sum(pg_total_relation_size(quote_ident(schemaname) || '.' || quote_ident(relname)))) AS table_and_index_size
FROM pg_stat_user_tables;
```

Alert at 70% filesystem use and treat 85% as urgent. Keep enough free space for normal growth,
WAL, index creation, and vacuum. A practical allocation is the larger of 10 GiB or three times
the live database size, then rounded up to the storage provider's next tier. Recalculate from at
least four weeks of observed growth and provision enough for the chosen planning horizon.

The committed PVC size applies automatically only to a new claim. For an existing deployment,
first confirm that its StorageClass has `allowVolumeExpansion: true`, increase the live claim,
and wait for both PVC capacity and the filesystem size to report the new value. Never delete and
recreate the claim to resize it. When the provider cannot expand it, restore a verified backup
into a new, separately named claim during a planned migration.

Logical backup files belong in encrypted storage outside the cluster and outside this PVC. As a
bounded starting policy, take a daily custom-format backup plus one immediately before an
upgrade, retain 14 daily and 8 weekly copies, and run the restore drill quarterly. The service
owner still needs to choose an RPO and RTO; shorten the schedule or use provider snapshots/PITR
when one day of possible data loss or logical-restore time is unacceptable. A snapshot alone is
not a verified PostgreSQL backup unless the provider guarantees a database-consistent capture.

## Create and inspect a backup

The following command reads credentials inside the PostgreSQL pod and writes only the dump to
the operator's machine. Pick a private destination on encrypted storage; the archive contains
application data and password hashes. The operator's machine needs `kubectl` and PostgreSQL 18
client tools (`pg_restore`) on `PATH`.

```sh
(
set -eu
umask 077
backup_path=toadie-$(date -u +%Y%m%dT%H%M%SZ).dump
kubectl -n toadie exec deployment/postgres -- sh -c \
  'exec pg_dump --format=custom --no-owner --no-acl --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' \
  > "$backup_path"
test -s "$backup_path"
pg_restore --list "$backup_path" >/dev/null
printf 'Verified backup: %s\n' "$backup_path"
)
```

Capture the PostgreSQL major version (`kubectl -n toadie exec deployment/postgres -- postgres
--version`), archive checksum, creation time, and deployment version beside the dump. Monitor the
command exit status and nonzero archive size before declaring success. Copy the completed file to
the off-cluster backup store, then apply that store's retention and access controls.

## Exercise restore safely

From the repository root, with Docker available:

```sh
cd e2e
npm run drill:backup-restore
```

The drill starts PostgreSQL 18 source and restore services under a random
`toadie-backup-drill-*` Compose project. Neither service publishes a port or mounts an existing
volume. It creates a fixture with constraints, a sequence, a view, JSON, escaping, and Unicode;
creates and lists a custom-format archive; restores it into the second empty volume; and compares
a deterministic content fingerprint. Cleanup is guarded by the random project-name pattern and
removes only those two volumes after completion, failure, SIGINT, or SIGTERM. It never connects
to the development stack or its `postgres-data` volume. After an uncatchable process/host loss,
inspect the exact project name printed at startup and remove only that project's resources;
never use a blanket volume prune.

For a real recovery rehearsal, use a separately named namespace or cluster and a new empty PVC.
Keep the production and development namespaces out of the active kubectl context. Restore with a
PostgreSQL image at the captured major version, run `pg_restore --exit-on-error --no-owner
--no-acl` into an empty `toadie` database, then start the matching Toadie release so Flyway can
apply only migrations newer than the backup. Verify sign-in, representative catalog and Port
records, history, and row counts before measuring the achieved restore time. Destroy only the
rehearsal namespace after recording the result.

Do not run `pg_restore --clean` against a live database. A recovery replaces service state: stop
application writers, restore into a new database/claim, validate it, and switch the application
only through the deployment's planned rollback procedure.
