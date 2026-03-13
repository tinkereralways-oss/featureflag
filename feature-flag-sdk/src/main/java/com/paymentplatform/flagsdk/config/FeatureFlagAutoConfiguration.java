package com.paymentplatform.flagsdk.config;

import com.paymentplatform.flagsdk.FeatureFlagService;
import com.paymentplatform.flagsdk.aop.FeatureToggleAspect;
import com.paymentplatform.flagsdk.cache.FlagCacheManager;
import com.paymentplatform.flagsdk.client.FlagServerClient;
import com.paymentplatform.flagsdk.event.FlagEvent;
import com.paymentplatform.flagsdk.event.FlagEventListener;
import com.paymentplatform.flagsdk.filter.FeatureFlagRequestFilter;
import com.paymentplatform.flagsdk.heartbeat.HeartbeatManager;
import com.paymentplatform.flagsdk.lifecycle.SdkLifecycleManager;
import com.paymentplatform.flagsdk.proxy.FeatureFlagProxyBeanRegistrar;
import com.paymentplatform.flagsdk.proxy.FeatureFlagProxyFactory;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@AutoConfiguration
@EnableConfigurationProperties(FeatureFlagProperties.class)
@ConditionalOnProperty(prefix = "feature-flag", name = "server-url")
@EnableScheduling
public class FeatureFlagAutoConfiguration {

    @Bean
    public RestTemplate featureFlagRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        return new RestTemplate(factory);
    }

    @Bean
    @ConditionalOnMissingBean
    public FlagCacheManager flagCacheManager() {
        return new FlagCacheManager();
    }

    @Bean
    @ConditionalOnMissingBean
    public FlagServerClient flagServerClient(RestTemplate featureFlagRestTemplate,
                                             FeatureFlagProperties properties,
                                             Environment environment) {
        return new FlagServerClient(featureFlagRestTemplate, properties, environment);
    }

    @Bean
    @ConditionalOnMissingBean
    public FeatureFlagService featureFlagService(FlagCacheManager cacheManager,
                                                  FeatureFlagProperties properties) {
        return new FeatureFlagService(cacheManager, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SdkLifecycleManager sdkLifecycleManager(FlagServerClient serverClient,
                                                     FlagCacheManager cacheManager) {
        return new SdkLifecycleManager(serverClient, cacheManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public HeartbeatManager heartbeatManager(FlagServerClient serverClient) {
        return new HeartbeatManager(serverClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public FlagEventListener flagEventListener(FlagCacheManager cacheManager,
                                                FlagServerClient serverClient) {
        return new FlagEventListener(cacheManager, serverClient);
    }

    @Bean
    public ConsumerFactory<String, FlagEvent> featureFlagConsumerFactory(FeatureFlagProperties properties) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getKafka().getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "feature-flag-" + properties.getInstanceId());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.paymentplatform.*");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, FlagEvent.class.getName());
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, FlagEvent> featureFlagKafkaListenerContainerFactory(
            ConsumerFactory<String, FlagEvent> featureFlagConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, FlagEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(featureFlagConsumerFactory);
        return factory;
    }

    @Bean
    @ConditionalOnMissingBean
    public FeatureToggleAspect featureToggleAspect(FeatureFlagService featureFlagService) {
        return new FeatureToggleAspect(featureFlagService);
    }

    @Bean
    @ConditionalOnMissingBean
    public FeatureFlagProxyFactory featureFlagProxyFactory(FeatureFlagService featureFlagService) {
        return new FeatureFlagProxyFactory(featureFlagService);
    }

    @Bean
    public static FeatureFlagProxyBeanRegistrar featureFlagProxyBeanRegistrar() {
        return new FeatureFlagProxyBeanRegistrar();
    }

    @Bean
    public FilterRegistrationBean<FeatureFlagRequestFilter> featureFlagRequestFilter(FlagCacheManager cacheManager) {
        FilterRegistrationBean<FeatureFlagRequestFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new FeatureFlagRequestFilter(cacheManager));
        registration.addUrlPatterns("/*");
        registration.setOrder(Integer.MIN_VALUE + 10);
        return registration;
    }
}
