package dev.visorcraft.mongreldb;

/**
 * Raised for HTTP 401 or 403 responses - bad or missing credentials.
 *
 * <p>The daemon returns these when started in {@code --auth-token} or
 * {@code --auth-users} mode and the request lacks valid credentials.
 */
public class AuthException extends MongrelDBException {

    private static final long serialVersionUID = 1L;

    AuthException(String message, int status, String code, Integer opIndex) {
        super(message, status, code, opIndex);
    }
}
