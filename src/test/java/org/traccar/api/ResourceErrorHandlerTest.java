package org.traccar.api;

import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class ResourceErrorHandlerTest {

    @Test
    public void testSecurityExceptionReturnsForbiddenWithoutStackTrace() {
        Response response = new ResourceErrorHandler().toResponse(new SecurityException("private detail"));

        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
        assertFalse(response.hasEntity());
    }

    @Test
    public void testForbiddenResponseDoesNotExposeStackTrace() {
        Response response = new ResourceErrorHandler().toResponse(new ForbiddenException("private detail"));

        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
        assertFalse(response.hasEntity());
    }

    @Test
    public void testUnsupportedMediaTypeDoesNotExposeStackTrace() {
        Response response = new ResourceErrorHandler().toResponse(new NotSupportedException());
        String body = response.getEntity().toString();

        assertEquals(Response.Status.UNSUPPORTED_MEDIA_TYPE.getStatusCode(), response.getStatus());
        assertFalse(body.contains("ResourceErrorHandlerTest"));
        assertFalse(body.contains("\tat "));
    }

    @Test
    public void testGenericErrorReturnsMessageWithoutStackTrace() {
        Response response = new ResourceErrorHandler().toResponse(new IllegalArgumentException("Invalid value"));

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals("Invalid value", response.getEntity());
    }
}
