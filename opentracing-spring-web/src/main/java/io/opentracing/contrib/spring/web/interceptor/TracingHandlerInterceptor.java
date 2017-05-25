package io.opentracing.contrib.spring.web.interceptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import io.opentracing.ActiveSpan;
import io.opentracing.References;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.contrib.web.servlet.filter.TracingFilter;

/**
 * Tracing handler interceptor for spring web. It creates a new span for an incoming request.
 * This handler depends on {@link TracingFilter}. Both classes have to be properly configured.
 *
 * <p>HTTP tags and logged errors are added in {@link TracingFilter}. This interceptor adds only
 * spring related logs (handler class/method).
 *
 * @author Pavol Loffay
 */
public class TracingHandlerInterceptor extends HandlerInterceptorAdapter {

    private Tracer tracer;
    private List<HandlerInterceptorSpanDecorator> decorators;

    /**
     * @param tracer
     */
    @Autowired
    public TracingHandlerInterceptor(Tracer tracer) {
        this(tracer, Collections.singletonList(HandlerInterceptorSpanDecorator.STANDARD_TAGS));
    }

    /**
     * @param tracer tracer
     * @param decorators span decorators
     */
    @Autowired
    public TracingHandlerInterceptor(Tracer tracer, List<HandlerInterceptorSpanDecorator> decorators) {
        this.tracer = tracer;
        this.decorators = new ArrayList<>(decorators);
    }

    @Override
    public boolean preHandle(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Object handler)
            throws Exception {

        if (httpServletRequest.getAttribute(ActiveSpan.Continuation.class.getName() + handler.toString()) != null) {
            // Only call this preHandle once for the handler
            return true;
        }

        ActiveSpan serverSpan = tracer.activeSpan();
        boolean localSpan = false;
        if (serverSpan == null) {
            // Need to check if handler has already been called by preHandle
            if (httpServletRequest.getAttribute(ActiveSpan.Continuation.class.getName()) != null) {
                ActiveSpan.Continuation cont = (ActiveSpan.Continuation)
                        httpServletRequest.getAttribute(ActiveSpan.Continuation.class.getName());
                // Clear attribute to make clear that the continuation has been used
                httpServletRequest.setAttribute(ActiveSpan.Continuation.class.getName(), null);
                serverSpan = cont.activate();

            // Check if previous server span context was stored
            } else if (httpServletRequest.getAttribute(TracingFilter.SERVER_SPAN_CONTEXT) != null) {
                serverSpan = tracer.buildSpan(httpServletRequest.getMethod())
                        .addReference(References.FOLLOWS_FROM,
                                (SpanContext)httpServletRequest.getAttribute(TracingFilter.SERVER_SPAN_CONTEXT))
                        .startActive();
                localSpan = true;
            } else {
                return true;
            }
        }

        httpServletRequest.setAttribute(ActiveSpan.Continuation.class.getName() + handler.toString(),
                serverSpan.capture());

        for (HandlerInterceptorSpanDecorator decorator : decorators) {
            decorator.onPreHandle(httpServletRequest, handler, serverSpan);
        }

        // Create another continuation to pass to next handler in the chain
        httpServletRequest.setAttribute(ActiveSpan.Continuation.class.getName(),
                serverSpan.capture());

        if (localSpan) {
            // Deactivate the locally started span, so that once the continuation has been
            // handled in the 'afterCompletion' handler, it will automatically be finished.
            serverSpan.deactivate();
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
                                Object handler, Exception ex) throws Exception {
        if (httpServletRequest.getAttribute(ActiveSpan.Continuation.class.getName()) != null) {
            ActiveSpan.Continuation cont = (ActiveSpan.Continuation)
                    httpServletRequest.getAttribute(ActiveSpan.Continuation.class.getName());
            // Clear unused continuation, to prevent span being unreported
            cont.activate().deactivate();
            httpServletRequest.setAttribute(ActiveSpan.Continuation.class.getName(), null);
        }

        ActiveSpan serverSpan = null;
        ActiveSpan.Continuation cont = (ActiveSpan.Continuation)
                httpServletRequest.getAttribute(ActiveSpan.Continuation.class.getName() + handler.toString());

        if (cont != null) {
            serverSpan = cont.activate();
        }

        if (serverSpan != null) {
            for (HandlerInterceptorSpanDecorator decorator : decorators) {
                decorator.onAfterCompletion(httpServletRequest, httpServletResponse, handler, ex, serverSpan);
            }
            serverSpan.deactivate();
        }
    }

    /**
     * It checks whether a request should be traced or not.
     *
     * @param httpServletRequest request
     * @param httpServletResponse response
     * @param handler handler
     * @return whether request should be traced or not
     */
    protected boolean isTraced(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
                               Object handler) {
        return true;
    }

}
