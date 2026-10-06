/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.datasources.spi;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;

/**
 * Evidence that storage was asked, on this query, whether the caller's credentials can read a particular
 * object — and said yes. Carries what the probe observed, so a caller can also tell whether the object is
 * still the one a cached entry was derived from.
 *
 * <p>This exists so that serving a fact derived from an object's contents can require evidence rather than
 * rely on each call site remembering to check. A cache that takes one of these as a parameter cannot be read
 * without the caller having asked, and the compiler says so; a cache that merely documents that callers
 * should check first is a convention, and the four stores this was written for show what happens to those.
 *
 * <p>Deliberately holds no credential, no configuration and no provider. The caches that consume it stay
 * ignorant of what a credential is and of which storage system answered — they require proof, they do not
 * perform authorization.
 */
public record ReadProof(StoragePath path, long length, Instant lastModified) {

    public ReadProof {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(lastModified, "lastModified");
    }

    /**
     * Asks storage, and returns the proof if it said yes.
     *
     * @throws ExternalException if the object cannot be read — the provider's own refusal or
     *         missing-object failure, rethrown so the caller surfaces why rather than an empty result
     */
    public static ReadProof probe(StorageProvider provider, StoragePath path) throws IOException {
        ReadOutcome outcome = provider.probeRead(path);
        return switch (outcome) {
            case ReadOutcome.Readable readable -> new ReadProof(path, readable.length(), readable.lastModified());
            case ReadOutcome.Denied denied -> throw denied.failure();
            case ReadOutcome.Absent absent -> throw absent.failure();
        };
    }

    /**
     * Whether this proof was taken against the same object, at the same version, that a cached entry was
     * derived from. A proof for a different path proves nothing about this entry; a proof whose timestamp has
     * moved means the object changed under the entry and the derived facts no longer describe it.
     */
    public boolean covers(String canonicalPath, long derivedFromLastModified) {
        return path.toString().equals(canonicalPath) && lastModified.toEpochMilli() == derivedFromLastModified;
    }
}
