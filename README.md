# Imixs-Connect

**Imixs-Connect** is a universal lightweight integration layer for the [Imixs-Workflow](https://www.imixs.org) engine. It allows an easy integration of external systems — such as SAP, N8N, Salesforce, or any custom service — to participate in an Imixs workflow process by implementing a single HTTP endpoint.

Imixs-Connect follows a generic architectural principle and integrates into the Imixs processing life cycle via a Signal Adapter. Each connector is configured directly in the BPMN model, and keeps sensitive credentials out of the model by delegating them to an admin-managed configuration file.

---

## The Concept

In a typical Imixs workflow, a business process may need to exchange data with an external system at a specific point in the process — for example, looking up a customer ID in SAP when an offer is submitted, or triggering a follow-up action in Salesforce when a contract is signed.

One approach is building a dedicated client for each external system — a time-consuming effort that also moves responsibility for data correctness into the workflow engine. If the integration breaks, the workflow team is blamed. With the Imixs-Connect architecture you can solve these kind of integrations in a much more lightweight way.

**Imixs-Connect inverts this responsibility.**

Imixs-Connect provides a generic connector API that can be easily adopted. The Imixs-Connect Adapter calls a generic HTTP endpoint provided by an external system. The contract is easy to implement and fully defined by Imixs XML: the request body is a standard [Imixs XMLDocument](https://www.imixs.org/doc/core/xml/index.html) containing a configurable set of workflow items, and the response is an XMLDocument containing the data to be written back into the workitem.

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

The external team holds full control over their endpoint. Imixs-Connect is responsible for the call.

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
    <endpoint id="N8N">
        <url>https://n8n.my-company.com/webhook/imixs</url>
        <apikey>${env.N8N_API_KEY}</apikey>
    </endpoint>
</imixs-connect>
```

---

## The Payload Format

Both the request and the response use the standard [Imixs XMLDocument](https://www.imixs.org/doc/core/xml/index.html) format. This format is fully documented, typed, and already used by the Imixs REST API — no proprietary schema needs to be learned by the external team.

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

The full Imixs XMLDocument schema specification is available at [imixs.org/doc/core/xml](https://www.imixs.org/doc/core/xml/index.html).

---

## Security

Authentication uses **Bearer tokens** exclusively. Tokens are resolved from the `imixs-connect.xml` configuration file and support `${env.VAR_NAME}` placeholders so that secrets can be injected via environment variables at runtime. The BPMN model never contains credentials.

---

## Architecture

Imixs-Connect is built around a clean 3-layer architecture. The `imixs-connect-core` module provides the framework — all connector modules build on top of it without modifying it.

| Class                       | Role                                                                                       |
| --------------------------- | ------------------------------------------------------------------------------------------ |
| `ImixsConnectConfigService` | `@Singleton @Startup` — loads `imixs-connect.xml`, provides URL and API key by endpoint ID |
| `ImixsConnectConnector`     | `@Stateless` — Connection Factory, creates `HttpURLConnection` with Bearer auth            |
| `ImixsConnectService`       | `@Stateless` — Business logic: marshal ItemCollection → POST → unmarshal response → merge  |
| `ImixsConnectAdapter`       | `SignalAdapter` — BPMN entry point, reads event config, orchestrates the call              |

---

## Connector Library

The real power of Imixs-Connect lies in its **connector library**. Because the protocol is always the same — a standard XMLDocument POST — adding support for a new integration target requires no Java code at all. Each connector module contains ready-to-use configuration examples, Docker Compose setups, and importable workflow templates for its target platform.

```
imixs-connect/
├── imixs-connect-core/           ← the framework (Java)
│
├── imixs-connect-n8n/            ← N8N
├── imixs-connect-make/           ← Make (formerly Integromat)
├── imixs-connect-zapier/         ← Zapier
└── imixs-connect-powerautomate/  ← Microsoft Power Automate
```

Each connector module follows the same structure:

```
imixs-connect-n8n/
├── README.md                        ← step-by-step integration guide
├── docker-compose.yml               ← run Imixs + N8N locally
├── workflows/
│   └── imixs-hook-example.json      ← importable N8N workflow template
└── bpmn/
    └── example-process.bpmn         ← matching BPMN process model
```

Community contributions of new connector modules are very welcome — see [Contributing](#contributing).

---

## Modules

| Module                                       | Description                                                         |
| -------------------------------------------- | ------------------------------------------------------------------- |
| [`imixs-connect-core`](./imixs-connect-core) | Core adapter, connector and service classes (Maven JAR)             |
| [`imixs-connect-n8n`](./imixs-connect-n8n)   | N8N integration example with Docker Compose and importable workflow |
| [`imixs-connect-make`](./imixs-connect-make) | Make (Integromat) integration example                               |

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

## Contributing

Imixs-Connect is designed to grow through community contributions. The most valuable contributions are **new connector modules** for additional integration platforms. Since no Java code is required — only documentation, Docker Compose, and platform-specific configuration — the barrier to contribute is intentionally low.

Please open an issue or start a discussion before submitting a new connector module.

---

## License

Imixs-Connect is open source and licensed under the [Eclipse Public License 2.0](LICENSE).
