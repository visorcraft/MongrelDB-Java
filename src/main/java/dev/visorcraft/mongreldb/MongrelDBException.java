package dev.visorcraft.mongreldb;

/**
 * Base class for all errors raised by the MongrelDB client.
 *
 * <p>Every non-2xx response from the daemon is mapped to a typed subclass of
 * this exception. Catch {@code MongrelDBException} to handle any client-side
 * failure, or catch one of the specific subclasses:
 *
 * <ul>
 *   <li>{@link AuthException} — HTTP 401/403 (bad or missing credentials)
 *   <li>{@link NotFoundException} — HTTP 404 (missing table, schema, etc.)
 *   <li>{@link ConflictException} — HTTP 409 (unique, foreign-key, check, or
 *       trigger constraint violations)
 *   <li>{@link QueryException} — HTTP 400 or 5xx, and any other request-level
 *       failure not covered by the more specific subclasses
 * </ul>
 *
 * <p>Each typed exception also carries the HTTP status code, the daemon's
 * decoded error envelope (message, structured code, and offending op index),
 * so callers can both branch on type and inspect the response detail.
 */
public class MongrelDBException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** HTTP status code returned by the daemon, or {@code -1} when unknown. */
    private final int status;

    /** The server's structured error code, when present (e.g. {@code UNIQUE_VIOLATION}). */
    private final String code;

    /** The offending operation index within a transaction, when the server reports one. */
    private final Integer opIndex;

    /**
     * Constructs a new exception with a message and no HTTP detail.
     *
     * @param message the detail message
     */
    public MongrelDBException(String message) {
        this(message, -1, null, null, null);
    }

    /**
     * Constructs a new exception with a message and a cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public MongrelDBException(String message, Throwable cause) {
        this(message, -1, null, null, cause);
    }

    /**
     * Constructs a new exception carrying the daemon's HTTP response detail.
     *
     * @param message the human-readable error message
     * @param status  the HTTP status code
     * @param code    the server's structured error code, or {@code null}
     * @param opIndex the offending op index within a transaction, or {@code null}
     */
    public MongrelDBException(String message, int status, String code, Integer opIndex) {
        this(message, status, code, opIndex, null);
    }

    MongrelDBException(String message, int status, String code, Integer opIndex, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
        this.opIndex = opIndex;
    }

    /**
     * @return the HTTP status code returned by the daemon, or {@code -1} when unknown
     */
    public int status() {
        return status;
    }

    /**
     * @return the server's structured error code (e.g. {@code UNIQUE_VIOLATION}), or {@code null}
     */
    public String code() {
        return code;
    }

    /**
     * @return the offending op index within a transaction, or {@code null} when not reported
     */
    public Integer opIndex() {
        return opIndex;
    }
}
