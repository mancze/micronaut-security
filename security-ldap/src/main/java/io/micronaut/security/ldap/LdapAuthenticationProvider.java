/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.security.ldap;

import io.micronaut.security.ldap.configuration.LdapConfiguration;
import io.micronaut.security.ldap.context.ContextBuilder;
import io.micronaut.security.ldap.context.LdapSearchResult;
import io.micronaut.security.ldap.context.LdapSearchService;
import io.micronaut.security.ldap.group.LdapGroupProcessor;
import io.micronaut.http.HttpRequest;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.security.authentication.AuthenticationProvider;
import io.micronaut.security.authentication.AuthenticationResponse;
import io.micronaut.security.authentication.AuthenticationRequest;
import io.micronaut.security.authentication.AuthenticationException;
import io.micronaut.security.authentication.AuthenticationFailureReason;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Flux;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import jakarta.inject.Named;
import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import java.io.Closeable;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Authenticates against an LDAP server using the configuration provided through
 * {@link LdapConfiguration}. One provider will be created for each configuration.
 *
 * @author James Kleeh
 * @since 1.0
 */
public class LdapAuthenticationProvider implements AuthenticationProvider, Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(LdapAuthenticationProvider.class);

    private final LdapConfiguration configuration;
    private final LdapSearchService ldapSearchService;
    private final ContextBuilder contextBuilder;
    private final ContextAuthenticationMapper contextAuthenticationMapper;
    private final LdapGroupProcessor ldapGroupProcessor;
    private final Scheduler scheduler;

    /**
     * @param configuration               The configuration to use to authenticate
     * @param ldapSearchService           The search service
     * @param contextBuilder              The context builder
     * @param contextAuthenticationMapper The authentication mapper
     * @param ldapGroupProcessor          The group processor
     * @param executorService             Executor Service
     */
    public LdapAuthenticationProvider(LdapConfiguration configuration,
                                      LdapSearchService ldapSearchService,
                                      ContextBuilder contextBuilder,
                                      ContextAuthenticationMapper contextAuthenticationMapper,
                                      LdapGroupProcessor ldapGroupProcessor,
                                      @Named(TaskExecutors.IO) ExecutorService executorService) {
        this.configuration = configuration;
        this.ldapSearchService = ldapSearchService;
        this.contextBuilder = contextBuilder;
        this.contextAuthenticationMapper = contextAuthenticationMapper;
        this.ldapGroupProcessor = ldapGroupProcessor;
        this.scheduler = Schedulers.fromExecutorService(executorService);
    }

    @Override
    public Publisher<AuthenticationResponse> authenticate(HttpRequest<?> httpRequest, AuthenticationRequest<?, ?> authenticationRequest) {
        Flux<AuthenticationResponse> reactiveSequence = Flux.create(emitter -> {
            String username = authenticationRequest.getIdentity().toString();
            String password = authenticationRequest.getSecret().toString();

            if (LOG.isDebugEnabled()) {
                LOG.debug("Starting authentication with configuration [{}]", configuration.getName());
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("Attempting to initialize manager context");
            }
            DirContext managerContext;
            try {
                managerContext = contextBuilder.build(configuration.getManagerSettings());
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Manager context initialized successfully");
                }
            } catch (NamingException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Failed to create manager context. Returning unknown authentication failure. Encountered {}", e.getMessage());
                }
                emitter.error(AuthenticationResponse.exception(AuthenticationFailureReason.UNKNOWN));
                return;
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("Attempting to authenticate with user [{}]", username);
            }

            try {
                Optional<LdapSearchResult> optionalResult = ldapSearchService.searchFirst(managerContext, configuration.getSearch().getSettings(new Object[]{username}));

                if (optionalResult.isPresent()) {
                    LdapSearchResult result = optionalResult.get();
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("User found in context [{}]. Attempting to bind.", result.getDn());
                    }

                    DirContext userContext = null;
                    try {
                        String dn = result.getDn();
                        userContext = contextBuilder.build(configuration.getSettings(result.getDn(), password));
                        if (result.getAttributes() == null) {
                            result.setAttributes(userContext.getAttributes(dn));
                        }
                    } finally {
                        contextBuilder.close(userContext);
                    }

                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Successfully bound user [{}]. Attempting to retrieving groups.", result.getDn());
                    }

                    Set<String> groups = Collections.emptySet();

                    LdapConfiguration.GroupConfiguration groupSettings = configuration.getGroups();
                    if (groupSettings.isEnabled()) {
                        groups = ldapGroupProcessor.process(groupSettings.getAttribute(), result, () -> {
                                    Object[] params = new Object[]{
                                            groupSettings.getFilterAttribute()
                                                    .map(attr -> result.getAttributes().getValue(attr))
                                                    .orElse(result.getDn())
                                    };
                                    return ldapSearchService.search(managerContext, groupSettings.getSearchSettings(params));
                                });


                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Group search returned [{}] for user [{}]", groups, username);
                        }
                    } else {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Group search is disabled for configuration [{}]", configuration.getName());
                        }
                    }

                    if (LOG.isTraceEnabled()) {
                        LOG.trace("Attempting to map [{}] with groups [{}] to an authentication response.", username, groups);
                    }

                    AuthenticationResponse response = contextAuthenticationMapper.map(result.getAttributes(), username, groups);
                    if (response.isAuthenticated()) {
                        emitter.next(response);
                        emitter.complete();
                    } else {
                        emitter.error(new AuthenticationException(response));
                    }

                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Response successfully created for [{}]. Response is authenticated: [{}]", username, response.isAuthenticated());
                    }
                } else {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("User not found [{}]", username);
                    }
                    emitter.error(AuthenticationResponse.exception(AuthenticationFailureReason.USER_NOT_FOUND));
                }
            } catch (NamingException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Failed to authenticate with user [{}].  {}", username, e);
                }
                if (e instanceof javax.naming.AuthenticationException) {
                    emitter.error(AuthenticationResponse.exception(AuthenticationFailureReason.CREDENTIALS_DO_NOT_MATCH));
                } else {
                    emitter.error(e);
                }
            } finally {
                contextBuilder.close(managerContext);
            }
        }, FluxSink.OverflowStrategy.ERROR);
        reactiveSequence = reactiveSequence.subscribeOn(scheduler);
        return reactiveSequence;
    }

    @Override
    public void close() { }
}
