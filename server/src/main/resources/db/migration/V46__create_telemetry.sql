-- V46: in-app telemetry (.claude/docs/observability.md "Telemetry"): the Toadie process samples ITSELF into these two tables;
-- the ADMIN telemetry panel reads them. Numbers and labels only, never project data.

-- One row per process run (a JVM start): who sampled. Identity and limits only - never JVM arguments, environment or system
-- properties. `last_seen` is stamped on every sample tick; rows unseen for 90 days are pruned (their samples cascade).
CREATE TABLE telemetry_processes (
    id SERIAL PRIMARY KEY,
    instance VARCHAR(100) NOT NULL,
    started_at BIGINT NOT NULL,
    last_seen BIGINT NOT NULL,
    build_version VARCHAR(64),
    build_commit VARCHAR(64),
    processors INT NOT NULL,
    heap_max_bytes BIGINT NOT NULL,
    container_limit_bytes BIGINT,
    CONSTRAINT uq_telemetry_processes_identity UNIQUE (instance, started_at)
);
CREATE INDEX idx_telemetry_processes_last_seen ON telemetry_processes (last_seen);

-- ONE narrow table for every metric (the metric names, kinds and units live in telemetry/TelemetryMetrics.kt, so a new metric
-- needs no migration). `resolution` is the sample's width in seconds: a raw sample stores the sampler's REAL interval (60..1800,
-- a divisor of 3600; kept 7 days), 3600 = an hourly rollup (kept 90 days; `n` = the raw samples folded in, `avg` their
-- n-weighted mean, `max` the largest raw max, so a 5-minute peak survives the rollup).
-- Hard-deleted by the prune (persistence.md "Telemetry (V46)").
CREATE TABLE telemetry_samples (
    resolution INT NOT NULL CHECK (resolution BETWEEN 60 AND 3600 AND 3600 % resolution = 0),
    ts BIGINT NOT NULL,
    process_id INT NOT NULL REFERENCES telemetry_processes (id) ON DELETE CASCADE,
    metric VARCHAR(48) NOT NULL,
    label VARCHAR(120) NOT NULL DEFAULT '',
    n INT NOT NULL DEFAULT 1,
    avg DOUBLE PRECISION NOT NULL,
    max DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (resolution, metric, ts, process_id, label)
);
-- The prune and the hourly rollup scan by (resolution, ts); the FK index keeps a process delete's cascade off a table scan.
CREATE INDEX idx_telemetry_samples_prune ON telemetry_samples (resolution, ts);
CREATE INDEX idx_telemetry_samples_process ON telemetry_samples (process_id);
