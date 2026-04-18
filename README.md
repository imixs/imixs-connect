# Imixs-Connect

**Imixs-Connect** is a lightweight, webhook-based integration layer for the [Imixs-Workflow](https://www.imixs.org) engine. It allows any external system — such as N8N, SAP, Salesforce, or any custom service — to participate in an Imixs workflow process by implementing a single HTTP endpoint.

Imixs-Connect follows the same architectural principles as [Imixs-AI](https://github.com/imixs/imixs-ai): it integrates into the Imixs processing life cycle via a Signal Adapter, is configured directly in the BPMN model, and keeps sensitive credentials out of the model by delegating them to an admin-managed configuration file.

---

## The Concept

In a typical Imixs workflow, a business process may need to exchange data with an external system at a specific point in the process — for example, looking up a customer ID in SAP when an offer is submitted, or triggering a follow-up action in Salesforce when a contract is signed.

The traditional approach requires building a dedicated client for each external system — a time-consuming effort that also moves responsibility for data correctness into the workflow engine. If the integration breaks, the workflow team is blamed.

**Imixs-Connect inverts this responsibility.**

Instead of implementing a system-specific client, Imixs-Connect calls a generic HTTP endpoint that the external system must provide. The contract is simple and fully defined by Imixs: the request body is a standard [Imixs XMLDocument](https://www.imixs.org/doc/core/xml/index.html) containing a configurable set of workflow items, and the response is an XMLDocument containing the data to be written back into the workitem.

```
Imixs Workflow                        External System (SAP, N8N, ...)
─────────────────                     ──────────────────────────────
WorkItem (ItemCollection)
  → filter request-params
  → XMLDocument                ──POST──►  /your-endpoint
                               ◄─200 ───  XMLDocument (response)
  ← filter result-params
WorkItem (ItemCollection)
```

The external team is responsible for their endpoint. Imixs-Connect is responsible for the call.

---

## How It Works

Integration is configured directly in the BPMN model on a workflow event. The process modeller defines which data items are sent to the external system and which items are written back into the workitem from the response. No credentials are stored in the model.

**BPMN Event Configuration:**

```xml
<imixs-connect>
    <endpoint-id>SAP</endpoint-id>
    <debug>true</debug>

    <request-params>
        <item>offer.sender.name</item>
        <item>offer.total.amount</item>
    </request-params>

    <result-params>
        <item>customer.id</item>
        <item>customer.name</item>
        <item>customer.email</item>
    </result-params>
</imixs-connect>
```

The `endpoint-id` references a named endpoint defined in the admin-managed `imixs-connect.xml` configuration file. This is where the URL and Bearer token are stored — not in the model.

**Admin Configuration (`imixs-connect.xml`):**

```xml
<imixs-connect>
    <endpoint id="SAP">
        <url>http://sap-api-server/imixs-hook</url>
        <apikey>${env.SAP_API_KEY}</apikey>
        <timeout>5000</timeout>
    </endpoint>
    <endpoint id="Salesforce">
        <url>https://sf-middleware.acme.com/imixs-hook</url>
        <apikey>${env.SF_API_KEY}</apikey>
    </endpoint>
</imixs-connect>
```

---

## The Payload Format

Both the request and the response use the standard [Imixs XMLDocument](https://www.imixs.org/doc/core/xml/index.html) format. This format is fully documented, typed, and already used by the Imixs REST API — no proprietary schema needs to be learned.

**POST Request Body →**

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

**← Response Body**

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

The external endpoint receives a filtered snapshot of the workitem and responds with the data to be merged back. The Imixs XMLDocument schema specification is available at [imixs.org/doc/core/xml](https://www.imixs.org/doc/core/xml/index.html).

---

## Security

Authentication uses **Bearer tokens** exclusively, following the same pattern established by [Imixs-AI](https://github.com/imixs/imixs-ai). Tokens are resolved from the `imixs-connect.xml` configuration file and support `${env.VAR_NAME}` placeholders so that secrets can be injected via environment variables at runtime. The BPMN model never contains credentials.

---

## Architecture

Imixs-Connect follows the same 3-layer architecture as Imixs-AI:

| Class | Role |
|---|---|
| `ImixsConnectConfigService` | `@Singleton @Startup` — loads `imixs-connect.xml`, provides URL and API key by endpoint ID |
| `ImixsConnectConnector` | `@Stateless` — Connection Factory, creates `HttpURLConnection` with Bearer auth |
| `ImixsConnectService` | `@Stateless` — Business logic: marshal ItemCollection → POST → unmarshal response → merge |
| `ImixsConnectAdapter` | `SignalAdapter` — BPMN entry point, reads event config, orchestrates the call |

---

## Example: N8N Integration

A complete integration example using [N8N](https://n8n.io) is provided in the [`imixs-connect-n8n`](./imixs-connect-n8n) module. N8N is a popular open-source workflow automation tool with native webhook support — making it an ideal target for an Imixs-Connect endpoint.

The example demonstrates how to:

- Configure an N8N Webhook node as an Imixs-Connect endpoint
- Parse the incoming Imixs XMLDocument in N8N
- Enrich the data using N8N's built-in integrations
- Return a valid XMLDocument response back to Imixs

> *"Configure an N8N Webhook as an Imixs-Connect endpoint in under 10 minutes."*

---

## Modules

| Module | Description |
|---|---|
| `imixs-connect-core` | The core adapter, connector and service classes (Maven JAR) |
| `imixs-connect-n8n` | N8N integration example with Docker Compose |

---

## Maven Dependency

```xml
<dependency>
    <groupId>org.imixs.workflow</groupId>
    <artifactId>imixs-connect-core</artifactId>
    <version>${imixs.connect.version}</version>
</dependency>
```

---

## Requirements

- Imixs-Workflow >= 6.x
- Jakarta EE 10
- Java 11+

---


Imixs-Connect is open source and licensed under the [Eclipse Public License 2.0](LICENSE).
