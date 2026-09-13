package com.ambitiousconcepts.opsatlas.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CursorTest {

    @Test
    void round_trips_an_id() {
        UUID id = Ids.newRowId();
        assertThat(Cursor.decode(Cursor.encode(id))).isEqualTo(id);
    }

    @Test
    void encoding_is_url_safe_and_unpadded() {
        // A cursor travels in a query string. Standard base64 would need
        // escaping, and callers would eventually forget to escape it.
        for (int i = 0; i < 200; i++) {
            assertThat(Cursor.encode(Ids.newRowId())).matches("[A-Za-z0-9_-]+");
        }
    }

    @Test
    void ordering_by_row_id_is_ordering_by_creation_time() {
        // Keyset pagination over the id is only correct because UUIDv7 is
        // time-ordered. If this ever stops holding, the pagination silently
        // starts skipping and repeating rows, so it is asserted rather than
        // assumed.
        UUID previous = Ids.newRowId();
        for (int i = 0; i < 1_000; i++) {
            UUID next = Ids.newRowId();
            assertThat(next.toString()).isGreaterThan(previous.toString());
            previous = next;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-cursor", "!!!!", "AAAA", "MTIz"})
    void rejects_anything_it_did_not_issue(String malformed) {
        assertThatThrownBy(() -> Cursor.decode(malformed))
                .isInstanceOf(Cursor.InvalidCursorException.class)
                .hasMessageContaining("nextCursor");
    }

    @Test
    void the_rejection_message_says_what_to_do_instead() {
        // A 400 that only says "bad cursor" makes the caller guess.
        assertThatThrownBy(() -> Cursor.decode("garbage"))
                .hasMessageContaining("omit it to start at the beginning");
    }
}
