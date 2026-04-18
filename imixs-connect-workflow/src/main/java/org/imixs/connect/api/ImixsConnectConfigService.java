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

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.inject.Inject;

/**
 * Singleton EJB that loads the <code>imixs-connect.xml</code> configuration
 * file at application startup and provides lookup methods for endpoint URL, API
 * key and timeout by logical endpoint id.
 * <p>
 * The path to the configuration file is configured via MicroProfile Config:
 *
 * <pre>
 * # As environment variable:
 * CONNECT_CONFIG_FILE=/opt/imixs/imixs-connect.xml
 *
 * # Or as system property:
 * connect.config.file=/opt/imixs/imixs-connect.xml
 * </pre>
 *
 * If the property is not set the service starts with an empty registry and logs
 * a warning.
 * <p>
 * Example imixs-connect.xml:
 *
 * <pre>
 * {@code
 * <imixs-connect>
 *     <endpoint id="SAP">
 *         <url>http://sap-api-server/imixs-hook</url>
 *         <apikey>${env.SAP_API_KEY}</apikey>
 *         <timeout>5000</timeout>
 *     </endpoint>
 *     <endpoint id="N8N">
 *         <url>https://n8n.my-company.com/webhook/imixs</url>
 *         <apikey>${env.N8N_API_KEY}</apikey>
 *     </endpoint>
 * </imixs-connect>
 * }
 * </pre>
 *
 * @author imixs.com
 */
@Singleton
@Startup
public class ImixsConnectConfigService {

    public static final String ENV_CONNECT_CONFIG_FILE = "connect.config.file";
    public static final int DEFAULT_TIMEOUT = 30000;

    private static final Logger logger = Logger.getLogger(ImixsConnectConfigService.class.getName());

    @Inject
    @ConfigProperty(name = ENV_CONNECT_CONFIG_FILE)
    Optional<String> configFilePath;

    // The parsed XML document - null if config file was not loaded
    private Document configDocument = null;

    /**
     * Loads and parses the imixs-connect.xml from the path configured via the
     * MicroProfile Config property <code>connect.config.file</code>.
     */
    @PostConstruct
    public void init() {
        if (!configFilePath.isPresent() || configFilePath.get().isBlank()) {
            logger.warning("├── ⚠️ Imixs-Connect: property '" + ENV_CONNECT_CONFIG_FILE
                    + "' is not set – endpoint registry is empty.");
            return;
        }

        String path = configFilePath.get().trim();
        logger.info("├── Imixs-Connect: loading config from '" + path + "'");

        try (InputStream is = new FileInputStream(path)) {
            configDocument = parseXML(is);
            logger.info("├── ✅ Imixs-Connect: config loaded successfully from '" + path + "'");
        } catch (IOException e) {
            logger.severe("├── ⚠️ Imixs-Connect: cannot read '" + path + "': " + e.getMessage());
        } catch (Exception e) {
            logger.severe("├── ⚠️ Imixs-Connect: failed to parse '" + path + "': " + e.getMessage());
        }
    }

    /**
     * Returns the URL of the endpoint with the given id, or null if not found.
     *
     * @param endpointId - the id attribute of the &lt;endpoint&gt; element
     * @return the resolved URL string, or null
     */
    public String getURL(String endpointId) {
        return getEndpointValue(endpointId, "url");
    }

    /**
     * Returns the API key of the endpoint with the given id, or null if not
     * configured.
     *
     * @param endpointId - the id attribute of the &lt;endpoint&gt; element
     * @return the resolved API key string, or null
     */
    public String getApiKey(String endpointId) {
        return getEndpointValue(endpointId, "apikey");
    }

    /**
     * Returns the timeout in milliseconds for the endpoint with the given id. Falls
     * back to {@link #DEFAULT_TIMEOUT} if not configured.
     *
     * @param endpointId - the id attribute of the &lt;endpoint&gt; element
     * @return the timeout in milliseconds
     */
    public int getTimeout(String endpointId) {
        String value = getEndpointValue(endpointId, "timeout");
        if (value == null) {
            return DEFAULT_TIMEOUT;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.warning("├── ⚠️ Imixs-Connect: invalid timeout for endpoint '"
                    + endpointId + "' – using default " + DEFAULT_TIMEOUT + "ms.");
            return DEFAULT_TIMEOUT;
        }
    }

    /**
     * Returns true if an endpoint with the given id exists in the config.
     *
     * @param endpointId - the id attribute of the &lt;endpoint&gt; element
     */
    public boolean hasEndpoint(String endpointId) {
        return findEndpointElement(endpointId) != null;
    }

    /**
     * Allows unit tests to inject a pre-parsed XML document directly without
     * reading from the filesystem.
     *
     * @param document - a parsed imixs-connect.xml document
     */
    public void setConfigDocument(Document document) {
        this.configDocument = document;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Finds the &lt;endpoint&gt; element with the given id and returns the text
     * content of the specified direct child tag.
     */
    private String getEndpointValue(String endpointId, String tagName) {
        Element endpoint = findEndpointElement(endpointId);
        if (endpoint == null) {
            return null;
        }
        NodeList nodes = endpoint.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        String value = nodes.item(0).getTextContent();
        if (value == null || value.isBlank()) {
            return null;
        }
        return resolveEnvPlaceholders(value.trim());
    }

    /**
     * Returns the &lt;endpoint&gt; Element with the matching id attribute, or null
     * if the config document is not loaded or no match is found.
     */
    private Element findEndpointElement(String endpointId) {
        if (configDocument == null || endpointId == null || endpointId.isBlank()) {
            return null;
        }
        NodeList endpoints = configDocument.getElementsByTagName("endpoint");
        for (int i = 0; i < endpoints.getLength(); i++) {
            Element element = (Element) endpoints.item(i);
            if (endpointId.equals(element.getAttribute("id"))) {
                return element;
            }
        }
        logger.warning("├── ⚠️ Imixs-Connect: no endpoint found for id '" + endpointId + "'");
        return null;
    }

    /**
     * Parses an imixs-connect.xml input stream into a DOM Document. External entity
     * processing is disabled to prevent XXE attacks.
     */
    private Document parseXML(InputStream is) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Disable external entities to prevent XXE attacks
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(is);
        doc.getDocumentElement().normalize();
        return doc;
    }

    /**
     * Resolves ${env.VAR_NAME} placeholders against system environment variables.
     * If the variable is not set, the placeholder is replaced with an empty string
     * and a warning is logged.
     */
    private String resolveEnvPlaceholders(String value) {
        if (value == null || !value.contains("${env.")) {
            return value;
        }
        StringBuilder result = new StringBuilder(value);
        int start;
        while ((start = result.indexOf("${env.")) >= 0) {
            int end = result.indexOf("}", start);
            if (end < 0) {
                break;
            }
            String varName = result.substring(start + 6, end);
            String envValue = System.getenv(varName);
            if (envValue == null) {
                logger.warning("├── ⚠️ Imixs-Connect: environment variable '" + varName
                        + "' is not set – replacing with empty string.");
                envValue = "";
            }
            result.replace(start, end + 1, envValue);
        }
        return result.toString();
    }
}