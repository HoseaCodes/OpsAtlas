package com.ambitiousconcepts.opsatlas.shared;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import java.util.UUID;

/**
 * Identifier generation.
 *
 * <p>CLAUDE.md section 7: UUIDv7 where ordering helps, UUIDv4 elsewhere. Row
 * identifiers are v7 because they double as the keyset-pagination sort key -
 * see {@link Cursor}. Generating application-side rather than in PostgreSQL
 * keeps the id available before insert and avoids depending on a server version
 * that ships {@code uuidv7()}.
 */
public final class Ids {

    private static final TimeBasedEpochGenerator V7 = Generators.timeBasedEpochGenerator();

    private Ids() {}

    /** A time-ordered identifier, for rows whose natural order is creation order. */
    public static UUID newRowId() {
        return V7.generate();
    }

    /** An unordered identifier, for everything else. */
    public static UUID newId() {
        return UUID.randomUUID();
    }
}
