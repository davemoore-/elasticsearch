/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.datasources.spi;

import java.time.Instant;

/**
 * What storage says when asked whether an object can be read now, from
 * {@link StorageProvider#probeRead(StoragePath)}.
 *
 * <p>Three outcomes rather than a thrown failure, so a caller can branch without catching: {@code Absent}
 * means there is nothing to serve, {@code Denied} means there is and this caller may not have it, and those
 * have different consequences. {@code Readable} carries what the probe observed, so a caller can also tell
 * whether the object still matches a cached entry.
 *
 * <p>Classification is best-effort per provider. S3 maps its refusal; others propagate the failure, which
 * fails closed either way.
 */
public sealed interface ReadOutcome {

    /** Storage served the object's metadata, so this caller can read it. */
    record Readable(long length, Instant lastModified) implements ReadOutcome {}

    /** Storage reported no object at the path. */
    record Absent(ExternalException failure) implements ReadOutcome {}

    /** Storage refused: there may well be an object, and this caller may not read it. */
    record Denied(ExternalException failure) implements ReadOutcome {}

}
