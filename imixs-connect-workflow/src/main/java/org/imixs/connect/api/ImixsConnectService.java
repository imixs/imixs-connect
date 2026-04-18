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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Serializable;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;
import org.imixs.workflow.xml.XMLDocument;
import org.imixs.workflow.xml.XMLDocumentAdapter;

import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;

/**
 * The ImixsConnectService provides the core business logic for the
 * Imixs-Connect integration layer.
 * <p>
 * The service handles the full request/response lifecycle:
 * <ol>
 * <li>Filter the configured <code>request-params</code> from the workitem into
 * a new {@link ItemCollection}</li>
 * <li>Marshal the filtered ItemCollection into an Imixs
 * {@link XMLDocument}</li>
 * <li>POST the XMLDocument to the configured external endpoint via
 * {@link ImixsConnectConnector}</li>
 * <li>Unmarshal the XMLDocument response</li>
 * <li>Merge the configured <code>result-params</code> back into the
 * workitem</li>
 * </ol>
 * <p>
 * Both request and response use the standard Imixs XMLDocument format as
 * defined at https://www.imixs.org/doc/core/xml/index.html
 * <p>
 * Communication errors (connection failure, non-200 response) are thrown as
 * {@link AdapterException} so that the BPMN modeller can handle them via
 * conditional sequence flows evaluating <code>adapter.error_code</code>.
 * <p>
 * Marshalling errors (malformed response XML) are thrown as
 * {@link PluginException} since they indicate a contract violation by the
 * external endpoint and should abort the transaction immediately.
 *
 * @author imixs.com
 */
@Stateless
@LocalBean
public class ImixsConnectService implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger logger = Logger.getLogger(ImixsConnectService.class.getName());

    // AdapterException error codes - used in BPMN conditional flows
    public static final String ERROR_CONNECT_TIMEOUT = "ERROR_CONNECT_TIMEOUT";
    public static final String ERROR_CONNECT_HTTP = "ERROR_CONNECT_HTTP";
    public static final String ERROR_CONNECT_IO = "ERROR_CONNECT_IO";

    // PluginException error code - hard failure, aborts transaction
    public static final String ERROR_CONNECT_XML = "ERROR_CONNECT_XML";

    @Inject
    ImixsConnectConnector connector;

    /**
     * Executes the full Imixs-Connect request/response cycle for a given workitem.
     * <p>
     * The method filters the <code>requestParams</code> items from the workitem,
     * POSTs them as an XMLDocument to the endpoint identified by
     * <code>endpointId</code>, and merges the <code>resultParams</code> items from
     * the response back into the workitem.
     * <p>
     * Communication errors throw an {@link AdapterException} — the processing
     * lifecycle continues and the error is available in the workitem via
     * <code>adapter.error_code</code> for conditional BPMN flows.
     * <p>
     * A malformed response XML throws a {@link PluginException} — this aborts the
     * transaction immediately since it indicates a contract violation.
     *
     * @param workitem      - the current workflow workitem
     * @param endpointId    - logical endpoint id as defined in imixs-connect.xml
     * @param requestParams - list of item names to include in the POST request
     * @param resultParams  - list of item names to read from the response
     * @param debug         - if true, request and response XML are logged at INFO
     *                      level
     * @throws AdapterException if the HTTP connection fails or the endpoint returns
     *                          a non-200 status code
     * @throws PluginException  if the response XML cannot be unmarshalled
     */
    public void execute(ItemCollection workitem, String endpointId,
            List<String> requestParams, List<String> resultParams, boolean debug)
            throws AdapterException, PluginException {

        long startTime = System.currentTimeMillis();

        // Step 1 - Build the request XMLDocument from filtered workitem items
        String requestXml = buildRequestXml(workitem, requestParams, debug);

        // Step 2 - POST the XMLDocument and read the response
        String responseXml = postXmlDocument(endpointId, requestXml, debug);

        // Step 3 - Merge result-params from response back into workitem
        mergeResponseIntoWorkitem(workitem, responseXml, resultParams, debug);

        logger.fine("ImixsConnectService: endpoint '" + endpointId + "' completed in "
                + (System.currentTimeMillis() - startTime) + "ms");
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Builds an Imixs XMLDocument containing only the items listed in
     * <code>requestParams</code> and marshals it to an XML string. Items not
     * present in the workitem are silently skipped.
     *
     * @param workitem      - source workitem
     * @param requestParams - item names to include
     * @param debug         - log the generated XML at INFO level if true
     * @return XML string representing the filtered XMLDocument
     * @throws PluginException if marshalling fails
     */
    private String buildRequestXml(ItemCollection workitem, List<String> requestParams,
            boolean debug) throws PluginException {

        // Build a filtered ItemCollection containing only the request-params
        ItemCollection requestData = new ItemCollection();
        for (String itemName : requestParams) {
            if (workitem.hasItem(itemName)) {
                requestData.setItemValue(itemName, workitem.getItemValue(itemName));
            } else {
                logger.warning("ImixsConnectService: request-param '" + itemName
                        + "' not found in workitem – skipped.");
            }
        }

        // Convert ItemCollection to XMLDocument using the Imixs XMLDocumentAdapter
        XMLDocument xmlDocument = XMLDocumentAdapter.getDocument(requestData);

        // Marshal XMLDocument to XML string via JAXB
        try {
            JAXBContext jaxbContext = JAXBContext.newInstance(XMLDocument.class);
            Marshaller marshaller = jaxbContext.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
            marshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");

            StringWriter writer = new StringWriter();
            marshaller.marshal(xmlDocument, writer);
            String xml = writer.toString();

            if (debug) {
                logger.info("├── 📥 ImixsConnect Request XML:");
                logger.info(xml);
            }
            return xml;

        } catch (JAXBException e) {
            // Marshalling failure is a hard error - abort transaction
            throw new PluginException(
                    ImixsConnectService.class.getSimpleName(),
                    ERROR_CONNECT_XML,
                    "Failed to marshal request XMLDocument: " + e.getMessage(), e);
        }
    }

    /**
     * POSTs the given XML string to the endpoint identified by
     * <code>endpointId</code> and returns the response body as a string.
     * <p>
     * HTTP errors and IO failures are thrown as {@link AdapterException} so that
     * the BPMN modeller can define error handling flows based on the error code:
     * <ul>
     * <li>{@link #ERROR_CONNECT_HTTP} — endpoint returned a non-200 HTTP status
     * <li>{@link #ERROR_CONNECT_IO} — network or socket error
     * </ul>
     *
     * @param endpointId - logical endpoint id as defined in imixs-connect.xml
     * @param requestXml - XML string to POST
     * @param debug      - log the response XML at INFO level if true
     * @return the response body XML string
     * @throws AdapterException if the HTTP call fails or returns a non-200 status
     */
    private String postXmlDocument(String endpointId, String requestXml,
            boolean debug) throws AdapterException {

        HttpURLConnection conn = null;
        try {
            conn = connector.createHttpConnection(endpointId);

            if (debug) {
                logger.info("├── POST " + conn.getURL().toString());
            }

            // Write request body
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestXml.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // Evaluate HTTP response code
            int responseCode = conn.getResponseCode();
            if (debug) {
                logger.info("├── Response Code: " + responseCode);
            }

            if (responseCode == HttpURLConnection.HTTP_OK) {
                String responseXml = readStream(conn.getInputStream());
                if (debug) {
                    logger.info("├── 📤 ImixsConnect Response XML:");
                    logger.info(responseXml);
                }
                return responseXml;
            } else {
                // Non-200 response - external endpoint reported an error
                String errorBody = readStream(conn.getErrorStream());
                logger.warning("└── ⚠️ ImixsConnect POST failed – HTTP " + responseCode
                        + " – " + errorBody);
                throw new AdapterException(
                        ImixsConnectService.class.getSimpleName(),
                        ERROR_CONNECT_HTTP,
                        "Endpoint '" + endpointId + "' returned HTTP " + responseCode
                                + ": " + errorBody);
            }

        } catch (AdapterException e) {
            throw e;
        } catch (IOException e) {
            // Network/socket error - allow BPMN error flow to handle this
            logger.warning("└── ⚠️ ImixsConnect IO error for endpoint '" + endpointId
                    + "': " + e.getMessage());
            throw new AdapterException(
                    ImixsConnectService.class.getSimpleName(),
                    ERROR_CONNECT_IO,
                    "IO error communicating with endpoint '" + endpointId + "': "
                            + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Unmarshals the response XML string into an {@link XMLDocument}, converts it
     * to an {@link ItemCollection}, and merges only the items listed in
     * <code>resultParams</code> back into the workitem. Items not present in the
     * response are logged as a warning and skipped.
     *
     * @param workitem     - target workitem to merge results into
     * @param responseXml  - XML response string from the external endpoint
     * @param resultParams - item names to read from the response
     * @param debug        - log merged items at INFO level if true
     * @throws PluginException if the response XML cannot be unmarshalled — this
     *                         aborts the transaction since it indicates a contract
     *                         violation by the external endpoint
     */
    private void mergeResponseIntoWorkitem(ItemCollection workitem, String responseXml,
            List<String> resultParams, boolean debug) throws PluginException {

        // Unmarshal the response XML into an XMLDocument via JAXB
        ItemCollection responseData;
        try {
            JAXBContext jaxbContext = JAXBContext.newInstance(XMLDocument.class);
            Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
            XMLDocument xmlDocument = (XMLDocument) unmarshaller
                    .unmarshal(new java.io.StringReader(responseXml));

            // Convert XMLDocument back to ItemCollection using Imixs XMLDocumentAdapter
            responseData = XMLDocumentAdapter.putDocument(xmlDocument);

        } catch (JAXBException e) {
            // Malformed response is a hard error - the external endpoint violated the
            // contract
            throw new PluginException(
                    ImixsConnectService.class.getSimpleName(),
                    ERROR_CONNECT_XML,
                    "Failed to unmarshal response XMLDocument - "
                            + "verify the endpoint returns a valid Imixs XMLDocument: "
                            + e.getMessage(),
                    e);
        }

        // Merge only the configured result-params into the workitem
        for (String itemName : resultParams) {
            if (responseData.hasItem(itemName)) {
                workitem.setItemValue(itemName, responseData.getItemValue(itemName));
                if (debug) {
                    logger.info("├── merged '" + itemName + "' = "
                            + responseData.getItemValue(itemName));
                }
            } else {
                logger.warning("ImixsConnectService: result-param '" + itemName
                        + "' not found in response – skipped.");
            }
        }
    }

    /**
     * Reads an {@link InputStream} fully and returns its content as a UTF-8 string.
     * Returns an empty string if the stream is null.
     *
     * @param is - input stream to read
     * @return the stream content as string
     */
    private String readStream(InputStream is) {
        if (is == null) {
            return "";
        }
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line).append("\n");
            }
            return response.toString();
        } catch (IOException e) {
            logger.warning("ImixsConnectService: could not read stream: " + e.getMessage());
            return "";
        }
    }
}