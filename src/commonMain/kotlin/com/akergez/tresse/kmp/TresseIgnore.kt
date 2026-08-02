package com.akergez.tresse.kmp

/**
 * Compiled ignore rules — the engine's own matcher, in Rust, shared verbatim with
 * `tresse-cli` and the Obsidian plugin. A host that filters paths on its own side
 * (to prune a directory walk, say) must ask this and nothing else: a replica that
 * disagrees about which files exist commits a deletion the others then apply.
 *
 * Semantics are gitignore's: ordered rules, last match wins, `!` re-includes, a
 * leading or embedded `/` anchors to the root, a trailing `/` restricts to
 * directories. `.tresse` (the local CAS store) is always excluded — it is
 * engine-internal, never user content.
 *
 * Holds a native handle: build one per scan and [close] it, rather than one per path.
 */
expect class TresseIgnore(patterns: List<String>) : AutoCloseable {
    /** `path` is relative to the notes directory and slash-separated. */
    fun isIgnored(path: String, isDir: Boolean): Boolean

    /**
     * Negations that can never fire because an ancestor is already excluded — a
     * silently dead rule reads as if it worked. Worth surfacing to whoever wrote it.
     */
    fun warnings(): List<String>
}
