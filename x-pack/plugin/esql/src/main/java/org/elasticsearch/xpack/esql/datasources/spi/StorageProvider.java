/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.datasources.spi;

import org.elasticsearch.xpack.esql.datasources.StorageIterator;

import java.io.Closeable;
import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Abstraction for accessing objects in external storage systems.
 * Implementations handle specific protocols (HTTP, S3, GCS, local, etc.).
 * This is a read-only interface focused on ESQL's needs for querying external data.
 */
public interface StorageProvider extends Closeable {

    /** Creates a StorageObject for reading. The path must be a valid object path. */
    StorageObject newObject(StoragePath path);

    /** Creates a StorageObject with pre-known length (avoids HEAD request for remote objects). */
    StorageObject newObject(StoragePath path, long length);

    /** Creates a StorageObject with pre-known length and modification time. */
    StorageObject newObject(StoragePath path, long length, Instant lastModified);

    /**
     * Lists objects under a prefix. For blob storage, lists all objects with the given prefix.
     * Returns an iterator to support lazy loading of large directories.
     *
     * @param prefix the prefix path to list under
     * @param recursive if true, recurse into subdirectories; if false, list only immediate children
     */
    StorageIterator listObjects(StoragePath prefix, boolean recursive) throws IOException;

    /**
     * Lists the immediate children of a directory-like prefix, distinguishing subdirectories from
     * objects — for blob storage, a delimiter listing whose common prefixes are the subdirectories.
     * This lets a partition-aware caller skip whole subtrees; see {@link StorageChildren}.
     *
     * <p>Returning {@code null} means "fall back to {@link #listObjects}", legitimate when the
     * provider cannot enumerate directories (e.g. plain HTTP) or the directory holds more than
     * {@code limit} children — the result is fully materialized, so implementations must stop rather
     * than buffer without bound. Deliberately not a default method: forgetting to implement (or
     * delegate) it would silently disable partition-pruned listing, so each implementation states
     * its choice.
     */
    StorageChildren listChildren(StoragePath prefix, int limit) throws IOException;

    /** Checks if an object exists at the given path. */
    boolean exists(StoragePath path) throws IOException;

    /**
     * Asks storage whether this provider's credentials can read the object right now, and what its length and
     * last-modified are. The answer must come from storage on this call and never from cached or local state:
     * callers use it to decide whether facts already derived from the object may be served, so an answer
     * recalled from memory would confirm nothing.
     *
     * <p>One call answers two independent questions — whether the caller may read the object, and whether it is
     * still the object a cached entry was derived from. They stay separate because the consequences differ: a
     * refusal fails the query, a moved timestamp only means the derived facts must be recomputed.
     *
     * <p>{@link #exists} is not a substitute. It answers {@code boolean}, so a refusal and a missing object
     * arrive identically, and a caller reading that as "no object" turns a permission failure into an empty
     * result.
     *
     * <p>Unlike {@link #listChildren}, this has a default, because here there is one correct generic answer
     * rather than a choice between two legitimate ones: probe by stat. The default is safe in the direction
     * that matters — it asks storage and reports what storage said, and no code path returns a readable answer
     * without a metadata call having returned first. Override only to add behaviour around the probe, such as a
     * retry policy or a concurrency permit.
     */
    default ReadOutcome probeRead(StoragePath path) throws IOException {
        return ReadOutcome.byStat(this, path);
    }

    /** Returns the URI schemes this provider handles (e.g., ["http", "https"]). */
    List<String> supportedSchemes();

    /**
     * Whether this provider's objects have reliable last-modified timestamps suitable
     * for mtime-based cache invalidation. Returns {@code true} by default.
     * Providers serving dynamic content (e.g. HTTP URLs) should return {@code false}
     * so the schema cache is bypassed and metadata is resolved fresh on every query.
     */
    default boolean supportsStableMetadata() {
        return true;
    }
}
