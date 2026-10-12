package com.fromwau.klap

/** Whether one dash-led word may hold several short options. */
public enum class ShortOptions {
    /** Shorts cluster, as getopt reads them: `ls -la` is `ls -l -a`, and `head -n5` gives `-n` the value `5`. */
    Clustered,

    /**
     * Each short is a word of its own, as GNU find reads its `-P -L -H -D -O`: `find -L -P .` runs, while
     * `find -LP .` is "unknown predicate `-LP'". A value a short requires is the next word (`find -D tree .`; `-Dtree`
     * is unknown), and the value of an `.optionalValue()` short stays glued, getopt's only form for it: `-O3` gives
     * `-O` the value `3`, and a bare `-O` gives none. A `.gluedValue()` short stays glued too, and its bare `-O` is
     * refused, as find's own `-O` is.
     */
    Alone,
}
