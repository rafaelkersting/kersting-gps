package org.traccar.api.resource;

import com.google.common.net.InetAddresses;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import org.traccar.api.BaseResource;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.demo.DemoScenario;
import org.traccar.demo.DemoService;
import org.traccar.helper.LogAction;
import org.traccar.helper.SessionHelper;
import org.traccar.model.DemoSession;
import org.traccar.model.User;

import java.util.Date;
import java.util.List;

@Path("demo")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DemoResource extends BaseResource {

    public record Configuration(
            boolean enabled, int sessionDurationMinutes, String defaultScenario,
            List<DemoScenario.Description> scenarios) {
    }

    public record SessionView(
            long id, long deviceId, String routeId, String scenarioId, String status,
            String currentStep, int progress, Date createdAt, Date startedAt, Date expiresAt, Date finishedAt) {
    }

    public record CreateResponse(User user, SessionView session, Configuration configuration) {
    }

    private final DemoService demoService;
    private final Config config;
    private final LogAction actionLogger;

    @Context
    private HttpServletRequest request;

    @Inject
    public DemoResource(DemoService demoService, Config config, LogAction actionLogger) {
        this.demoService = demoService;
        this.config = config;
        this.actionLogger = actionLogger;
    }

    @GET
    @Path("config")
    @PermitAll
    public Configuration configuration() {
        return new Configuration(
                demoService.isEnabled(), demoService.getDurationMinutes(), demoService.getDefaultScenario(),
                DemoScenario.descriptions());
    }

    @POST
    @Path("session")
    @PermitAll
    public CreateResponse create(DemoService.CreateRequest entity) throws Exception {
        DemoService.Provisioned result = demoService.create(entity, clientIp());
        SessionHelper.userLogin(actionLogger, request, result.user(), result.session().getExpiresAt());
        return new CreateResponse(result.user(), view(result.session()), configuration());
    }

    @GET
    @Path("session")
    public SessionView session() throws Exception {
        return view(demoService.getForUser(getUserId()));
    }

    @POST
    @Path("scenarios/{scenarioId}")
    public SessionView scenario(@PathParam("scenarioId") String scenarioId) throws Exception {
        return view(demoService.startScenario(getUserId(), scenarioId));
    }

    @POST
    @Path("controls/{action}")
    public SessionView control(@PathParam("action") String action) throws Exception {
        return view(demoService.control(getUserId(), action));
    }

    private SessionView view(DemoSession session) {
        return new SessionView(
                session.getId(), session.getDeviceId(), session.getRouteId(), session.getScenarioId(),
                session.getStatus(), session.getCurrentStep(), session.getProgress(), session.getCreatedAt(),
                session.getStartedAt(), session.getExpiresAt(), session.getFinishedAt());
    }

    private String clientIp() {
        String remote = request.getRemoteAddr();
        boolean loopbackProxy = remote != null && InetAddresses.isInetAddress(remote)
                && InetAddresses.forString(remote).isLoopbackAddress();
        if (config.getBoolean(Keys.DEMO_TRUST_PROXY) || loopbackProxy) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null) {
                String candidate = forwarded.split(",", 2)[0].trim();
                if (InetAddresses.isInetAddress(candidate)) {
                    return candidate;
                }
            }
        }
        return remote != null && InetAddresses.isInetAddress(remote) ? remote : "0.0.0.0";
    }
}
