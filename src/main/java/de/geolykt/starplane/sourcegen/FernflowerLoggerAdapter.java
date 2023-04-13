package de.geolykt.starplane.sourcegen;

import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FernflowerLoggerAdapter extends IFernflowerLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger(FernflowerLoggerAdapter.class);

    public FernflowerLoggerAdapter(Severity severity) {
        setSeverity(severity);
    }

    @Override
    public void writeMessage(String message, Severity severity) {
        if (!accepts(severity)) {
            return;
        }
        switch (severity) {
        case ERROR:
            LOGGER.error(message);
            break;
        case INFO:
            LOGGER.info(message);
            break;
        case TRACE:
            LOGGER.trace(message);
            break;
        case WARN:
            LOGGER.warn(message);
            break;
        default:
            LOGGER.error(message);
            break;
        }
    }

    @Override
    public void writeMessage(String message, Severity severity, Throwable t) {
        if (!accepts(severity)) {
            return;
        }
        switch (severity) {
        case ERROR:
            LOGGER.error(message, t);
            break;
        case INFO:
            LOGGER.info(message, t);
            break;
        case TRACE:
            LOGGER.trace(message, t);
            break;
        case WARN:
            LOGGER.warn(message, t);
            break;
        default:
            LOGGER.error(message, t);
            break;
        }
    }
}
