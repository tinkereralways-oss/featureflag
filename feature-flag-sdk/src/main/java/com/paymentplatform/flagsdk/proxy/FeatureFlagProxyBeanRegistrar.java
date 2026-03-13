package com.paymentplatform.flagsdk.proxy;

import com.paymentplatform.flagsdk.FeatureFlagService;
import com.paymentplatform.flagsdk.annotation.FeatureFlagDisabled;
import com.paymentplatform.flagsdk.annotation.FeatureFlagEnabled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Scans bean definitions for paired @FeatureFlagEnabled/@FeatureFlagDisabled annotations,
 * then registers a primary proxy FactoryBean for each common interface.
 * Runs before bean instantiation to prevent ambiguous dependency errors.
 */
public class FeatureFlagProxyBeanRegistrar implements BeanFactoryPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagProxyBeanRegistrar.class);

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (!(beanFactory instanceof BeanDefinitionRegistry registry)) {
            log.warn("BeanFactory is not a BeanDefinitionRegistry, skipping feature flag proxy registration");
            return;
        }

        Map<String, FlaggedPair> pairsByFlag = new HashMap<>();

        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
            String className = bd.getBeanClassName();
            if (className == null) continue;

            try {
                Class<?> beanClass = Class.forName(className);
                FeatureFlagEnabled enabled = beanClass.getAnnotation(FeatureFlagEnabled.class);
                if (enabled != null) {
                    FlaggedPair pair = pairsByFlag.computeIfAbsent(enabled.value(), FlaggedPair::new);
                    pair.enabledBeanName = beanName;
                    pair.enabledClass = beanClass;
                }
                FeatureFlagDisabled disabled = beanClass.getAnnotation(FeatureFlagDisabled.class);
                if (disabled != null) {
                    FlaggedPair pair = pairsByFlag.computeIfAbsent(disabled.value(), FlaggedPair::new);
                    pair.disabledBeanName = beanName;
                    pair.disabledClass = beanClass;
                }
            } catch (ClassNotFoundException e) {
                // Skip beans whose class can't be loaded
            }
        }

        for (FlaggedPair pair : pairsByFlag.values()) {
            if (pair.enabledClass == null || pair.disabledClass == null) {
                log.warn("Incomplete feature flag pair for flag '{}': enabled={}, disabled={}",
                        pair.flagKey, pair.enabledBeanName, pair.disabledBeanName);
                continue;
            }

            Class<?> commonInterface = findCommonInterface(pair.enabledClass, pair.disabledClass);
            if (commonInterface == null) {
                log.warn("No common interface for flag '{}': {} and {}",
                        pair.flagKey, pair.enabledClass.getSimpleName(), pair.disabledClass.getSimpleName());
                continue;
            }

            String proxyBeanName = "featureFlagProxy_" + pair.flagKey.replace("-", "_");
            log.info("Registering feature flag proxy bean '{}' for interface {} (flag='{}')",
                    proxyBeanName, commonInterface.getSimpleName(), pair.flagKey);

            GenericBeanDefinition proxyDef = new GenericBeanDefinition();
            proxyDef.setBeanClass(FeatureFlagProxyFactoryBean.class);
            proxyDef.getConstructorArgumentValues().addIndexedArgumentValue(0, commonInterface);
            proxyDef.getConstructorArgumentValues().addIndexedArgumentValue(1, pair.flagKey);
            proxyDef.getConstructorArgumentValues().addIndexedArgumentValue(2, pair.enabledBeanName);
            proxyDef.getConstructorArgumentValues().addIndexedArgumentValue(3, pair.disabledBeanName);
            proxyDef.setPrimary(true);

            registry.registerBeanDefinition(proxyBeanName, proxyDef);
        }
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

    /**
     * FactoryBean that lazily creates the dynamic proxy once all beans are available.
     */
    public static class FeatureFlagProxyFactoryBean implements FactoryBean<Object>, BeanFactoryAware {

        private final Class<?> interfaceType;
        private final String flagKey;
        private final String enabledBeanName;
        private final String disabledBeanName;
        private ConfigurableListableBeanFactory beanFactory;

        public FeatureFlagProxyFactoryBean(Class<?> interfaceType, String flagKey,
                                           String enabledBeanName, String disabledBeanName) {
            this.interfaceType = interfaceType;
            this.flagKey = flagKey;
            this.enabledBeanName = enabledBeanName;
            this.disabledBeanName = disabledBeanName;
        }

        @Override
        public void setBeanFactory(org.springframework.beans.factory.BeanFactory beanFactory) throws BeansException {
            this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
        }

        @Override
        public Object getObject() {
            Object enabledImpl = beanFactory.getBean(enabledBeanName);
            Object disabledImpl = beanFactory.getBean(disabledBeanName);
            FeatureFlagService flagService = beanFactory.getBean(FeatureFlagService.class);

            return Proxy.newProxyInstance(
                    interfaceType.getClassLoader(),
                    new Class<?>[]{interfaceType},
                    (proxy, method, args) -> {
                        if ("toString".equals(method.getName())) {
                            return "FeatureFlagProxy[" + interfaceType.getSimpleName() + ", flag=" + flagKey + "]";
                        }
                        if ("hashCode".equals(method.getName())) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(method.getName())) {
                            return proxy == args[0];
                        }
                        boolean enabled = flagService.isEnabled(flagKey);
                        Object target = enabled ? enabledImpl : disabledImpl;
                        return method.invoke(target, args);
                    }
            );
        }

        @Override
        public Class<?> getObjectType() {
            return interfaceType;
        }

        @Override
        public boolean isSingleton() {
            return true;
        }
    }

    private static class FlaggedPair {
        final String flagKey;
        String enabledBeanName;
        String disabledBeanName;
        Class<?> enabledClass;
        Class<?> disabledClass;

        FlaggedPair(String flagKey) {
            this.flagKey = flagKey;
        }
    }
}
