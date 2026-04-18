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

import java.io.IOException;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.logging.Logger;

import org.imixs.workflow.exceptions.AdapterException;

import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;

/**
 * The ImixsConnectConnector provides a factory method to create an
 * authenticated HTTP connection to a registered Imixs-Connect endpoint.
 * <p>
 * The logical endpoint id is resolved via {@link ImixsConnectConfigService},
 * which reads the URL, API key and timeout from the
 * <code>imixs-connect.xml</code> configuration file. The caller only provides
 * the endpoint id — no URLs or credentials appear in business code.
 * <p>
 * Bearer authentication is used when an API key is configured for the endpoint.
 * If no API key is set the request is sent without an Authorization header,
 * which is typical for locally hosted or trusted internal services.
 * <p>
 * All failures are thrown as {@link AdapterException} so that the BPMN modeller
 * can handle them via conditional sequence flows. The caller is responsible for
 * closing the connection after use.
 *
 * @author imixs.com
 */
@Stateless
@LocalBean
public class ImixsConnectConnector implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger logger = Logger.getLogger(ImixsConnectConnector.class.getName());

    public static final String ERROR_CONNECT_UNKNOWN_ENDPOINT = "ERROR_CONNECT_UNKNOWN_ENDPOINT";
    public static final String ERROR_CONNECT_IO = "ERROR_CONNECT_IO";

    @Inject
    ImixsConnectConfigService configService;

    /**
     * Creates an authenticated {@link HttpURLConnection} to the endpoint identified
     * by the given logical endpoint id.
     * <p>
     * The endpoint URL and credentials are resolved via
     * {@link ImixsConnectConfigService}. If no endpoint with the given id is
     * registered an {@link AdapterException} with error code
     * {@link #ERROR_CONNECT_UNKNOWN_ENDPOINT} is thrown — the BPMN modeller can use
     * this to detect misconfiguration in a conditional flow.
     * <p>
     * The connection is configured as a POST request with
     * <code>Content-Type: application/xml</code> and
     * <code>Accept: application/xml</code> — matching the Imixs XMLDocument payload
     * format used by both request and response.
     *
     * @param endpointId - logical endpoint id as defined in imixs-connect.xml
     * @return an open HttpURLConnection ready for writing the request body
     * @throws AdapterException if the endpoint id is unknown or the connection
     *                          fails
     */
    public HttpURLConnection createHttpConnection(String endpointId) throws AdapterException {

        // Resolve URL from config
        String url = configService.getURL(endpointId);
        if (url == null || url.isBlank()) {
            throw new AdapterException(
                    ImixsConnectConnector.class.getSimpleName(),
                    ERROR_CONNECT_UNKNOWN_ENDPOINT,
                    "Unknown Imixs-Connect endpoint id: '" + endpointId
                            + "' – verify imixs-connect.xml");
        }

        // Resolve timeout from config
        int timeout = configService.getTimeout(endpointId);

        HttpURLConnection conn = null;
        try {
            // Normalize URL - remove trailing slash
            url = url.strip();
            if (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }

            logger.fine("ImixsConnectConnector: connecting to '" + url + "'");

            URL requestUrl = new URL(url);
            conn = (HttpURLConnection) requestUrl.openConnection();

            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);

            // Bearer authentication - only if an API key is configured
            String apiKey = configService.getApiKey(endpointId);
            if (apiKey != null && !apiKey.isBlank()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/xml; utf-8");
            conn.setRequestProperty("Accept", "application/xml");
            conn.setDoOutput(true);

        } catch (IOException e) {
            logger.severe("ImixsConnectConnector: failed to open connection to '"
                    + url + "': " + e.getMessage());
            throw new AdapterException(
                    ImixsConnectConnector.class.getSimpleName(),
                    ERROR_CONNECT_IO,
                    "Failed to create connection for endpoint '" + endpointId
                            + "': " + e.getMessage());
        }

        return conn;
    }
}