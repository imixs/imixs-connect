/*******************************************************************************
 * Copyright (c) 2025 Imixs Software Solutions GmbH and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
 *******************************************************************************/
package org.imixs.connect.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.imixs.connect.api.ImixsConnectService;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.SignalAdapter;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.inject.Inject;

/**
 * The ImixsConnectAdapter is a {@link SignalAdapter} that integrates external
 * systems into the Imixs-Workflow processing life cycle via a generic HTTP/XML
 * endpoint.
 * <p>
 * The adapter reads its configuration from the BPMN event definition and
 * delegates the actual HTTP call to {@link ImixsConnectService}. Multiple
 * <code>&lt;imixs-connect&gt;</code> definitions can be placed on a single BPMN
 * event — they are executed sequentially in the order they appear.
 * <p>
 * Example BPMN event configuration:
 *
 * <pre>
 * {@code
 * <imixs-connect>
 *     <endpoint-id>SAP</endpoint-id>
 *     <debug>true</debug>
 *     <on-error>continue</on-error>
 *     <request-params>offer.sender.name, offer.total.amount</request-params>
 *     <result-params>customer.id, customer.name, customer.email</result-params>
 * </imixs-connect>
 * }
 * </pre>
 *
 * <p>
 * The <code>endpoint-id</code> references a named endpoint in the admin-managed
 * <code>imixs-connect.xml</code> configuration file. URL and Bearer token are
 * never stored in the BPMN model.
 * <p>
 * The <code>on-error</code> attribute controls error handling behaviour:
 * <ul>
 * <li><code>continue</code> (default) — on communication failure an
 * {@link AdapterException} is thrown. The processing lifecycle continues and
 * the error is available in the workitem via <code>adapter.error_code</code>
 * for conditional BPMN sequence flows.</li>
 * <li><code>throw</code> — on communication failure a {@link PluginException}
 * is thrown, which aborts the transaction immediately.</li>
 * </ul>
 *
 * @author imixs.com
 */
public class ImixsConnectAdapter implements SignalAdapter {

    private static final Logger logger = Logger.getLogger(ImixsConnectAdapter.class.getName());

    public static final String IMIXS_CONNECT_TAG = "imixs-connect";
    public static final String ON_ERROR_THROW = "throw";
    public static final String ON_ERROR_CONTINUE = "continue";

    @Inject
    protected WorkflowService workflowService;

    @Inject
    protected ImixsConnectService connectService;

    /**
     * Executes all <code>&lt;imixs-connect&gt;</code> definitions found in the BPMN
     * event configuration sequentially.
     * <p>
     * For each definition the adapter resolves the endpoint, parses the
     * request/result item lists and delegates to {@link ImixsConnectService}. Error
     * handling follows the <code>on-error</code> setting per definition.
     *
     * @param workitem - the current workflow workitem
     * @param event    - the BPMN event containing the imixs-connect configuration
     * @return the workitem enriched with result items from the external endpoint
     * @throws AdapterException if on-error=continue and a communication error
     *                          occurs
     * @throws PluginException  if on-error=throw and a communication error occurs,
     *                          or if the response XML is malformed
     */
    @Override
    public ItemCollection execute(ItemCollection workitem, ItemCollection event)
            throws AdapterException, PluginException {

        long processingTime = System.currentTimeMillis();
        logger.finest("├── Running ImixsConnectAdapter...");

        // Read all <imixs-connect> definitions from the BPMN event
        List<ItemCollection> connectDefinitions = workflowService.evalWorkflowResultXML(
                event, IMIXS_CONNECT_TAG, "POST", workitem, false);

        if (connectDefinitions == null || connectDefinitions.isEmpty()) {
            logger.warning("ImixsConnectAdapter: no <imixs-connect> definition found in event – skipped.");
            return workitem;
        }

        // Process each definition sequentially
        for (ItemCollection definition : connectDefinitions) {

            // -- endpoint-id (required) --
            String endpointId = definition.getItemValueString("endpoint-id").trim();
            if (endpointId.isEmpty()) {
                throw new PluginException(
                        ImixsConnectAdapter.class.getSimpleName(),
                        ImixsConnectService.ERROR_CONNECT_IO,
                        "Missing <endpoint-id> in <imixs-connect> definition – verify BPMN model.");
            }

            // -- debug --
            boolean debug = "true".equalsIgnoreCase(definition.getItemValueString("debug").trim());

            // -- on-error --
            String onError = definition.getItemValueString("on-error").trim();
            if (onError.isEmpty()) {
                onError = ON_ERROR_CONTINUE;
            }

            // -- request-params (comma-separated) --
            List<String> requestParams = parseCommaSeparated(
                    definition.getItemValueString("request-params"));

            // -- result-params (comma-separated) --
            List<String> resultParams = parseCommaSeparated(
                    definition.getItemValueString("result-params"));

            if (debug) {
                logger.info("├── ImixsConnectAdapter endpoint-id='" + endpointId + "'");
                logger.info("│   ├── request-params : " + requestParams);
                logger.info("│   ├── result-params  : " + resultParams);
                logger.info("│   ├── on-error       : " + onError);
            }

            // -- execute the connect call --
            try {
                connectService.execute(workitem, endpointId, requestParams, resultParams, debug);
            } catch (AdapterException e) {
                if (ON_ERROR_THROW.equalsIgnoreCase(onError)) {
                    // Escalate to PluginException - aborts the transaction immediately
                    throw new PluginException(
                            ImixsConnectAdapter.class.getSimpleName(),
                            e.getErrorCode(),
                            "ImixsConnect call to endpoint '" + endpointId
                                    + "' failed (on-error=throw): " + e.getMessage(),
                            e);
                } else {
                    // on-error=continue - rethrow AdapterException, BPMN handles it
                    if (debug) {
                        logger.warning("│   └── ⚠️ AdapterException (on-error=continue): "
                                + e.getErrorCode() + " – " + e.getMessage());
                    }
                    throw e;
                }
            }

            if (debug) {
                logger.info("└── ImixsConnectAdapter endpoint='" + endpointId + "' completed in "
                        + (System.currentTimeMillis() - processingTime) + "ms");
            }
        }

        return workitem;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Parses a comma-separated string into a trimmed list of non-empty strings.
     * Returns an empty list if the input is null or blank.
     *
     * @param value - comma-separated string, e.g. "offer.name, offer.amount"
     * @return list of trimmed item name strings
     */
    private List<String> parseCommaSeparated(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}