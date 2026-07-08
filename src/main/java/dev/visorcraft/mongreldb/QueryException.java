package dev.visorcraft.mongreldb;

/**
 * Raised for HTTP 400 or 5xx responses, and for any other request-level failure
 * not covered by {@link AuthException}, {@link NotFoundException}, or
 * {@link ConflictException}.
 *
 * <p>This is the catch-all for malformed queries, server-side errors, and
 * transport failures (the latter carries the underlying {@link Throwable} cause
 * and an HTTP status of {@code -1}).
 */
public class QueryException extends MongrelDBException {

    private static final long serialVersionUID = 1L;

    QueryException(String message, int status, String code, Integer opIndex) {
        super(message, status, code, opIndex);
    }

    QueryException(String message, Throwable cause) {
        super(message, cause);
    }

    QueryException(String message) {
        super(message);
    }
}
