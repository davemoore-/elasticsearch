/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.datasources.spi;

import java.io.IOException;
import java.time.Instant;

/**
 * What storage says when asked whether an object can be read right now, from
 * {@link StorageProvider#probeRead(StoragePath)}.
 *
 * <p>The three outcomes are kept apart because they have different consequences and because collapsing
 * two of them loses the distinction a caller needs: {@code Absent} means there is nothing to serve, while
 * {@code Denied} means there is something and this caller may not have it. {@link StorageProvider#exists}
 * cannot carry that — it answers {@code boolean}, so a refusal and a missing object arrive identically, and
 * a caller reading the answer as "no object" turns a permission failure into an empty result.
 *
 * <p>{@code Readable} carries the length and last-modified the probe observed, so one call answers two
 * independent questions: whether the caller may read the object, and whether it is still the object a
 * cached entry was derived from. Those answers are deliberately not fused — a denial refuses the query,
 * while a moved timestamp only means the derived facts must be recomputed.
 */
public sealed interface ReadOutcome {

    /** Storage served the object's metadata, so this caller can read it. */
    record Readable(long length, Instant lastModified) implements ReadOutcome {}

    /** Storage reported no object at the path. */
    record Absent(ExternalException failure) implements ReadOutcome {}

    /** Storage refused: there may well be an object, and this caller may not read it. */
    record Denied(ExternalException failure) implements ReadOutcome {}

    /**
     * Probes by asking the provider for the object's metadata, which is what every blob provider's stat
     * call already does. Shared so each {@link StorageProvider} states that it probes by stat without
     * repeating the condition mapping, and so no provider is tempted to answer from local state: a
     * {@code Readable} is only ever returned after the provider's own metadata call has returned.
     *
     * <p>Conditions other than a missing object or a refusal propagate. An outage, a throttle or a clock
     * skew is not an answer to the question asked, and reporting one as {@code Denied} would turn a
     * transient fault into a permission failure.
     */
    static ReadOutcome byStat(StorageProvider provider, StoragePath path) throws IOException {
        try {
            StorageObject object = provider.newObject(path);
            long length = object.length();
            Instant lastModified = object.lastModified();
            return new Readable(length, lastModified == null ? Instant.EPOCH : lastModified);
        } catch (ExternalException e) {
            if (e.condition() == ExternalException.Condition.OBJECT_NOT_FOUND) {
                return new Absent(e);
            }
            if (e.condition() == ExternalException.Condition.ACCESS_DENIED) {
                return new Denied(e);
            }
            throw e;
        }
    }
}
