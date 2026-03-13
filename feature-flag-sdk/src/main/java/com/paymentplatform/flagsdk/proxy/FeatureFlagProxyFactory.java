package com.paymentplatform.flagsdk.proxy;

import com.paymentplatform.flagsdk.FeatureFlagService;
import com.paymentplatform.flagsdk.annotation.FeatureFlagDisabled;
import com.paymentplatform.flagsdk.annotation.FeatureFlagEnabled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * BeanPostProcessor that detects interfaces with both @FeatureFlagEnabled and @FeatureFlagDisabled
 * implementations and creates a JDK dynamic proxy that routes to the correct impl based on flag state.
 */
public class FeatureFlagProxyFactory implements ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagProxyFactory.class);

    private final FeatureFlagService featureFlagService;
    private ApplicationContext applicationContext;

    public FeatureFlagProxyFactory(FeatureFlagService featureFlagService) {
        this.featureFlagService = featureFlagService;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * Creates a dynamic proxy for the given interface that routes between enabled and disabled implementations.
     */
    public <T> T createProxy(Class<T> interfaceType, T enabledImpl, T disabledImpl, String flagKey) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("toString".equals(method.getName())) {
                return "FeatureFlagProxy[" + interfaceType.getSimpleName() + ", flag=" + flagKey + "]";
            }
            if ("hashCode".equals(method.getName())) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(method.getName())) {
                return proxy == args[0];
            }

            boolean flagEnabled = featureFlagService.isEnabled(flagKey);
            T target = flagEnabled ? enabledImpl : disabledImpl;
            log.debug("Routing {}.{} to {} (flag {} = {})",
                    interfaceType.getSimpleName(), method.getName(),
                    target.getClass().getSimpleName(), flagKey, flagEnabled);
            return method.invoke(target, args);
        };

        @SuppressWarnings("unchecked")
        T proxy = (T) Proxy.newProxyInstance(
                interfaceType.getClassLoader(),
                new Class<?>[]{interfaceType},
                handler
        );
        return proxy;
    }

    /**
     * Scans beans and creates proxies for interfaces with paired @FeatureFlagEnabled/@FeatureFlagDisabled.
     * Call from a BeanFactoryPostProcessor or programmatically after context refresh.
     */
    public Map<Class<?>, ProxyRegistration> detectFlaggedInterfaces() {
        Map<String, FlaggedPair> pairsByFlag = new HashMap<>();

        // Find all beans annotated with @FeatureFlagEnabled
        Map<String, Object> enabledBeans = applicationContext.getBeansWithAnnotation(FeatureFlagEnabled.class);
        for (Map.Entry<String, Object> entry : enabledBeans.entrySet()) {
            Object bean = entry.getValue();
            FeatureFlagEnabled ann = bean.getClass().getAnnotation(FeatureFlagEnabled.class);
            if (ann == null) continue;
            String flagKey = ann.value();
            pairsByFlag.computeIfAbsent(flagKey, k -> new FlaggedPair(flagKey)).enabledBean = bean;
            pairsByFlag.get(flagKey).enabledBeanName = entry.getKey();
        }

        // Find all beans annotated with @FeatureFlagDisabled
        Map<String, Object> disabledBeans = applicationContext.getBeansWithAnnotation(FeatureFlagDisabled.class);
        for (Map.Entry<String, Object> entry : disabledBeans.entrySet()) {
            Object bean = entry.getValue();
            FeatureFlagDisabled ann = bean.getClass().getAnnotation(FeatureFlagDisabled.class);
            if (ann == null) continue;
            String flagKey = ann.value();
            pairsByFlag.computeIfAbsent(flagKey, k -> new FlaggedPair(flagKey)).disabledBean = bean;
            pairsByFlag.get(flagKey).disabledBeanName = entry.getKey();
        }

        Map<Class<?>, ProxyRegistration> proxies = new HashMap<>();
        for (FlaggedPair pair : pairsByFlag.values()) {
            if (pair.enabledBean == null || pair.disabledBean == null) {
                log.warn("Incomplete feature flag pair for flag '{}': enabled={}, disabled={}",
                        pair.flagKey, pair.enabledBean != null, pair.disabledBean != null);
                continue;
            }

            // Find common interface
            Class<?> commonInterface = findCommonInterface(pair.enabledBean.getClass(), pair.disabledBean.getClass());
            if (commonInterface == null) {
                log.warn("No common interface found for flag '{}' implementations: {} and {}",
                        pair.flagKey, pair.enabledBean.getClass().getSimpleName(),
                        pair.disabledBean.getClass().getSimpleName());
                continue;
            }

            log.info("Creating feature flag proxy for interface {} with flag '{}'",
                    commonInterface.getSimpleName(), pair.flagKey);

            @SuppressWarnings("unchecked")
            Object proxy = createProxy(
                    (Class<Object>) commonInterface,
                    pair.enabledBean,
                    pair.disabledBean,
                    pair.flagKey
            );
            proxies.put(commonInterface, new ProxyRegistration(proxy, pair.enabledBeanName, pair.disabledBeanName));
        }

        return proxies;
    }

    private Class<?> findCommonInterface(Class<?> class1, Class<?> class2) {
        Set<Class<?>> interfaces1 = getAllInterfaces(class1);
        Set<Class<?>> interfaces2 = getAllInterfaces(class2);

        for (Class<?> iface : interfaces1) {
            if (interfaces2.contains(iface) && !isFrameworkInterface(iface)) {
                return iface;
            }
        }
        return null;
    }

    private Set<Class<?>> getAllInterfaces(Class<?> clazz) {
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Class<?> iface : current.getInterfaces()) {
                interfaces.add(iface);
            }
            current = current.getSuperclass();
        }
        return interfaces;
    }

    private boolean isFrameworkInterface(Class<?> iface) {
        String name = iface.getName();
        return name.startsWith("org.springframework.") ||
                name.startsWith("java.") ||
                name.startsWith("jakarta.");
    }

    private static class FlaggedPair {
        final String flagKey;
        Object enabledBean;
        Object disabledBean;
        String enabledBeanName;
        String disabledBeanName;

        FlaggedPair(String flagKey) {
            this.flagKey = flagKey;
        }
    }

    public record ProxyRegistration(Object proxy, String enabledBeanName, String disabledBeanName) {}
}
