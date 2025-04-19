package org.cibseven.bpm.engine.rest.security.auth.impl;

import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.rest.security.auth.AuthenticationProvider;
import org.cibseven.bpm.engine.rest.security.auth.AuthenticationResult;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * CompositeAuthenticationProvider tries to authenticate using the first
 * provider.
 * If the first provider fails, it falls back to the second provider.
 */
public class CompositeAuthenticationProvider implements AuthenticationProvider {

    private final AuthenticationProvider primaryProvider;
    private final AuthenticationProvider fallbackProvider;

    public CompositeAuthenticationProvider(AuthenticationProvider primaryProvider,
            AuthenticationProvider fallbackProvider) {
        this.primaryProvider = primaryProvider;
        this.fallbackProvider = fallbackProvider;
    }

    @Override
    public AuthenticationResult extractAuthenticatedUser(HttpServletRequest request, ProcessEngine engine) {
        // Try primary provider
        AuthenticationResult result = primaryProvider.extractAuthenticatedUser(request, engine);
        if (result.isAuthenticated()) {
            return result;
        }

        // Fallback to secondary provider
        return fallbackProvider.extractAuthenticatedUser(request, engine);
    }

    @Override
    public void augmentResponseByAuthenticationChallenge(HttpServletResponse response, ProcessEngine engine) {
        // Delegate to both providers
        primaryProvider.augmentResponseByAuthenticationChallenge(response, engine);
        fallbackProvider.augmentResponseByAuthenticationChallenge(response, engine);
    }
}
