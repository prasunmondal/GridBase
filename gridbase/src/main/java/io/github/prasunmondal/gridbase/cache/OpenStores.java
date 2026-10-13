package io.github.prasunmondal.gridbase.cache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Every built-in store that is open in this JVM, for {@link CacheBackend#clearAllOpen()}. Stores add
 * themselves when opened and remove themselves on close. Held weakly, so an unclosed private store
 * that is no longer used can still be collected.
 */
final class OpenStores {

    private static final Set<ResponseCache> OPEN = Collections.newSetFromMap(new WeakHashMap<>());

    private OpenStores() {
    }

    static synchronized void add(ResponseCache store) {
        OPEN.add(store);
    }

    static synchronized void remove(ResponseCache store) {
        OPEN.remove(store);
    }

    static synchronized List<ResponseCache> snapshot() {
        return new ArrayList<>(OPEN);
    }
}
