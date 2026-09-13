package com.ambitiousconcepts.opsatlas.shared;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * One page of a collection.
 *
 * <p>{@code nextCursor} is null when this is the last page. There is no total
 * count: computing one requires a second full scan on every request, and no
 * caller in this API needs it.
 *
 * @param items    the rows in this page, in stable order
 * @param nextCursor pass back as {@code ?cursor=} to fetch the next page, or null
 */
public record PageResponse<T>(List<T> items, @Schema(nullable = true) String nextCursor) {

    public static <T> PageResponse<T> of(List<T> items, String nextCursor) {
        return new PageResponse<>(List.copyOf(items), nextCursor);
    }

    public static <T> PageResponse<T> lastPage(List<T> items) {
        return new PageResponse<>(List.copyOf(items), null);
    }
}
