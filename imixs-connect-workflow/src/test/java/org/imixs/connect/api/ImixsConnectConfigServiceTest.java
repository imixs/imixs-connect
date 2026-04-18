/*******************************************************************************
 * Copyright (c) 2025 Imixs Software Solutions GmbH and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
 *******************************************************************************/
package org.imixs.connect.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/**
 * Unit tests for {@link ImixsConnectConfigService}.
 * <p>
 * The tests load a pre-defined test configuration from
 * <code>imixs-connect-test.xml</code> on the classpath and inject it directly
 * into the service via
 * {@link ImixsConnectConfigService#setConfigDocument(Document)}. No application
 * server or MicroProfile runtime is required.
 */
class ImixsConnectConfigServiceTest {

    private ImixsConnectConfigService configService;

    /**
     * Loads the test XML config and injects it into a fresh service instance before
     * each test.
     */
    @BeforeEach
    void setUp() throws Exception {
        configService = new ImixsConnectConfigService();

        // Load test config from classpath
        InputStream is = getClass().getResourceAsStream("/imixs-connect-test.xml");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(is);
        doc.getDocumentElement().normalize();

        // Inject directly - bypasses @PostConstruct / filesystem
        configService.setConfigDocument(doc);
    }

    // -------------------------------------------------------------------------
    // hasEndpoint
    // -------------------------------------------------------------------------

    @Test
    void testHasEndpoint_knownId_returnsTrue() {
        assertTrue(configService.hasEndpoint("SAP"));
    }

    @Test
    void testHasEndpoint_unknownId_returnsFalse() {
        assertFalse(configService.hasEndpoint("UNKNOWN"));
    }

    @Test
    void testHasEndpoint_nullId_returnsFalse() {
        assertFalse(configService.hasEndpoint(null));
    }

    // -------------------------------------------------------------------------
    // getURL
    // -------------------------------------------------------------------------

    @Test
    void testGetURL_knownEndpoint_returnsUrl() {
        assertEquals("http://sap-api-server/imixs-hook", configService.getURL("SAP"));
    }

    @Test
    void testGetURL_unknownEndpoint_returnsNull() {
        assertNull(configService.getURL("UNKNOWN"));
    }

    // -------------------------------------------------------------------------
    // getApiKey
    // -------------------------------------------------------------------------

    @Test
    void testGetApiKey_endpointWithKey_returnsKey() {
        assertEquals("test-secret-token", configService.getApiKey("SAP"));
    }

    @Test
    void testGetApiKey_endpointWithoutKey_returnsNull() {
        // INTERNAL endpoint has no <apikey> element
        assertNull(configService.getApiKey("INTERNAL"));
    }

    // -------------------------------------------------------------------------
    // getTimeout
    // -------------------------------------------------------------------------

    @Test
    void testGetTimeout_endpointWithTimeout_returnsConfiguredValue() {
        assertEquals(5000, configService.getTimeout("SAP"));
    }

    @Test
    void testGetTimeout_endpointWithoutTimeout_returnsDefault() {
        // N8N endpoint has no <timeout> element - should fall back to DEFAULT_TIMEOUT
        assertEquals(ImixsConnectConfigService.DEFAULT_TIMEOUT, configService.getTimeout("N8N"));
    }

    @Test
    void testGetTimeout_unknownEndpoint_returnsDefault() {
        assertEquals(ImixsConnectConfigService.DEFAULT_TIMEOUT, configService.getTimeout("UNKNOWN"));
    }

    // -------------------------------------------------------------------------
    // Environment variable placeholder resolution
    // -------------------------------------------------------------------------

    @Test
    void testGetApiKey_envPlaceholder_unsetVariable_returnsEmptyString() {
        // N8N endpoint uses ${env.N8N_API_KEY} - if not set, should resolve to ""
        // We can only test the "not set" case reliably in a plain unit test
        String apiKey = configService.getApiKey("N8N");
        // If the env var is not set the service replaces the placeholder with ""
        // If the env var IS set in the test environment the resolved value is returned
        // Either way the result must not contain the raw placeholder syntax
        assertTrue(apiKey == null || !apiKey.contains("${env."),
                "Resolved value must not contain unresolved placeholder syntax");
    }
}