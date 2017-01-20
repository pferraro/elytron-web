/*
 * JBoss, Home of Professional Open Source
 *
 * Copyright 2016 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.elytron.web.undertow.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.wildfly.security.password.interfaces.ClearPassword.ALGORITHM_CLEAR;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.undertow.server.session.InMemorySessionManager;
import io.undertow.server.session.SessionManager;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.junit.Rule;
import org.junit.Test;
import org.wildfly.elytron.web.undertow.server.util.UndertowServer;
import org.wildfly.security.auth.permission.LoginPermission;
import org.wildfly.security.auth.realm.SimpleMapBackedSecurityRealm;
import org.wildfly.security.auth.realm.SimpleRealmEntry;
import org.wildfly.security.auth.server.SecurityDomain;
import org.wildfly.security.credential.PasswordCredential;
import org.wildfly.security.http.HttpServerAuthenticationMechanismFactory;
import org.wildfly.security.http.util.sso.DefaultSingleSignOnManager;
import org.wildfly.security.http.util.sso.DefaultSingleSignOnSessionFactory;
import org.wildfly.security.http.util.sso.DefaultSingleSignOnSessionIdentifierFactory;
import org.wildfly.security.http.util.sso.SingleSignOnServerMechanismFactory;
import org.wildfly.security.http.util.sso.SingleSignOnEntry;
import org.wildfly.security.http.util.sso.SingleSignOnManager;
import org.wildfly.security.http.util.sso.SingleSignOnSessionFactory;
import org.wildfly.security.password.PasswordFactory;
import org.wildfly.security.password.spec.ClearPasswordSpec;
import org.wildfly.security.permission.PermissionVerifier;

/**
 * Test case to test HTTP FORM authentication where authentication is backed by Elytron and session replication is enabled.
 *
 * @author <a href="mailto:psilva@redhat.com">Pedro Igor</a>
 * @author Paul Ferraro
 */
public class FormAuthenticationWithClusteredSSOTest extends AbstractHttpServerMechanismTest {

    private final Map<Integer, SessionManager> sessionManagers = new HashMap<>();

    @Rule
    public UndertowServer serverA = createUndertowServer(7776);

    @Rule
    public UndertowServer serverB = createUndertowServer(7777);

    @Rule
    public UndertowServer serverC = createUndertowServer(7778);

    @Rule
    public UndertowServer serverD = createUndertowServer(7779);

    @Rule
    public UndertowServer serverE = createUndertowServer(7780);

    private UndertowServer createUndertowServer(int port) {
        InMemorySessionManager sessionManager = new InMemorySessionManager(String.valueOf(port));

        sessionManagers.put(port, sessionManager);

        return new UndertowServer(createRootHttpHandler(sessionManager), port, sessionManager.getDeploymentName());
    }

    @Test
    public void testSingleSignOn() throws Exception {
        BasicCookieStore cookieStore = new BasicCookieStore();
        HttpClient httpClient = HttpClientBuilder.create().setDefaultCookieStore(cookieStore).build();

        assertLoginPage(httpClient.execute(new HttpGet(serverA.createUri())));

        assertFalse(cookieStore.getCookies().stream().filter(cookie -> cookie.getName().equals("JSESSIONSSOID")).findAny().isPresent());

        // authenticate on NODE_A
        HttpPost httpAuthenticate = new HttpPost(serverA.createUri("/j_security_check"));
        List<NameValuePair> parameters = new ArrayList<>(2);

        parameters.add(new BasicNameValuePair("j_username", "ladybird"));
        parameters.add(new BasicNameValuePair("j_password", "Coleoptera"));

        httpAuthenticate.setEntity(new UrlEncodedFormEntity(parameters));

        HttpResponse execute = httpClient.execute(httpAuthenticate);

        assertTrue(cookieStore.getCookies().stream().filter(cookie -> cookie.getName().equals("JSESSIONSSOID")).findAny().isPresent());
        assertSuccessfulResponse(execute, "ladybird");
        assertSuccessfulResponse(httpClient.execute(new HttpGet(serverB.createUri())), "ladybird");
        assertSuccessfulResponse(httpClient.execute(new HttpGet(serverC.createUri())), "ladybird");
        assertSuccessfulResponse(httpClient.execute(new HttpGet(serverD.createUri())), "ladybird");
        assertSuccessfulResponse(httpClient.execute(new HttpGet(serverE.createUri())), "ladybird");
    }

    @Test
    public void testSingleLogout() throws Exception {
        BasicCookieStore cookieStore = new BasicCookieStore();
        HttpClient httpClient = HttpClientBuilder.create().setDefaultCookieStore(cookieStore).build();

        assertLoginPage(httpClient.execute(new HttpGet(serverA.createUri())));

        // authenticate on NODE_A
        HttpPost httpAuthenticate = new HttpPost(serverA.createUri("/j_security_check"));
        List<NameValuePair> parameters = new ArrayList<>();

        parameters.add(new BasicNameValuePair("j_username", "ladybird"));
        parameters.add(new BasicNameValuePair("j_password", "Coleoptera"));

        httpAuthenticate.setEntity(new UrlEncodedFormEntity(parameters));

        HttpResponse execute = httpClient.execute(httpAuthenticate);

        assertSuccessfulResponse(execute, "ladybird");
        assertSuccessfulResponse(httpClient.execute(new HttpGet(serverB.createUri())), "ladybird");
        assertSuccessfulResponse(httpClient.execute(new HttpGet(serverC.createUri())), "ladybird");
        assertSuccessfulResponse(httpClient.execute(new HttpGet(serverD.createUri())), "ladybird");
        assertSuccessfulResponse(httpClient.execute(new HttpGet(serverE.createUri())), "ladybird");

        httpClient.execute(new HttpGet(serverB.createUri("/logout")));

        assertLoginPage(httpClient.execute(new HttpGet(serverC.createUri())));
        assertLoginPage(httpClient.execute(new HttpGet(serverA.createUri())));
        assertLoginPage(httpClient.execute(new HttpGet(serverB.createUri())));
        assertLoginPage(httpClient.execute(new HttpGet(serverD.createUri())));
        assertLoginPage(httpClient.execute(new HttpGet(serverE.createUri())));
    }

    @Test
    public void testSingleLogoutWhenNodeIsFailing() throws Exception {
        BasicCookieStore cookieStore = new BasicCookieStore();
        HttpClient httpClient = HttpClientBuilder.create().setDefaultCookieStore(cookieStore).build();

        assertLoginPage(httpClient.execute(new HttpGet(serverA.createUri())));

        // authenticate on NODE_A
        HttpPost httpAuthenticate = new HttpPost(serverA.createUri("/j_security_check"));
        List<NameValuePair> parameters = new ArrayList<>(2);

        parameters.add(new BasicNameValuePair("j_username", "ladybird"));
        parameters.add(new BasicNameValuePair("j_password", "Coleoptera"));

        httpAuthenticate.setEntity(new UrlEncodedFormEntity(parameters));

        HttpResponse execute = httpClient.execute(httpAuthenticate);

        assertSuccessfulResponse(execute, "ladybird");
        assertSuccessfulResponse(httpClient.execute(new HttpGet(serverB.createUri())), "ladybird");
        assertSuccessfulResponse(httpClient.execute(new HttpGet(serverC.createUri())), "ladybird");
        assertSuccessfulResponse(httpClient.execute(new HttpGet(serverD.createUri())), "ladybird");
        assertSuccessfulResponse(httpClient.execute(new HttpGet(serverE.createUri())), "ladybird");

        serverC.forceShutdown();
        serverE.forceShutdown();

        httpClient.execute(new HttpGet(serverB.createUri("/logout")));

        assertLoginPage(httpClient.execute(new HttpGet(serverA.createUri())));
        assertLoginPage(httpClient.execute(new HttpGet(serverB.createUri())));
        assertLoginPage(httpClient.execute(new HttpGet(serverD.createUri())));
    }

    @Test
    public void testSessionInvalidation() throws Exception {
        BasicCookieStore cookieStore = new BasicCookieStore();
        HttpClient httpClient = HttpClientBuilder.create().setDefaultCookieStore(cookieStore).build();

        assertLoginPage(httpClient.execute(new HttpGet(serverA.createUri())));

        for (int i = 0; i < 10; i++) {
            HttpPost httpAuthenticate = new HttpPost(serverA.createUri("/j_security_check"));
            List<NameValuePair> parameters = new ArrayList<>(2);

            parameters.add(new BasicNameValuePair("j_username", "ladybird"));
            parameters.add(new BasicNameValuePair("j_password", "Coleoptera"));

            httpAuthenticate.setEntity(new UrlEncodedFormEntity(parameters));

            HttpResponse execute = httpClient.execute(httpAuthenticate);

            assertSuccessfulResponse(execute, "ladybird");
            assertSuccessfulResponse(httpClient.execute(new HttpGet(serverA.createUri())), "ladybird");
            assertSuccessfulResponse(httpClient.execute(new HttpGet(serverB.createUri())), "ladybird");
            assertSuccessfulResponse(httpClient.execute(new HttpGet(serverC.createUri())), "ladybird");
            assertSuccessfulResponse(httpClient.execute(new HttpGet(serverD.createUri())), "ladybird");
            assertSuccessfulResponse(httpClient.execute(new HttpGet(serverE.createUri())), "ladybird");

            httpClient.execute(new HttpGet(serverA.createUri("/logout")));

            assertLoginPage(httpClient.execute(new HttpGet(serverC.createUri())));
            assertLoginPage(httpClient.execute(new HttpGet(serverA.createUri())));
            assertLoginPage(httpClient.execute(new HttpGet(serverB.createUri())));
            assertLoginPage(httpClient.execute(new HttpGet(serverD.createUri())));
            assertLoginPage(httpClient.execute(new HttpGet(serverE.createUri())));
        }

        this.sessionManagers.values().forEach(manager -> assertEquals(manager.getDeploymentName(), 1, manager.getActiveSessions().size()));
    }

    @Override
    protected String getMechanismName() {
        return "FORM";
    }

    @Override
    protected SecurityDomain doCreateSecurityDomain() throws Exception {
        PasswordFactory passwordFactory = PasswordFactory.getInstance(ALGORITHM_CLEAR);
        Map<String, SimpleRealmEntry> passwordMap = new HashMap<>();

        passwordMap.put("ladybird", new SimpleRealmEntry(Collections.singletonList(new PasswordCredential(passwordFactory.generatePassword(new ClearPasswordSpec("Coleoptera".toCharArray()))))));

        SimpleMapBackedSecurityRealm securityRealm = new SimpleMapBackedSecurityRealm();

        securityRealm.setPasswordMap(passwordMap);

        SecurityDomain.Builder builder = SecurityDomain.builder()
                .setDefaultRealmName("TestRealm");

        builder.addRealm("TestRealm", securityRealm).build();
        builder.setPermissionMapper((principal, roles) -> PermissionVerifier.from(new LoginPermission()));

        return builder.build();
    }

    @Override
    protected HttpServerAuthenticationMechanismFactory doCreateHttpServerMechanismFactory(Map<String, ?> properties) {
        HttpServerAuthenticationMechanismFactory delegate = super.doCreateHttpServerMechanismFactory(properties);

        String cacheManagerName = UUID.randomUUID().toString();
        EmbeddedCacheManager cacheManager = new DefaultCacheManager(
                GlobalConfigurationBuilder.defaultClusteredBuilder()
                        .globalJmxStatistics().cacheManagerName(cacheManagerName)
                        .transport().nodeName(cacheManagerName).addProperty(JGroupsTransport.CONFIGURATION_FILE, "fast.xml")
                        .build(),
                new ConfigurationBuilder()
                        .clustering()
                        .cacheMode(CacheMode.REPL_SYNC)
                        .build()
        );

        Cache<String, SingleSignOnEntry> cache = cacheManager.getCache();
        SingleSignOnManager manager = new DefaultSingleSignOnManager(cache, new DefaultSingleSignOnSessionIdentifierFactory(), (id, entry) -> cache.put(id, entry));
        SingleSignOnServerMechanismFactory.SingleSignOnConfiguration signOnConfiguration = new SingleSignOnServerMechanismFactory.SingleSignOnConfiguration("JSESSIONSSOID", null, "/", false, false);

        String alias = "server";
        char[] password = "password".toCharArray();
        try {
            KeyStore store = loadKeyStore("/tls/server.keystore");
            assertTrue(store.containsAlias(alias));
            assertTrue(store.entryInstanceOf(alias, KeyStore.PrivateKeyEntry.class));
            KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) store.getEntry(alias, new KeyStore.PasswordProtection(password));
            KeyPair keyPair = new KeyPair(entry.getCertificate().getPublicKey(), entry.getPrivateKey());

            SingleSignOnSessionFactory singleSignOnSessionFactory = new DefaultSingleSignOnSessionFactory(manager, keyPair);

            return new SingleSignOnServerMechanismFactory(delegate, singleSignOnSessionFactory, signOnConfiguration);
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyStore loadKeyStore(final String path) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("jks");
        try (InputStream caTrustStoreFile = ClientCertAuthenticationTest.class.getResourceAsStream(path)) {
            store.load(caTrustStoreFile, "password".toCharArray());
        }
        return store;
    }
}
