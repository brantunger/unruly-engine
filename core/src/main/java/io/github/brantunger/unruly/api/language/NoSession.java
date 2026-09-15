package io.github.brantunger.unruly.api.language;

/** The session of a language that keeps no state between runs; see {@link Session#none()}. */
enum NoSession implements Session {
    INSTANCE
}
