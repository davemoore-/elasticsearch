/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.datasources.spi;

import org.elasticsearch.xpack.esql.datasources.StorageEntry;
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
     * Asks storage whether this provider's credentials can read the object now, returning what a listing would
     * have reported for it. The answer must come from storage on this call, never from cached state: callers use
     * it to decide whether facts already derived from the object may be served.
     *
     * <p>Throws rather than returning an outcome, because the provider's own {@link ExternalException} already
     * carries the condition — a refusal and a missing object are distinguishable by {@code condition()} and were
     * never usefully separated by a return type. Anything else, an outage or a throttle, propagates unchanged:
     * reporting one as a refusal would turn a transient fault into a permission failure.
     *
     * <p>Not {@link #exists}, which returns neither the length nor the last-modified this answer carries, so a
     * caller needing the object's version token would have to ask twice.
     *
     * <p>Never carries a null modification time, whatever the provider reports: {@link StorageEntry} substitutes
     * EPOCH, which keeps a key derived from it stable. Providers with no time to report include gRPC/Flight and the
     * GCS and Azure fixtures; one whose time is never trustworthy reports {@link #supportsStableMetadata()} false
     * and takes itself out of caching entirely.
     *
     * <p>Defaulted rather than abstract because there is one correct generic answer, probe by stat. The default
     * asks storage and reports what it said. Override only to wrap the call.
     */
    default StorageEntry probeRead(StoragePath path) throws IOException {
        StorageObject object = newObject(path);
        return new StorageEntry(path, object.length(), object.lastModified());
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

    /**
     * Whether this provider's {@link #listObjects} and {@link #listChildren} results arrive in
     * lexicographic key order. When {@code true}, concatenating the per-child listings produced by
     * a prefix fan-out — each prefix's entries in their own lexicographic span — reproduces the
     * order of a single flat listing, so the fan-out result is deterministic and matches the serial
     * path entry for entry. Returns {@code false} by default; providers whose listing order is
     * unspecified or filesystem-dependent must not override this.
     */
    default boolean listsInKeyOrder() {
        return false;
    }
}
