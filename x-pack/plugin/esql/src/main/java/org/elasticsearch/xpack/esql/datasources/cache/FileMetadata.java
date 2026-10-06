/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.datasources.cache;

/**
 * A single object's cheap physical metadata: byte {@code length} and last-modified epoch millis, as
 * observed by the probe that resolved the object on this query.
 * <p>
 * mtime is the version token that rebuilds the {@link SchemaCacheKey} and populates the resolved
 * {@code StorageEntry}; it is not a second freshness clock. {@code length} travels with it because the
 * single-file resolve rebuilds its singleton file list from both.
 * <p>
 * Not cached. These two values come from the same probe that establishes the caller can read the object,
 * and that probe runs per resolve: an mtime recalled from an earlier query would make the identity keys
 * below it rest on a reading nobody took this time round.
 */
public record FileMetadata(long length, long mtimeMillis) {}
