package io.github.prasunmondal.gridbase.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ResponseCache} in a SQLite database via {@code sqlite-jdbc}, which needs a native library for
 * the platform. On Android use {@code AndroidSqliteResponseCache} from {@code gridbase-android} (the
 * platform's own SQLite, same file format) or {@link JournalResponseCache}.
 *
 * <p>One instance (one JDBC connection) per database file per JVM, shared via {@link #open}.
 * Thread-safe.</p>
 */
public final class SqliteResponseCache extends SqlResponseCache {

    private static final Map<Path, SqliteResponseCache> OPEN = new ConcurrentHashMap<>();

    private SqliteResponseCache(Path file) {
        super(file, connect(file));
    }

    /** The shared cache for {@code file}, opened (and created) on first use. */
    public static SqliteResponseCache open(Path file) {
        Path key = file.toAbsolutePath().normalize();
        return OPEN.compute(key, (k, existing) -> existing != null && !existing.isClosed()
                ? existing : new SqliteResponseCache(k));
    }

    @Override
    public synchronized void close() {
        OPEN.remove(file(), this);
        super.close();
    }

    private static Sql connect(Path file) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // The driver is used directly rather than via DriverManager: ServiceLoader driver discovery is
            // unreliable on Android ("No suitable driver").
            Connection connection = new org.sqlite.JDBC().connect("jdbc:sqlite:" + file, new Properties());
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA busy_timeout = 5000");
                s.execute("PRAGMA journal_mode = WAL");
                s.execute("PRAGMA foreign_keys = ON");
            } catch (SQLException e) {
                connection.close();
                throw e;
            }
            return new Jdbc(connection);
        } catch (SQLException | IOException e) {
            throw cannotOpen(file, e);
        }
    }

    /** {@link Sql} over one JDBC connection. */
    private static final class Jdbc implements Sql {
        private final Connection connection;

        Jdbc(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void execute(String sql) {
            try (Statement s = connection.createStatement()) {
                s.execute(sql);
            } catch (SQLException e) {
                throw new SqlFailure(e);
            }
        }

        @Override
        public int update(String sql, Object... args) {
            try (PreparedStatement ps = prepare(sql, args)) {
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw new SqlFailure(e);
            }
        }

        @Override
        public Optional<String[]> queryRow(String sql, Object... args) {
            try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String[] row = new String[rs.getMetaData().getColumnCount()];
                for (int i = 0; i < row.length; i++) {
                    row[i] = rs.getString(i + 1);
                }
                return Optional.of(row);
            } catch (SQLException e) {
                throw new SqlFailure(e);
            }
        }

        @Override
        public void inTransaction(Runnable work) {
            try {
                connection.setAutoCommit(false);
                try {
                    work.run();
                    connection.commit();
                } catch (RuntimeException e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw new SqlFailure(e);
            }
        }

        @Override
        public boolean isClosed() {
            try {
                return connection.isClosed();
            } catch (SQLException e) {
                return true;
            }
        }

        @Override
        public void close() {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new SqlFailure(e);
            }
        }

        private PreparedStatement prepare(String sql, Object... args) throws SQLException {
            PreparedStatement ps = connection.prepareStatement(sql);
            try {
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof Long l) {
                        ps.setLong(i + 1, l);
                    } else {
                        ps.setString(i + 1, (String) args[i]);
                    }
                }
                return ps;
            } catch (SQLException | RuntimeException e) {
                ps.close();
                throw e;
            }
        }
    }
}
