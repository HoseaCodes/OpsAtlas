package com.ambitiousconcepts.opsatlas.shared;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque pagination cursor.
 *
 * <p>CLAUDE.md section 9 requires cursor pagination for every collection and
 * rules out offset pagination. This is keyset pagination over the row id: row
 * ids are UUIDv7 and therefore time-ordered, so the id alone is a stable,
 * index-friendly sort key and no compound cursor is needed.
 *
 * <p>The encoding is deliberately opaque - base64url of the last id - so the
 * shape can change later without breaking callers who stored one. It is
 * encoding, not encryption: a caller who decodes it learns a row id they were
 * already shown.
 */
public final class Cursor {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private Cursor() {}

    public static String encode(UUID lastId) {
        return ENCODER.encodeToString(lastId.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @throws InvalidCursorException when the value was not produced by {@link #encode}.
     *     A malformed cursor is a client error with an actionable message, not a 500.
     */
    public static UUID decode(String cursor) {
        try {
            return UUID.fromString(new String(DECODER.decode(cursor), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException(cursor);
        }
    }

    public static final class InvalidCursorException extends RuntimeException {

        private static final long serialVersionUID = 1L;
        public InvalidCursorException(String cursor) {
            super("The cursor '" + cursor + "' is not a cursor this API issued. "
                    + "Pass the nextCursor value from a previous response, or omit it to start at the beginning.");
        }
    }
}
