# Implementing an Imixs-Connect Endpoint in Java

This guide explains how to implement a compatible Imixs-Connect endpoint in Java using Jakarta EE. The endpoint receives a workflow event from Imixs-Workflow, processes it, and returns a response — both in the standard [Imixs XMLDocument](https://www.imixs.org/doc/core/xml/index.html) format.

---

## The Contract

An Imixs-Connect endpoint must:

- Accept **HTTP POST** requests
- Consume `Content-Type: application/xml`
- Produce `Content-Type: application/xml`
- Validate the **Bearer token** from the `Authorization` header
- Return **HTTP 200** with an XMLDocument response body on success
- Return an appropriate **HTTP error code** (4xx/5xx) on failure

The request and response body are both standard Imixs `XMLDocument` objects. The schema is documented at [imixs.org/doc/core/xml](https://www.imixs.org/doc/core/xml/index.html).

---

## Maven Dependency

To work with the Imixs XMLDocument format directly, add the Imixs Workflow Core dependency:

```xml
<dependency>
    <groupId>org.imixs.workflow</groupId>
    <artifactId>imixs-workflow-core</artifactId>
    <version>${imixs.workflow.version}</version>
    <scope>provided</scope>
</dependency>
```

This gives you access to `XMLDocument`, `XMLItem`, `ItemCollection` and `XMLDocumentAdapter`.

---

## Example Implementation

The following example shows a minimal Jakarta EE REST endpoint that receives an Imixs-Connect request, reads the incoming items, and returns a response with result data.

```java
package com.example.connect;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.xml.XMLDocument;
import org.imixs.workflow.xml.XMLDocumentAdapter;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Example Imixs-Connect endpoint.
 *
 * Receives a filtered XMLDocument from Imixs-Workflow, processes the data,
 * and returns a response XMLDocument to be merged back into the workitem.
 */
@Path("/imixs-hook")
public class ImixsConnectEndpoint {

    private static final String EXPECTED_TOKEN = System.getenv("IMIXS_CONNECT_SECRET");

    /**
     * Handles an incoming Imixs-Connect request.
     *
     * @param authHeader  - the Authorization header, expected: "Bearer <token>"
     * @param xmlDocument - the incoming Imixs XMLDocument with request items
     * @return XMLDocument response with result items to be merged into the workitem
     */
    @POST
    @Consumes(MediaType.APPLICATION_XML)
    @Produces(MediaType.APPLICATION_XML)
    public Response execute(
            @HeaderParam("Authorization") String authHeader,
            XMLDocument xmlDocument) {

        // -- Validate Bearer token --
        if (!isAuthorized(authHeader)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        // -- Convert XMLDocument to ItemCollection for easy access --
        ItemCollection request = XMLDocumentAdapter.putDocument(xmlDocument);

        // -- Read incoming items (as configured in request-params) --
        String senderName = request.getItemValueString("offer.sender.name");
        double totalAmount = request.getItemValueDouble("offer.total.amount");

        // -- Execute your business logic here --
        // e.g. lookup customer in SAP, call an ERP API, query a database...
        ItemCollection result = lookupCustomer(senderName, totalAmount);

        // -- Build XMLDocument response --
        XMLDocument responseDocument = XMLDocumentAdapter.getDocument(result);
        return Response.ok(responseDocument).build();
    }

    /**
     * Example business logic: looks up a customer and returns result data.
     * Replace this with your actual integration logic.
     *
     * @param senderName  - the sender name from the workflow workitem
     * @param totalAmount - the offer total amount from the workflow workitem
     * @return ItemCollection with result items to be merged back into the workitem
     */
    private ItemCollection lookupCustomer(String senderName, double totalAmount) {
        ItemCollection result = new ItemCollection();

        // Add the result items that match the result-params configured in the
        // BPMN event. Items not listed in result-params are ignored by Imixs-Connect.
        result.setItemValue("customer.id", "CUST-4711");
        result.setItemValue("customer.name", "Acme Corp GmbH");
        result.setItemValue("customer.email", "contact@acme.com");

        return result;
    }

    /**
     * Validates the Authorization header against the expected Bearer token.
     * The token is read from the environment variable IMIXS_CONNECT_SECRET.
     *
     * @param authHeader - the raw Authorization header value
     * @return true if the token is valid
     */
    private boolean isAuthorized(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return false;
        }
        String token = authHeader.substring(7);
        return token.equals(EXPECTED_TOKEN);
    }
}
```

---

## Request and Response Structure

### Incoming Request (POST Body)

Imixs-Connect sends only the items configured in `request-params` of the BPMN event. The XMLDocument format looks like this:

```xml
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<document xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xmlns:xs="http://www.w3.org/2001/XMLSchema">
    <item name="offer.sender.name">
        <value xsi:type="xs:string">John Doe</value>
    </item>
    <item name="offer.total.amount">
        <value xsi:type="xs:double">1234.56</value>
    </item>
</document>
```

### Expected Response (200 OK Body)

Your endpoint should return an XMLDocument containing the items configured in `result-params`. Additional items in the response are silently ignored by Imixs-Connect.

```xml
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
```

---

## Error Handling

If your endpoint encounters an error, return an appropriate HTTP error status code. Imixs-Connect will handle the error according to the `on-error` setting in the BPMN event:

| HTTP Status                 | Meaning                               | Imixs-Connect behaviour                                         |
| --------------------------- | ------------------------------------- | --------------------------------------------------------------- |
| `200 OK`                    | Success                               | Result items are merged into the workitem                       |
| `401 Unauthorized`          | Invalid or missing Bearer token       | `AdapterException` or `PluginException` depending on `on-error` |
| `404 Not Found`             | Resource not found in external system | `AdapterException` or `PluginException` depending on `on-error` |
| `500 Internal Server Error` | Unexpected error in your endpoint     | `AdapterException` or `PluginException` depending on `on-error` |

Never return HTTP 200 with an empty body on error — always use the appropriate HTTP status code so that the BPMN modeller can define proper error flows.

---

## BPMN Configuration Reference

The corresponding BPMN event configuration for the example above:

```xml
<imixs-connect>
    <endpoint-id>SAP</endpoint-id>
    <debug>true</debug>
    <on-error>continue</on-error>
    <request-params>offer.sender.name, offer.total.amount</request-params>
    <result-params>customer.id, customer.name, customer.email</result-params>
</imixs-connect>
```

The `endpoint-id` references the entry in `imixs-connect.xml` managed by your administrator:

```xml
<imixs-connect>
    <endpoint id="SAP">
        <url>http://your-server/imixs-hook</url>
        <apikey>${env.IMIXS_CONNECT_SECRET}</apikey>
        <timeout>5000</timeout>
    </endpoint>
</imixs-connect>
```

---

## See Also

- [Imixs XMLDocument Schema](https://www.imixs.org/doc/core/xml/index.html)
- [Imixs REST API](https://www.imixs.org/doc/restapi/index.html)
- [N8N Connector Example](../imixs-connect-n8n/README.md)
