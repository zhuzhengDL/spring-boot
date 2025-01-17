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

package org.springframework.boot;

import java.lang.reflect.Method;

import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.util.ReflectionUtils;

/** 在上下文加载后清理缓存。
 *
 * {@link ApplicationListener} to cleanup caches once the context is loaded.
 *
 * @author Phillip Webb
 */
class ClearCachesApplicationListener implements ApplicationListener<ContextRefreshedEvent> {

	@Override
	public void onApplicationEvent(ContextRefreshedEvent event) {
		// 清空 ReflectionUtils 缓存
		ReflectionUtils.clearCache();
		// 清空类加载器的缓存
		clearClassLoaderCaches(Thread.currentThread().getContextClassLoader());
	}

	private void clearClassLoaderCaches(ClassLoader classLoader) {
		if (classLoader == null) {
			return;
		}
		try {
			// 同构反射调用 ClassLoader 类的 clearCache 方法，清空它的缓存
			Method clearCacheMethod = classLoader.getClass().getDeclaredMethod("clearCache");
			clearCacheMethod.invoke(classLoader);
		}
		catch (Exception ex) {
			// Ignore
		}
		// 如果有父加载器，则父加载器清空缓存
		clearClassLoaderCaches(classLoader.getParent());
	}

}
