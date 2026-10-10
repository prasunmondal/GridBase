package io.github.prasunmondal.gridbase.cache;

/** How a cached read is used. Only read-only requests (SELECT, GET_COLUMNS) are ever cached. */
public enum CacheStrategy {

    /** Serve an unexpired cached reply without any network call; otherwise fetch and cache. */
    CACHE_FIRST,

    /**
     * Always fetch and refresh the cache. If the network fails (transport error or a retryable
     * engine error), fall back to the last cached reply, even an expired one.
     */
    NETWORK_FIRST
}
