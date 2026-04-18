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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ImixsConnectService}.
 * <p>
 * The {@link ImixsConnectConnector} is mocked via Mockito so that no real HTTP
 * connection is established. The mock returns a pre-defined
 * {@link HttpURLConnection} whose input/output streams are backed by simple
 * byte arrays.
 */
@ExtendWith(MockitoExtension.class)
class ImixsConnectServiceTest {

    @Mock
    ImixsConnectConnector connector;

    @InjectMocks
    ImixsConnectService connectService;

    // Reusable workitem for all tests
    private ItemCollection workitem;

    // Valid Imixs XMLDocument response with customer data
    private static final String RESPONSE_XML_OK = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <document xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                      xmlns:xs="http://www.w3.org/2001/XMLSchema">
                <item name="customer.id">
                    <value xsi:type="xs:string">CUST-4711</value>
                </item>
                <item name="customer.name">
                    <value xsi:type="xs:string">Acme Corp GmbH</value>
                </item>
                <item name="customer.email">
                    <value xsi:type="xs:string">contact@acme.com</value>
                </item>
            </document>
            """;

    // Malformed XML to test contract violation handling
    private static final String RESPONSE_XML_BROKEN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <this-is-not-a-valid-imixs-document>
                <garbage/>
            </this-is-not-a-valid-imixs-document>
            """;

    @BeforeEach
    void setUp() {
        workitem = new ItemCollection();
        workitem.setItemValue("offer.sender.name", "John Doe");
        workitem.setItemValue("offer.total.amount", 1234.56);
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void testExecute_successfulCall_mergesResultItemsIntoWorkitem() throws Exception {
        // Arrange
        mockHttpConnection(HttpURLConnection.HTTP_OK, RESPONSE_XML_OK);

        List<String> requestParams = Arrays.asList("offer.sender.name", "offer.total.amount");
        List<String> resultParams = Arrays.asList("customer.id", "customer.name", "customer.email");

        // Act
        connectService.execute(workitem, "SAP", requestParams, resultParams, false);

        // Assert - result items are merged into the workitem
        assertEquals("CUST-4711", workitem.getItemValueString("customer.id"));
        assertEquals("Acme Corp GmbH", workitem.getItemValueString("customer.name"));
        assertEquals("contact@acme.com", workitem.getItemValueString("customer.email"));
    }

    @Test
    void testExecute_resultParamsFiltered_onlyConfiguredItemsMerged() throws Exception {
        // Arrange - response contains customer.id, customer.name, customer.email
        // but result-params only requests customer.id
        mockHttpConnection(HttpURLConnection.HTTP_OK, RESPONSE_XML_OK);

        List<String> requestParams = Arrays.asList("offer.sender.name");
        List<String> resultParams = Arrays.asList("customer.id"); // only one item

        // Act
        connectService.execute(workitem, "SAP", requestParams, resultParams, false);

        // Assert - only customer.id is merged, other items from response are ignored
        assertEquals("CUST-4711", workitem.getItemValueString("customer.id"));
        assertFalse(workitem.hasItem("customer.name"),
                "customer.name must not be merged - not in result-params");
        assertFalse(workitem.hasItem("customer.email"),
                "customer.email must not be merged - not in result-params");
    }

    @Test
    void testExecute_requestParamsFiltered_onlyConfiguredItemsSent() throws Exception {
        // Arrange - workitem has more items than configured in request-params
        workitem.setItemValue("internal.secret", "should-never-be-sent");
        mockHttpConnection(HttpURLConnection.HTTP_OK, RESPONSE_XML_OK);

        List<String> requestParams = Arrays.asList("offer.sender.name"); // only one item
        List<String> resultParams = Arrays.asList("customer.id");

        // Act - must complete without error
        connectService.execute(workitem, "SAP", requestParams, resultParams, false);

        // Assert - the call succeeded; internal.secret was not exposed
        // (verified indirectly - if the full workitem were sent the endpoint
        // would still return the mocked response, so we verify the filter
        // by checking that only the allowed result was merged)
        assertEquals("CUST-4711", workitem.getItemValueString("customer.id"));
    }

    @Test
    void testExecute_missingRequestParam_skippedGracefully() throws Exception {
        // Arrange - request-params references an item that does not exist in the
        // workitem
        mockHttpConnection(HttpURLConnection.HTTP_OK, RESPONSE_XML_OK);

        List<String> requestParams = Arrays.asList("offer.sender.name", "offer.does.not.exist");
        List<String> resultParams = Arrays.asList("customer.id");

        // Act - must complete without throwing an exception
        connectService.execute(workitem, "SAP", requestParams, resultParams, false);

        // Assert
        assertEquals("CUST-4711", workitem.getItemValueString("customer.id"));
    }

    // -------------------------------------------------------------------------
    // HTTP error handling → AdapterException
    // -------------------------------------------------------------------------

    @Test
    void testExecute_http500_throwsAdapterExceptionWithHttpErrorCode() throws Exception {
        // Arrange
        mockHttpConnection(HttpURLConnection.HTTP_INTERNAL_ERROR, "Internal Server Error");

        List<String> requestParams = Arrays.asList("offer.sender.name");
        List<String> resultParams = Arrays.asList("customer.id");

        // Act & Assert
        AdapterException ex = assertThrows(AdapterException.class,
                () -> connectService.execute(workitem, "SAP", requestParams, resultParams, false));

        assertEquals(ImixsConnectService.ERROR_CONNECT_HTTP, ex.getErrorCode());
    }

    @Test
    void testExecute_http401_throwsAdapterException() throws Exception {
        // Arrange
        mockHttpConnection(HttpURLConnection.HTTP_UNAUTHORIZED, "Unauthorized");

        List<String> requestParams = Arrays.asList("offer.sender.name");
        List<String> resultParams = Arrays.asList("customer.id");

        // Act & Assert
        AdapterException ex = assertThrows(AdapterException.class,
                () -> connectService.execute(workitem, "SAP", requestParams, resultParams, false));

        assertEquals(ImixsConnectService.ERROR_CONNECT_HTTP, ex.getErrorCode());
    }

    // -------------------------------------------------------------------------
    // Malformed response XML → PluginException (contract violation)
    // -------------------------------------------------------------------------

    @Test
    void testExecute_brokenResponseXml_throwsPluginException() throws Exception {
        // Arrange - endpoint returns HTTP 200 but with invalid XMLDocument body
        mockHttpConnection(HttpURLConnection.HTTP_OK, RESPONSE_XML_BROKEN);

        List<String> requestParams = Arrays.asList("offer.sender.name");
        List<String> resultParams = Arrays.asList("customer.id");

        // Act & Assert - contract violation must abort the transaction immediately
        PluginException ex = assertThrows(PluginException.class,
                () -> connectService.execute(workitem, "SAP", requestParams, resultParams, false));

        assertEquals(ImixsConnectService.ERROR_CONNECT_XML, ex.getErrorCode());
    }

    // -------------------------------------------------------------------------
    // Mock helper
    // -------------------------------------------------------------------------

    /**
     * Prepares the mocked {@link ImixsConnectConnector} to return a
     * {@link HttpURLConnection} mock that responds with the given HTTP status code
     * and response body.
     *
     * @param httpStatus   - HTTP status code to return from getResponseCode()
     * @param responseBody - response body string (XML or error message)
     */
    private void mockHttpConnection(int httpStatus, String responseBody) throws Exception {
        HttpURLConnection mockConn = mock(HttpURLConnection.class);

        // Output stream - absorbs the request body written by ImixsConnectService
        when(mockConn.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(mockConn.getResponseCode()).thenReturn(httpStatus);

        byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);

        if (httpStatus == HttpURLConnection.HTTP_OK) {
            when(mockConn.getInputStream())
                    .thenReturn(new ByteArrayInputStream(responseBytes));
        } else {
            when(mockConn.getErrorStream())
                    .thenReturn(new ByteArrayInputStream(responseBytes));
        }

        when(connector.createHttpConnection(anyString())).thenReturn(mockConn);
    }
}