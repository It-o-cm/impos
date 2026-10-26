package com.intermarche.pos.ui.supervision;

import com.intermarche.pos.domain.deploy.FunctionUsage;
import com.intermarche.pos.service.deploy.FunctionUsageService;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Counts every use of a REGISTER function (BO-08-02-02).
 *
 * <p>A register function is a gesture of the selling screen: a POST to one of
 * its {@code /action/...} handlers. The count is filed under the handler's
 * path TEMPLATE — {@code /action/add/{code}}, not the article scanned — so the
 * statistics count functions, not articles. The counters travel up with the
 * register's reports.
 *
 * <p>Never in the way: a count that cannot be written is logged and the sale
 * proceeds.
 */
@Provider
@Priority(Priorities.USER + 50)
public class RegisterFunctionUsageFilter implements ContainerRequestFilter {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(RegisterFunctionUsageFilter.class);

    /** The handler being called. */
    @Context
    ResourceInfo resourceInfo;

    /** The counters. */
    @Inject
    FunctionUsageService functionUsageService;

    /** This register's identifier. */
    @ConfigProperty(name = "pos.terminal.id", defaultValue = "POS01")
    String terminalId;

    /** This register's point of sale, when commissioned. */
    @ConfigProperty(name = "pos.pdv.number")
    Optional<String> pdvNumber;

    /**
     * Counts the call when it is a register function.
     *
     * @param request the request
     */
    @Override
    public void filter(ContainerRequestContext request) {
        Method method = resourceInfo == null ? null : resourceInfo.getResourceMethod();
        if (method == null || !HttpMethod.POST.equals(request.getMethod())) {
            return;
        }
        String function = template(resourceInfo.getResourceClass(), method);
        if (!function.startsWith("/action")) {
            return;
        }
        try {
            functionUsageService.record(terminalId, pdvNumber.orElse(null), FunctionUsage.REGISTER, function);
        } catch (RuntimeException failure) {
            LOGGER.warnf("Usage de fonction non compté (%s)", failure.getMessage());
        }
    }

    /**
     * The path template of a handler.
     *
     * @param type the resource class
     * @param method the handler
     * @return the class path followed by the method path, single slashes
     */
    static String template(Class<?> type, Method method) {
        Path classPath = type.getAnnotation(Path.class);
        Path methodPath = method.getAnnotation(Path.class);
        String full = (classPath == null ? "" : classPath.value()) + "/"
                + (methodPath == null ? "" : methodPath.value());
        return full.replaceAll("/+", "/");
    }
}
