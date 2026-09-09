package org.traccar.api;

import jakarta.ws.rs.ForbiddenException;
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
}
