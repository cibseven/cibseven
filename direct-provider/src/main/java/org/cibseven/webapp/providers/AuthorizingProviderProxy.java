/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.cibseven.webapp.providers;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.cibseven.webapp.auth.CIBUser;

/**
 * Dynamic proxy that wraps a {@code Direct*Provider} so every call carrying a {@link CIBUser} argument
 * runs with that user authenticated on the engine (see {@link DirectProviderUtil#runAsUser}).
 *
 * <p>This makes the engine enforce its own authorizations for Direct (in-process) calls, matching the
 * behaviour of the engine REST API's {@code ProcessEngineAuthenticationFilter}.
 *
 * <p>Methods that take no {@link CIBUser} argument (engine discovery, setup, password-policy, ...) run
 * run without a user.
 */
public class AuthorizingProviderProxy implements InvocationHandler {

	private final Object target;
	private final DirectProviderUtil directProviderUtil;

	private AuthorizingProviderProxy(Object target, DirectProviderUtil directProviderUtil) {
		this.target = target;
		this.directProviderUtil = directProviderUtil;
	}

	/**
	 * Wraps {@code target} in a proxy implementing {@code iface}, authenticating the {@link CIBUser} found in
	 * each call's arguments before delegating to {@code target}.
	 */
	@SuppressWarnings("unchecked")
	public static <T> T wrap(T target, Class<T> iface, DirectProviderUtil directProviderUtil) {
		return (T) Proxy.newProxyInstance(
				iface.getClassLoader(),
				new Class<?>[] { iface },
				new AuthorizingProviderProxy(target, directProviderUtil));
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		CIBUser user = findUser(args);
		if (user == null) {
			return invokeTarget(method, args);
		}
		try {
			return directProviderUtil.runAsUser(user, () -> {
				try {
					return invokeTarget(method, args);
				} catch (Throwable t) {
					throw new InvocationCarrier(t);
				}
			});
		} catch (InvocationCarrier carrier) {
			// re-throw the provider's original throwable so callers see the same exceptions as before
			throw carrier.getCause();
		}
	}

	private Object invokeTarget(Method method, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}

	private CIBUser findUser(Object[] args) {
		if (args == null) {
			return null;
		}
		for (Object arg : args) {
			if (arg instanceof CIBUser) {
				return (CIBUser) arg;
			}
		}
		return null;
	}

	/**
	 * Carries the target's throwable across the {@link java.util.function.Supplier} boundary of
	 * {@link DirectProviderUtil#runAsUser}, which cannot itself declare checked exceptions. Unwrapped in
	 * {@link #invoke} so the original throwable reaches the caller and {@code runAsUser}'s {@code finally}
	 * still restores the previous authentication.
	 */
	private static class InvocationCarrier extends RuntimeException {
		private static final long serialVersionUID = 1L;

		InvocationCarrier(Throwable cause) {
			super(cause);
		}
	}
}
