package org.traccar.api;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.traccar.demo.DemoException;

import java.util.Map;

@Provider
public class DemoExceptionMapper implements ExceptionMapper<DemoException> {

    @Override
    public Response toResponse(DemoException exception) {
        return Response.status(exception.getStatus())
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(Map.of("error", exception.getMessage()))
                .build();
    }
}
