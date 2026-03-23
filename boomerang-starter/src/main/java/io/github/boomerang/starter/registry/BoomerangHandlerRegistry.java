package io.github.boomerang.starter.registry;

import io.github.boomerang.annotation.BoomerangHandler;
import io.github.boomerang.model.SyncContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Scans the application context after startup for exactly one method annotated with
 * {@link BoomerangHandler} and holds a reference to it for reflective invocation by the
 * worker. Scanning is deferred to {@link ContextRefreshedEvent} so that all beans —
 * including those created by auto-configurations — are fully initialised before the scan.
 *
 * <p>Throws {@link IllegalStateException} at startup if zero or more than one handler
 * method is found.
 */
@Slf4j
public class BoomerangHandlerRegistry {

    private final ApplicationContext applicationContext;

    private volatile Object handlerBean;
    private volatile Method handlerMethod;

    public BoomerangHandlerRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * Triggered after the application context is fully refreshed. Runs before
     * {@link io.github.boomerang.starter.service.BoomerangWorker#startPolling} (Order 1).
     */
    @EventListener(ContextRefreshedEvent.class)
    @Order(1)
    public void onContextRefreshed() {
        Object foundBean   = null;
        Method foundMethod = null;

        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Object bean;
            try {
                bean = applicationContext.getBean(beanName);
            } catch (Exception e) {
                // Skip beans that cannot be instantiated (e.g. abstract, factory beans)
                continue;
            }

            Class<?> targetClass = AopUtils.getTargetClass(bean);
            for (Method method : targetClass.getDeclaredMethods()) {
                if (method.isAnnotationPresent(BoomerangHandler.class)) {
                    if (foundMethod != null) {
                        throw new IllegalStateException(
                                "Multiple @BoomerangHandler methods found. " +
                                "Exactly one is required. " +
                                "First: " + foundBean.getClass().getName() + "#" + foundMethod.getName() + " " +
                                "Second: " + bean.getClass().getName() + "#" + method.getName());
                    }
                    foundBean   = bean;
                    foundMethod = method;
                    foundMethod.setAccessible(true);
                }
            }
        }

        if (foundMethod == null) {
            throw new IllegalStateException(
                    "@EnableBoomerang requires exactly one @BoomerangHandler method in the " +
                    "application context. Please annotate your handler method with @BoomerangHandler.");
        }

        this.handlerBean   = foundBean;
        this.handlerMethod = foundMethod;
        log.info("Boomerang handler registered: {}#{}", handlerBean.getClass().getName(), handlerMethod.getName());
    }

    /**
     * Invokes the registered handler method with the given {@link SyncContext}.
     *
     * @param ctx per-job context carrying jobId, callerId, and trigger timestamp
     * @return the handler's return value (may be {@code null} for void methods)
     * @throws Exception any exception thrown by the handler
     */
    public Object invoke(SyncContext ctx) throws Exception {
        if (handlerMethod == null) {
            throw new IllegalStateException("BoomerangHandlerRegistry has not been initialised yet");
        }
        try {
            return handlerMethod.invoke(handlerBean, ctx);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException("Handler threw an unexpected error", cause);
        }
    }
}
