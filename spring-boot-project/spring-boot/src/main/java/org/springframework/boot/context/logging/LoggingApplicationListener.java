/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.context.logging;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.context.event.ApplicationStartingEvent;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.logging.LogFile;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.logging.LoggingSystemProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.GenericApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

/**
 * An {@link ApplicationListener} that configures the {@link LoggingSystem}. If the
 * environment contains a {@code logging.config} property it will be used to bootstrap the
 * logging system, otherwise a default configuration is used. Regardless, logging levels
 * will be customized if the environment contains {@code logging.level.*} entries and
 * logging groups can be defined with {@code logging.group}.
 * <p>
 * Debug and trace logging for Spring, Tomcat, Jetty and Hibernate will be enabled when
 * the environment contains {@code debug} or {@code trace} properties that aren't set to
 * {@code "false"} (i.e. if you start your application using
 * {@literal java -jar myapp.jar [--debug | --trace]}). If you prefer to ignore these
 * properties you can set {@link #setParseArgs(boolean) parseArgs} to {@code false}.
 * <p>
 * By default, log output is only written to the console. If a log file is required the
 * {@code logging.path} and {@code logging.file} properties can be used.
 * <p>
 * Some system properties may be set as side effects, and these can be useful if the
 * logging configuration supports placeholders (i.e. log4j or logback):
 * <ul>
 * <li>{@code LOG_FILE} is set to the value of path of the log file that should be written
 * (if any).</li>
 * <li>{@code PID} is set to the value of the current process ID if it can be determined.
 * </li>
 * </ul>
 *
 * @author Dave Syer
 * @author Phillip Webb
 * @author Andy Wilkinson
 * @author Madhura Bhave
 * @since 2.0.0
 * @see LoggingSystem#get(ClassLoader)
 */
public class LoggingApplicationListener implements GenericApplicationListener {

	private static final ConfigurationPropertyName LOGGING_LEVEL = ConfigurationPropertyName.of("logging.level");

	private static final ConfigurationPropertyName LOGGING_GROUP = ConfigurationPropertyName.of("logging.group");

	private static final Bindable<Map<String, String>> STRING_STRING_MAP = Bindable.mapOf(String.class, String.class);

	private static final Bindable<Map<String, String[]>> STRING_STRINGS_MAP = Bindable.mapOf(String.class,
			String[].class);

	/**
	 * The default order for the LoggingApplicationListener.
	 */
	public static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

	/**
	 * The name of the Spring property that contains a reference to the logging
	 * configuration to load.
	 */
	public static final String CONFIG_PROPERTY = "logging.config";

	/**
	 * The name of the Spring property that controls the registration of a shutdown hook
	 * to shut down the logging system when the JVM exits.
	 * @see LoggingSystem#getShutdownHandler
	 */
	public static final String REGISTER_SHUTDOWN_HOOK_PROPERTY = "logging.register-shutdown-hook";

	/**
	 * The name of the {@link LoggingSystem} bean.
	 */
	public static final String LOGGING_SYSTEM_BEAN_NAME = "springBootLoggingSystem";

	/**
	 * The name of the {@link LogFile} bean.
	 */
	public static final String LOGFILE_BEAN_NAME = "springBootLogFile";

	private static final Map<String, List<String>> DEFAULT_GROUP_LOGGERS;
	static {
		MultiValueMap<String, String> loggers = new LinkedMultiValueMap<>();
		loggers.add("web", "org.springframework.core.codec");
		loggers.add("web", "org.springframework.http");
		loggers.add("web", "org.springframework.web");
		loggers.add("web", "org.springframework.boot.actuate.endpoint.web");
		loggers.add("web", "org.springframework.boot.web.servlet.ServletContextInitializerBeans");
		loggers.add("sql", "org.springframework.jdbc.core");
		loggers.add("sql", "org.hibernate.SQL");
		DEFAULT_GROUP_LOGGERS = Collections.unmodifiableMap(loggers);
	}

	private static final Map<LogLevel, List<String>> LOG_LEVEL_LOGGERS;
	static {
		MultiValueMap<LogLevel, String> loggers = new LinkedMultiValueMap<>();
		loggers.add(LogLevel.DEBUG, "sql");
		loggers.add(LogLevel.DEBUG, "web");
		loggers.add(LogLevel.DEBUG, "org.springframework.boot");
		loggers.add(LogLevel.TRACE, "org.springframework");
		loggers.add(LogLevel.TRACE, "org.apache.tomcat");
		loggers.add(LogLevel.TRACE, "org.apache.catalina");
		loggers.add(LogLevel.TRACE, "org.eclipse.jetty");
		loggers.add(LogLevel.TRACE, "org.hibernate.tool.hbm2ddl");
		LOG_LEVEL_LOGGERS = Collections.unmodifiableMap(loggers);
	}

	private static final Class<?>[] EVENT_TYPES = { ApplicationStartingEvent.class,
			ApplicationEnvironmentPreparedEvent.class, ApplicationPreparedEvent.class, ContextClosedEvent.class,
			ApplicationFailedEvent.class };

	private static final Class<?>[] SOURCE_TYPES = { SpringApplication.class, ApplicationContext.class };

	private static final AtomicBoolean shutdownHookRegistered = new AtomicBoolean(false);

	private final Log logger = LogFactory.getLog(getClass());

	private LoggingSystem loggingSystem;

	private LogFile logFile;

	private int order = DEFAULT_ORDER;

	private boolean parseArgs = true;

	private LogLevel springBootLogging = null;

	@Override
	public boolean supportsEventType(ResolvableType resolvableType) {
		return isAssignableFrom(resolvableType.getRawClass(), EVENT_TYPES);
	}

	@Override
	public boolean supportsSourceType(Class<?> sourceType) {
		return isAssignableFrom(sourceType, SOURCE_TYPES);
	}

	private boolean isAssignableFrom(Class<?> type, Class<?>... supportedTypes) {
		if (type != null) {
			for (Class<?> supportedType : supportedTypes) {
				if (supportedType.isAssignableFrom(type)) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public void onApplicationEvent(ApplicationEvent event) {
		if (event instanceof ApplicationStartingEvent) {
			//应用开始时执行操作
			onApplicationStartingEvent((ApplicationStartingEvent) event);
		}
		else if (event instanceof ApplicationEnvironmentPreparedEvent) {
			//环境准备好后执行参数
			onApplicationEnvironmentPreparedEvent((ApplicationEnvironmentPreparedEvent) event);
		}
		else if (event instanceof ApplicationPreparedEvent) {
			//应用准备好时执行
			onApplicationPreparedEvent((ApplicationPreparedEvent) event);
		}
		else if (event instanceof ContextClosedEvent
				&& ((ContextClosedEvent) event).getApplicationContext().getParent() == null) {
			//上下文关闭时
			onContextClosedEvent();
		}
		else if (event instanceof ApplicationFailedEvent) {
			//应用错误时
			onApplicationFailedEvent();
		}
	}

	private void onApplicationStartingEvent(ApplicationStartingEvent event) {
		this.loggingSystem = LoggingSystem.get(event.getSpringApplication().getClassLoader());
		this.loggingSystem.beforeInitialize();
	}

	//对日志系统进行配置
	private void onApplicationEnvironmentPreparedEvent(ApplicationEnvironmentPreparedEvent event) {
		if (this.loggingSystem == null) {
			this.loggingSystem = LoggingSystem.get(event.getSpringApplication().getClassLoader());
		}
		//初始化日志系统
		initialize(event.getEnvironment(), event.getSpringApplication().getClassLoader());
	}

	private void onApplicationPreparedEvent(ApplicationPreparedEvent event) {
		ConfigurableListableBeanFactory beanFactory = event.getApplicationContext().getBeanFactory();
		if (!beanFactory.containsBean(LOGGING_SYSTEM_BEAN_NAME)) {
			beanFactory.registerSingleton(LOGGING_SYSTEM_BEAN_NAME, this.loggingSystem);
		}
		if (this.logFile != null && !beanFactory.containsBean(LOGFILE_BEAN_NAME)) {
			beanFactory.registerSingleton(LOGFILE_BEAN_NAME, this.logFile);
		}
	}

	private void onContextClosedEvent() {
		if (this.loggingSystem != null) {
			this.loggingSystem.cleanUp();
		}
	}

	private void onApplicationFailedEvent() {
		if (this.loggingSystem != null) {
			this.loggingSystem.cleanUp();
		}
	}

	/**
	 * Initialize the logging system according to preferences expressed through the
	 * {@link Environment} and the classpath.
	 * @param environment the environment
	 * @param classLoader the classloader
	 */
	protected void initialize(ConfigurableEnvironment environment, ClassLoader classLoader) {
		//将应用的配置设置到系统中
		new LoggingSystemProperties(environment).apply();
		//是否设置了日志文件配置
		this.logFile = LogFile.get(environment);
		if (this.logFile != null) {
			//将日志文件配置设置到系统环境变量中
			this.logFile.applyToSystemProperties();
		}
		//初始化早期的日志级别 如 java -jar myapp.jar --debug|trace
		initializeEarlyLoggingLevel(environment);
		//初始化日志系统 从spring-boot的配置文件 、 日志系统默认配置文件位置
		initializeSystem(environment, this.loggingSystem, this.logFile);
		//最终设置配置的Logger的日志级别
		initializeFinalLoggingLevels(environment, this.loggingSystem);
		//注册关机回调
		registerShutdownHookIfNecessary(environment, this.loggingSystem);
	}

	private void initializeEarlyLoggingLevel(ConfigurableEnvironment environment) {
		//获得启动参数 查看启动参数中的配置 是指spring-boot 一些组件的 日志级别
		if (this.parseArgs && this.springBootLogging == null) {
			if (isSet(environment, "debug")) {
				this.springBootLogging = LogLevel.DEBUG;
			}
			if (isSet(environment, "trace")) {
				this.springBootLogging = LogLevel.TRACE;
			}
		}
	}

	private boolean isSet(ConfigurableEnvironment environment, String property) {
		String value = environment.getProperty(property);
		return (value != null && !value.equals("false"));
	}

	private void initializeSystem(ConfigurableEnvironment environment, LoggingSystem system, LogFile logFile) {
		LoggingInitializationContext initializationContext = new LoggingInitializationContext(environment);
		String logConfig = environment.getProperty(CONFIG_PROPERTY);//查看配置系统中是否有 logging.config的配置如果有的话说明要对其进行配置
		if (ignoreLogConfig(logConfig)) {//说明没有配置 ，进行默认的配置
			system.initialize(initializationContext, null, logFile);
		}
		else {
			try {
				//从指定的配置文件中加载设置文件进行 系统的初始化
				ResourceUtils.getURL(logConfig).openStream().close();
				system.initialize(initializationContext, logConfig, logFile);
			}
			catch (Exception ex) {
				// NOTE: We can't use the logger here to report the problem
				System.err.println(
						"Logging system failed to initialize " + "using configuration from '" + logConfig + "'");
				ex.printStackTrace(System.err);
				throw new IllegalStateException(ex);
			}
		}
	}

	private boolean ignoreLogConfig(String logConfig) {
		return !StringUtils.hasLength(logConfig) || logConfig.startsWith("-D");
	}

	private void initializeFinalLoggingLevels(ConfigurableEnvironment environment, LoggingSystem system) {
		if (this.springBootLogging != null) {
			//如果设置了 springBootLogging 就将springboot设置的几个组件的日志级别记性覆盖
			initializeLogLevel(system, this.springBootLogging);
		}
		//设置我们自己设置的组件日志级别
		setLogLevels(system, environment);
	}

	protected void initializeLogLevel(LoggingSystem system, LogLevel level) {
		//感觉像是在脱裤子放屁，但是有可能有一种情况是 被用户自己改了级别 通过springBootLogging的设置来进行强制配置
		//或者是开启执行配置组件log
		LOG_LEVEL_LOGGERS.getOrDefault(level, Collections.emptyList())
				.forEach((logger) -> initializeLogLevel(system, level, logger));
	}

	private void initializeLogLevel(LoggingSystem system, LogLevel level, String logger) {
		List<String> groupLoggers = DEFAULT_GROUP_LOGGERS.get(logger);
		if (groupLoggers == null) {
			system.setLogLevel(logger, level);
			return;
		}
		//设置默认规定的级别
		groupLoggers.forEach((groupLogger) -> system.setLogLevel(groupLogger, level));
	}

	protected void setLogLevels(LoggingSystem system, Environment environment) {
		if (!(environment instanceof ConfigurableEnvironment)) {
			return;
		}
		Binder binder = Binder.get(environment);
		Map<String, String[]> groups = getGroups();
		//通过组配置来统一设置日志级别
		binder.bind(LOGGING_GROUP, STRING_STRINGS_MAP.withExistingValue(groups));
		Map<String, String> levels = binder.bind(LOGGING_LEVEL, STRING_STRING_MAP).orElseGet(Collections::emptyMap);
		levels.forEach((name, level) -> {
			String[] groupedNames = groups.get(name);
			if (ObjectUtils.isEmpty(groupedNames)) {
				setLogLevel(system, name, level);
			}
			else {
				setLogLevel(system, groupedNames, level);
			}
		});
	}

	private Map<String, String[]> getGroups() {
		Map<String, String[]> groups = new LinkedHashMap<>();
		DEFAULT_GROUP_LOGGERS.forEach((name, loggers) -> groups.put(name, StringUtils.toStringArray(loggers)));
		return groups;
	}

	private void setLogLevel(LoggingSystem system, String[] names, String level) {
		for (String name : names) {
			setLogLevel(system, name, level);
		}
	}

	private void setLogLevel(LoggingSystem system, String name, String level) {
		try {
			name = name.equalsIgnoreCase(LoggingSystem.ROOT_LOGGER_NAME) ? null : name;
			system.setLogLevel(name, coerceLogLevel(level));
		}
		catch (RuntimeException ex) {
			this.logger.error("Cannot set level '" + level + "' for '" + name + "'");
		}
	}

	private LogLevel coerceLogLevel(String level) {
		String trimmedLevel = level.trim();
		if ("false".equalsIgnoreCase(trimmedLevel)) {
			return LogLevel.OFF;
		}
		return LogLevel.valueOf(trimmedLevel.toUpperCase(Locale.ENGLISH));
	}

	private void registerShutdownHookIfNecessary(Environment environment, LoggingSystem loggingSystem) {
		boolean registerShutdownHook = environment.getProperty(REGISTER_SHUTDOWN_HOOK_PROPERTY, Boolean.class, false);
		if (registerShutdownHook) {
			Runnable shutdownHandler = loggingSystem.getShutdownHandler();
			if (shutdownHandler != null && shutdownHookRegistered.compareAndSet(false, true)) {
				registerShutdownHook(new Thread(shutdownHandler));
			}
		}
	}

	void registerShutdownHook(Thread shutdownHook) {
		Runtime.getRuntime().addShutdownHook(shutdownHook);
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	/**
	 * Sets a custom logging level to be used for Spring Boot and related libraries.
	 * @param springBootLogging the logging level
	 */
	public void setSpringBootLogging(LogLevel springBootLogging) {
		this.springBootLogging = springBootLogging;
	}

	/**
	 * Sets if initialization arguments should be parsed for {@literal debug} and
	 * {@literal trace} properties (usually defined from {@literal --debug} or
	 * {@literal --trace} command line args). Defaults to {@code true}.
	 * @param parseArgs if arguments should be parsed
	 */
	public void setParseArgs(boolean parseArgs) {
		this.parseArgs = parseArgs;
	}

}
