package com.ulisesbocchio.jasyptspringboot;

import com.ulisesbocchio.jasyptspringboot.aop.EncryptableMutablePropertySourcesInterceptor;
import com.ulisesbocchio.jasyptspringboot.aop.EncryptablePropertySourceMethodInterceptor;
import com.ulisesbocchio.jasyptspringboot.configuration.EnvCopy;
import com.ulisesbocchio.jasyptspringboot.util.ClassUtils;
import com.ulisesbocchio.jasyptspringboot.wrapper.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.*;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.stream.Collectors.toList;

/**
 * <p>EncryptablePropertySourceConverter class.</p>
 *
 * @author Ulises Bocchio
 * @version $Id: $Id
 */
@Slf4j
public class EncryptablePropertySourceConverter {

    @SuppressWarnings("ArraysAsListWithZeroOrOneArgument")
    private static final List<String> DEFAULT_SKIP_PROPERTY_SOURCE_CLASSES = Arrays.asList(
            "org.springframework.core.env.PropertySource$StubPropertySource",
            "org.springframework.boot.context.properties.source.ConfigurationPropertySourcesPropertySource"
    );
    private final InterceptionMode interceptionMode;
    private final List<Class<PropertySource<?>>> skipPropertySourceClasses;
    private final EncryptablePropertyResolver propertyResolver;
    private final EncryptablePropertyFilter propertyFilter;

    /**
     * <p>Constructor for EncryptablePropertySourceConverter.</p>
     *
     * @param interceptionMode a {@link com.ulisesbocchio.jasyptspringboot.InterceptionMode} object
     * @param skipPropertySourceClasses a {@link java.util.List} object
     * @param propertyResolver a {@link com.ulisesbocchio.jasyptspringboot.EncryptablePropertyResolver} object
     * @param propertyFilter a {@link com.ulisesbocchio.jasyptspringboot.EncryptablePropertyFilter} object
     */
    public EncryptablePropertySourceConverter(InterceptionMode interceptionMode, List<Class<PropertySource<?>>> skipPropertySourceClasses, EncryptablePropertyResolver propertyResolver, EncryptablePropertyFilter propertyFilter) {
        this.interceptionMode = interceptionMode;
        this.skipPropertySourceClasses = Stream.concat(skipPropertySourceClasses.stream(), defaultSkipPropertySourceClasses().stream()).collect(toList());
        this.propertyResolver = propertyResolver;
        this.propertyFilter = propertyFilter;
    }

    static List<Class<PropertySource<?>>> defaultSkipPropertySourceClasses() {
        return DEFAULT_SKIP_PROPERTY_SOURCE_CLASSES.stream().map(EncryptablePropertySourceConverter::getPropertiesClass).collect(toList());
    }

    /**
     * <p>getPropertiesClass.</p>
     *
     * @param className a {@link java.lang.String} object
     * @return a {@link java.lang.Class} object
     */
    @SuppressWarnings("unchecked")
    public static Class<PropertySource<?>> getPropertiesClass(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            if (PropertySource.class.isAssignableFrom(clazz)) {
                return (Class<PropertySource<?>>) clazz;
            }
            throw new IllegalArgumentException("Invalid jasypt.encryptor.skip-property-sources: Class %s does not implement %s".formatted(className, PropertySource.class.getName()));
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Invalid jasypt.encryptor.skip-property-sources: Class %s not found".formatted(className), e);
        }
    }

    /**
     * <p>convertPropertySources.</p>
     *
     * @param propSources a {@link org.springframework.core.env.MutablePropertySources} object
     */
    public void convertPropertySources(MutablePropertySources propSources) {
        propSources.stream()
                .filter(ps -> !(ps instanceof EncryptablePropertySource))
                .map(this::makeEncryptable)
                .collect(toList())
                .forEach(ps -> propSources.replace(ps.getName(), ps));
    }

    /**
     * <p>makeEncryptable.</p>
     *
     * @param propertySource a {@link org.springframework.core.env.PropertySource} object
     * @param <T> a T class
     * @return a {@link org.springframework.core.env.PropertySource} object
     */
    @SuppressWarnings("unchecked")
    public <T> PropertySource<T> makeEncryptable(PropertySource<T> propertySource) {
        if (propertySource instanceof EncryptablePropertySource || skipPropertySourceClasses.stream().anyMatch(skipClass -> skipClass.equals(propertySource.getClass()))) {
            if (!(propertySource instanceof EncryptablePropertySource)) {
                log.info("Skipping PropertySource {} [{}", propertySource.getName(), propertySource.getClass());
            }
            return propertySource;
        }
        PropertySource<T> encryptablePropertySource = convertPropertySource(propertySource);
        log.info("Converting PropertySource {} [{}] to {}", propertySource.getName(), propertySource.getClass().getName(),
                AopUtils.isAopProxy(encryptablePropertySource) ? "AOP Proxy" : encryptablePropertySource.getClass().getSimpleName());
        return encryptablePropertySource;
    }

    /**
     * <p>proxyMutablePropertySources.</p>
     *
     * @param propertySources a {@link org.springframework.core.env.MutablePropertySources} object
     * @param envCopy a {@link com.ulisesbocchio.jasyptspringboot.configuration.EnvCopy} object
     * @return a {@link org.springframework.core.env.MutablePropertySources} object
     */
    public MutablePropertySources proxyMutablePropertySources(MutablePropertySources propertySources, EnvCopy envCopy) {
        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setTarget(MutablePropertySources.class);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addInterface(PropertySources.class);
        proxyFactory.setTarget(propertySources);
        proxyFactory.addAdvice(new EncryptableMutablePropertySourcesInterceptor(this, envCopy));
        return (MutablePropertySources) proxyFactory.getProxy();
    }

    /**
     * <p>convertMutablePropertySources.</p>
     *
     * @param mode a {@link com.ulisesbocchio.jasyptspringboot.InterceptionMode} object
     * @param originalPropertySources a {@link org.springframework.core.env.MutablePropertySources} object
     * @param envCopy a {@link com.ulisesbocchio.jasyptspringboot.configuration.EnvCopy} object
     * @return a {@link org.springframework.core.env.MutablePropertySources} object
     */
    public MutablePropertySources convertMutablePropertySources(InterceptionMode mode, MutablePropertySources originalPropertySources, EnvCopy envCopy) {
        return InterceptionMode.PROXY == mode ?
            proxyMutablePropertySources(originalPropertySources, envCopy) :
            new EncryptableMutablePropertySourcesWrapper(originalPropertySources, this, envCopy);
    }

    private <T> PropertySource<T> convertPropertySource(PropertySource<T> propertySource) {
        return interceptionMode == InterceptionMode.PROXY
                ? proxyPropertySource(propertySource) : instantiatePropertySource(propertySource);
    }

    @SuppressWarnings("unchecked")
    private <T> PropertySource<T> proxyPropertySource(PropertySource<T> propertySource) {
        //can't be proxied with CGLib because of methods being final. So fallback to wrapper for Command Line Arguments only.
        if (CommandLinePropertySource.class.isAssignableFrom(propertySource.getClass())
                // Other PropertySource classes like org.springframework.boot.env.OriginTrackedMapPropertySource
                // are final classes as well
                || Modifier.isFinal(propertySource.getClass().getModifiers())) {
            return instantiatePropertySource(propertySource);
        }
        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setTargetClass(propertySource.getClass());
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addInterface(EncryptablePropertySource.class);
        proxyFactory.setTarget(propertySource);
        proxyFactory.addAdvice(new EncryptablePropertySourceMethodInterceptor<>(propertySource, propertyResolver, propertyFilter));
        return (PropertySource<T>) proxyFactory.getProxy();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> PropertySource<T> instantiatePropertySource(PropertySource<T> propertySource) {
        PropertySource<T> encryptablePropertySource;
        if (needsProxyAnyway(propertySource)) {
            encryptablePropertySource = proxyPropertySource(propertySource);
        } else if (propertySource instanceof SystemEnvironmentPropertySource source) {
            encryptablePropertySource = (PropertySource<T>) new EncryptableSystemEnvironmentPropertySourceWrapper(source, propertyResolver, propertyFilter);
        } else if (propertySource instanceof MapPropertySource source) {
            encryptablePropertySource = (PropertySource<T>) new EncryptableMapPropertySourceWrapper(source, propertyResolver, propertyFilter);
        } else if (propertySource instanceof EnumerablePropertySource source) {
            encryptablePropertySource = new EncryptableEnumerablePropertySourceWrapper<>(source, propertyResolver, propertyFilter);
        } else {
            encryptablePropertySource = new EncryptablePropertySourceWrapper<>(propertySource, propertyResolver, propertyFilter);
        }
        return encryptablePropertySource;
    }

    @SuppressWarnings("unchecked")
    private static boolean needsProxyAnyway(PropertySource<?> ps) {
        return needsProxyAnyway((Class<? extends PropertySource<?>>) ps.getClass());
    }

    private static boolean needsProxyAnyway(Class<? extends PropertySource<?>> psClass) {
        return needsProxyAnyway(psClass.getName());
    }

    /**
     * Some Spring Boot code actually casts property sources to this specific type so must be proxied.
     */
    @SuppressWarnings({"ConstantConditions", "SimplifyStreamApiCallChains"})
    private static boolean needsProxyAnyway(String className) {
        // Turned off for now
        return Stream.of(
//                "org.springframework.boot.context.config.ConfigFileApplicationListener$ConfigurationPropertySources",
//                "org.springframework.boot.context.properties.source.ConfigurationPropertySourcesPropertySource"
        ).anyMatch(className::equals);
    }
}
