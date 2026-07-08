package dev.visorcraft.mongreldb;

/**
 * Raised for HTTP 409 responses - a unique, foreign-key, check, or trigger
 * constraint violation.
 *
 * <p>During a transaction commit, the engine enforces all constraints at commit
 * time. On any violation every staged operation rolls back and this exception is
 * thrown carrying the server's structured {@link #code()} (e.g.
 * {@code UNIQUE_VIOLATION}, {@code FK_VIOLATION}) and the offending
 * {@link #opIndex()} within the batch.
 */
public class ConflictException extends MongrelDBException {

    private static final long serialVersionUID = 1L;

    ConflictException(String message, int status, String code, Integer opIndex) {
        super(message, status, code, opIndex);
    }
}
