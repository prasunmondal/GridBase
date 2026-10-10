# CacheBackend (in gridbase) opens the Android SQLite cache by reflection.
-keep class io.github.prasunmondal.hibernatesheets.android.AndroidSqliteResponseCache {
    public static *** open(java.nio.file.Path);
}
