package io.github.prasunmondal.hibernatesheets.internal;

import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thin wrapper over {@code java.util.logging}. {@code System.Logger} is avoided because it does not
 * exist on Android; messages are built lazily and only when the level is enabled.
 */
public final class Log {

    private final Logger logger;

    private Log(Logger logger) {
        this.logger = logger;
    }

    public static Log get(Class<?> type) {
        return new Log(Logger.getLogger(type.getName()));
    }

    public void trace(Supplier<String> message) {
        log(Level.FINER, message);
    }

    public void debug(Supplier<String> message) {
        log(Level.FINE, message);
    }

    public void warning(Supplier<String> message) {
        log(Level.WARNING, message);
    }

    private void log(Level level, Supplier<String> message) {
        if (logger.isLoggable(level)) {
            logger.logp(level, logger.getName(), null, message.get());
        }
    }
}
