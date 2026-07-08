package dev.visorcraft.mongreldb;

/**
 * Raised for HTTP 404 responses - a missing table, schema, or other resource.
 */
public class NotFoundException extends MongrelDBException {

    private static final long serialVersionUID = 1L;

    NotFoundException(String message, int status, String code, Integer opIndex) {
        super(message, status, code, opIndex);
    }
}
