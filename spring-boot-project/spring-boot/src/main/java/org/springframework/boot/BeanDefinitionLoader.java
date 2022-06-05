/*
 * Copyright 2012-2021 the original author or authors.
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

package org.springframework.boot;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import groovy.lang.Closure;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.groovy.GroovyBeanDefinitionReader;
import org.springframework.beans.factory.support.AbstractBeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.SpringProperties;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.filter.AbstractTypeHierarchyTraversingFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Loads bean definitions from underlying sources, including XML and JavaConfig. Acts as a
 * simple facade over {@link AnnotatedBeanDefinitionReader},
 * {@link XmlBeanDefinitionReader} and {@link ClassPathBeanDefinitionScanner}. See
 * {@link SpringApplication} for the types of sources that are supported.
 *
 * @author Phillip Webb
 * @author Vladislav Kisel
 * @author Sebastien Deleuze
 * @see #setBeanNameGenerator(BeanNameGenerator)
 */
class BeanDefinitionLoader {

	// Static final field to facilitate code removal by Graal
	private static final boolean XML_ENABLED = !SpringProperties.getFlag("spring.xml.ignore");

	private static final Pattern GROOVY_CLOSURE_PATTERN = Pattern.compile(".*\\$_.*closure.*");
	/**
	 * 来源的数组
	 */
	private final Object[] sources;
	/**
	 * 注解的 BeanDefinition 读取器
	 */
	private final AnnotatedBeanDefinitionReader annotatedReader;
	/**
	 * XML 的 BeanDefinition 读取器
	 */
	private final AbstractBeanDefinitionReader xmlReader;
	/**
	 * Groovy 的 BeanDefinition 读取器
	 */
	private final BeanDefinitionReader groovyReader;
	/**
	 * Classpath 的 BeanDefinition 扫描器
	 */
	private final ClassPathBeanDefinitionScanner scanner;
	/**
	 * 资源加载器
	 */
	private ResourceLoader resourceLoader;

	/**
	 * Create a new {@link BeanDefinitionLoader} that will load beans into the specified
	 * {@link BeanDefinitionRegistry}.
	 * @param registry the bean definition registry that will contain the loaded beans
	 * @param sources the bean sources
	 */
	BeanDefinitionLoader(BeanDefinitionRegistry registry, Object... sources) {
		Assert.notNull(registry, "Registry must not be null");
		Assert.notEmpty(sources, "Sources must not be empty");
		// <1>
		this.sources = sources;
		// 创建 AnnotatedBeanDefinitionReader 对象
		this.annotatedReader = new AnnotatedBeanDefinitionReader(registry);
		// 创建 XmlBeanDefinitionReader 对象
		this.xmlReader = (XML_ENABLED ? new XmlBeanDefinitionReader(registry) : null);
		// 创建 GroovyBeanDefinitionReader 对象
		this.groovyReader = (isGroovyPresent() ? new GroovyBeanDefinitionReader(registry) : null);
		// 创建 ClassPathBeanDefinitionScanner 对象
		this.scanner = new ClassPathBeanDefinitionScanner(registry);
		this.scanner.addExcludeFilter(new ClassExcludeFilter(sources));
	}

	/**
	 * Set the bean name generator to be used by the underlying readers and scanner.
	 * @param beanNameGenerator the bean name generator
	 */
	void setBeanNameGenerator(BeanNameGenerator beanNameGenerator) {
		this.annotatedReader.setBeanNameGenerator(beanNameGenerator);
		this.scanner.setBeanNameGenerator(beanNameGenerator);
		if (this.xmlReader != null) {
			this.xmlReader.setBeanNameGenerator(beanNameGenerator);
		}
	}

	/**
	 * Set the resource loader to be used by the underlying readers and scanner.
	 * @param resourceLoader the resource loader
	 */
	void setResourceLoader(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
		this.scanner.setResourceLoader(resourceLoader);
		if (this.xmlReader != null) {
			this.xmlReader.setResourceLoader(resourceLoader);
		}
	}

	/**
	 * Set the environment to be used by the underlying readers and scanner.
	 * @param environment the environment
	 */
	void setEnvironment(ConfigurableEnvironment environment) {
		this.annotatedReader.setEnvironment(environment);
		this.scanner.setEnvironment(environment);
		if (this.xmlReader != null) {
			this.xmlReader.setEnvironment(environment);
		}
	}

	/**
	 * Load the sources into the reader.
	 */
	void load() {
		//  遍历 sources 数组，逐个加载
		for (Object source : this.sources) {
			load(source);
		}
	}

	private void load(Object source) {
		Assert.notNull(source, "Source must not be null");
		// <1> 如果是 Class 类型，则使用 AnnotatedBeanDefinitionReader 执行加载
		if (source instanceof Class<?>) {
			load((Class<?>) source);
			return;
		}
		// <2> 如果是 Resource 类型，则使用 XmlBeanDefinitionReader 执行加载
		if (source instanceof Resource) {
			load((Resource) source);
			return;
		}
		// <3> 如果是 Package 类型，则使用 ClassPathBeanDefinitionScanner 执行加载
		if (source instanceof Package) {
			load((Package) source);
			return;
		}
		// <4> 如果是 CharSequence 类型，则各种尝试去加载
		if (source instanceof CharSequence) {
			load((CharSequence) source);
			return;
		}
		// <5> 无法处理的类型，抛出 IllegalArgumentException 异常
		throw new IllegalArgumentException("Invalid source type " + source.getClass());
	}

	private void load(Class<?> source) {
		// Groovy 相关，暂时忽略
		if (isGroovyPresent() && GroovyBeanDefinitionSource.class.isAssignableFrom(source)) {
			// Any GroovyLoaders added in beans{} DSL can contribute beans here
			GroovyBeanDefinitionSource loader = BeanUtils.instantiateClass(source, GroovyBeanDefinitionSource.class);
			((GroovyBeanDefinitionReader) this.groovyReader).beans(loader.getBeans());
		}
		//检查 bean 是否符合注册条件。
		if (isEligible(source)) {
			this.annotatedReader.register(source);//<2>注册启动配置类
		}
	}

	private void load(Resource source) {
		// Groovy 相关，暂时忽略
		if (source.getFilename().endsWith(".groovy")) {
			if (this.groovyReader == null) {
				throw new BeanDefinitionStoreException("Cannot load Groovy beans without Groovy on classpath");
			}
			this.groovyReader.loadBeanDefinitions(source);
		}
		else {
			if (this.xmlReader == null) {
				throw new BeanDefinitionStoreException("Cannot load XML bean definitions when XML support is disabled");
			}
			// 使用 XmlBeanDefinitionReader 加载 BeanDefinition
			this.xmlReader.loadBeanDefinitions(source);
		}
	}

	private void load(Package source) {
		this.scanner.scan(source.getName());
	}

	private void load(CharSequence source) {
		// <1> 解析 source 。因为，有可能里面带有占位符。
		String resolvedSource = this.scanner.getEnvironment().resolvePlaceholders(source.toString());
		// Attempt as a Class
		try {
			// <2> 尝试按照 Class 进行加载
			load(ClassUtils.forName(resolvedSource, null));
			return;
		}
		catch (IllegalArgumentException | ClassNotFoundException ex) {
			// swallow exception and continue
		}
		// <3> 尝试按照 Resource 进行加载
		// Attempt as Resources
		if (loadAsResources(resolvedSource)) {
			return;
		}
		// <4> 尝试按照 Package 进行加载
		// Attempt as package
		Package packageResource = findPackage(resolvedSource);
		if (packageResource != null) {
			load(packageResource);
			return;
		}
		// <5> 无法处理，抛出 IllegalArgumentException 异常
		throw new IllegalArgumentException("Invalid source '" + resolvedSource + "'");
	}

	private boolean loadAsResources(String resolvedSource) {
		boolean foundCandidate = false;
		Resource[] resources = findResources(resolvedSource);
		for (Resource resource : resources) {
			if (isLoadCandidate(resource)) {
				foundCandidate = true;
				load(resource);
			}
		}
		return foundCandidate;
	}

	private boolean isGroovyPresent() {
		return ClassUtils.isPresent("groovy.lang.MetaClass", null);
	}

	private Resource[] findResources(String source) {
		ResourceLoader loader = (this.resourceLoader != null) ? this.resourceLoader
				: new PathMatchingResourcePatternResolver();
		try {
			if (loader instanceof ResourcePatternResolver) {
				return ((ResourcePatternResolver) loader).getResources(source);
			}
			return new Resource[] { loader.getResource(source) };
		}
		catch (IOException ex) {
			throw new IllegalStateException("Error reading source '" + source + "'");
		}
	}

	private boolean isLoadCandidate(Resource resource) {
		if (resource == null || !resource.exists()) {
			return false;
		}
		if (resource instanceof ClassPathResource) {
			// A simple package without a '.' may accidentally get loaded as an XML
			// document if we're not careful. The result of getInputStream() will be
			// a file list of the package content. We double check here that it's not
			// actually a package.
			String path = ((ClassPathResource) resource).getPath();
			if (path.indexOf('.') == -1) {
				try {
					return Package.getPackage(path) == null;
				}
				catch (Exception ex) {
					// Ignore
				}
			}
		}
		return true;
	}

	private Package findPackage(CharSequence source) {
		// <X> 获得 source 对应的 Package 。如果存在，则返回
		Package pkg = Package.getPackage(source.toString());
		if (pkg != null) {
			return pkg;
		}
		try {
			// Attempt to find a class in this package
			// 创建 ResourcePatternResolver 对象
			ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(getClass().getClassLoader());
			// 尝试加载 source 目录下的 class 们
			Resource[] resources = resolver
					.getResources(ClassUtils.convertClassNameToResourcePath(source.toString()) + "/*.class");
			// 遍历 resources 数组
			for (Resource resource : resources) {
				// 获得类名
				String className = StringUtils.stripFilenameExtension(resource.getFilename());
				// 按照 Class 进行加载 BeanDefinition
				load(Class.forName(source.toString() + "." + className));
				break;
			}
		}
		catch (Exception ex) {
			// swallow exception and continue
		}
		// 返回 Package
		return Package.getPackage(source.toString());
	}

	/** 检查 bean 是否符合注册条件。
	 * Check whether the bean is eligible for registration.
	 * @param type candidate bean type
	 * @return true if the given bean type is eligible for registration, i.e. not a groovy
	 * closure nor an anonymous class
	 */
	private boolean isEligible(Class<?> type) {
		return !(type.isAnonymousClass() || isGroovyClosure(type) || hasNoConstructors(type));
	}

	private boolean isGroovyClosure(Class<?> type) {
		return GROOVY_CLOSURE_PATTERN.matcher(type.getName()).matches();
	}

	private boolean hasNoConstructors(Class<?> type) {
		Constructor<?>[] constructors = type.getDeclaredConstructors();
		return ObjectUtils.isEmpty(constructors);
	}

	/**
	 * Simple {@link TypeFilter} used to ensure that specified {@link Class} sources are
	 * not accidentally re-added during scanning.
	 */
	private static class ClassExcludeFilter extends AbstractTypeHierarchyTraversingFilter {

		private final Set<String> classNames = new HashSet<>();

		ClassExcludeFilter(Object... sources) {
			super(false, false);
			for (Object source : sources) {
				if (source instanceof Class<?>) {
					this.classNames.add(((Class<?>) source).getName());
				}
			}
		}

		@Override
		protected boolean matchClassName(String className) {
			return this.classNames.contains(className);
		}

	}

	/**
	 * Source for Bean definitions defined in Groovy.
	 */
	@FunctionalInterface
	protected interface GroovyBeanDefinitionSource {

		Closure<?> getBeans();

	}

}
