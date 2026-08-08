package app.silati.data

import kotlinx.serialization.Serializable

/**
 * One page of any cursor-paginated list endpoint. `nextCursor == null` means the end.
 *
 * All five list routes return this shape, so one type serves them all — the element type is
 * what differs.
 */
@Serializable
data class Page<T>(
    val items: List<T> = emptyList(),
    val nextCursor: String? = null,
    /**
     * Conversations only: `false` when the business has no Instagram account connected,
     * which is a different empty state from "connected but nobody has messaged yet".
     * Every other endpoint omits it, hence the default.
     */
    val connected: Boolean = true,
)
