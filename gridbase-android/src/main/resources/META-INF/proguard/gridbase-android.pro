# CacheBackend (in gridbase) opens the Android SQLite cache by reflection.
-keep class io.github.prasunmondal.gridbase.android.AndroidSqliteResponseCache {
    public static *** open(java.nio.file.Path);
}
