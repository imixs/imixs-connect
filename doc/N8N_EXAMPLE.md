# Imixs-Connect Demo: Customer Validation with N8N

This tutorial shows how to integrate N8N into an Imixs workflow process using Imixs-Connect.
A user submits an offer in Imixs-Workflow. At the moment of submission, Imixs-Connect
automatically calls an N8N Webhook that validates the company name and enriches the
workitem with full customer data — without any custom Java code.

## The Use Case

```
Imixs-Workflow                    N8N
──────────────                    ──────────────────────────
User submits offer
  → offer.company = "Acme"
                      ──POST──►  Webhook Node
                                 │
                                 ├── IF: company known?
                                 │     YES → customer data
                                 │     NO  → empty response
                                 │
                                 └── Respond to Webhook
                      ◄─200 ───  XMLDocument response
  ← customer.id     = "CUST-4711"
  ← customer.name   = "Acme Corp GmbH"
  ← customer.status = "GOLD"
Workitem enriched with
customer data ✅
```

---

## Prerequisites

- Imixs-Workflow is running with `imixs-connect-core` deployed
- N8N is running and reachable at `http://localhost:5678`
- `imixs-connect.xml` is configured with the N8N endpoint (see [Setup](../README.md))

---

## Part 1: Build the N8N Workflow

Open N8N at `http://localhost:5678` and create a new workflow. You will add 5 nodes.

### Node 1 — Webhook

This node receives the incoming POST request from Imixs-Connect.

Add a **Webhook** node and configure it as follows:

| Field          | Value                             |
| -------------- | --------------------------------- |
| HTTP Method    | `POST`                            |
| Path           | `imixs-connect`                   |
| Authentication | `Header Auth`                     |
| Response Mode  | `Using "Respond to Webhook" node` |

For the **Header Auth** credential click **Create New Credential** and set:

| Field | Value                      |
| ----- | -------------------------- |
| Name  | `Authorization`            |
| Value | `Bearer your-secret-token` |

> **Important:** The `Name` field must be exactly `Authorization`. The `Value` field
> must contain the full header value including the `Bearer ` prefix. Imixs-Connect
> sends `Authorization: Bearer <apikey>` — so the credential Value and the apikey
> in `imixs-connect.xml` together must form the correct header:
>
> | imixs-connect.xml `<apikey>` | N8N Credential `Value`     |
> | ---------------------------- | -------------------------- |
> | `your-secret-token`          | `Bearer your-secret-token` |

You can verify the Webhook is reachable and the token works with curl:

```bash
curl -v -X POST http://localhost:5678/webhook-test/imixs-connect \
  -H "Authorization: Bearer your-secret-token" \
  -H "Content-Type: application/xml" \
  -d '<?xml version="1.0" encoding="UTF-8"?><document></document>'
```

A correct response returns `HTTP 200`.

---

### Node 2 — Code: Parse incoming XMLDocument

Imixs-Connect sends a standard Imixs XMLDocument as the POST body. Add a **Code**
node (JavaScript) to extract the item values:

```javascript
// Parse the incoming Imixs XMLDocument and extract item values
const bodyXml = $input.first().json.body;

// Helper: extract item value by name from Imixs XMLDocument
function getItemValue(xml, itemName) {
  const regex = new RegExp(
    `<item name="${itemName}">\\s*<value[^>]*>([^<]*)</value>`,
    "i",
  );
  const match = xml.match(regex);
  return match ? match[1].trim() : null;
}

const company = getItemValue(bodyXml, "offer.company");
const email = getItemValue(bodyXml, "offer.email");

return [{ json: { company, email } }];
```

---

### Node 3 — IF: Company known?

Add an **IF** node to check whether the company name is recognized.

| Field     | Value                 |
| --------- | --------------------- |
| Value 1   | `{{ $json.company }}` |
| Operation | `Contains`            |
| Value 2   | `Acme`                |

- **True branch** → customer is known, continue to Node 4a
- **False branch** → unknown customer, continue to Node 4b

---

### Node 4a — Code: Build customer response (True branch)

Add a **Code** node on the **True** branch to build the Imixs XMLDocument response:

```javascript
// Build a valid Imixs XMLDocument response with customer data
const xml = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<document xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xmlns:xs="http://www.w3.org/2001/XMLSchema">
    <item name="customer.id">
        <value xsi:type="xs:string">CUST-4711</value>
    </item>
    <item name="customer.name">
        <value xsi:type="xs:string">Acme Corp GmbH</value>
    </item>
    <item name="customer.status">
        <value xsi:type="xs:string">GOLD</value>
    </item>
</document>`;

return [{ json: { xml } }];
```

---

### Node 4b — Code: Empty response (False branch)

Add a **Code** node on the **False** branch for unknown companies:

```javascript
// Return an empty but valid Imixs XMLDocument
const xml = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<document xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xmlns:xs="http://www.w3.org/2001/XMLSchema">
</document>`;

return [{ json: { xml } }];
```

---

### Node 5 — Respond to Webhook

Add a **Respond to Webhook** node and connect both Code nodes (4a and 4b) to it.

| Field         | Value             |
| ------------- | ----------------- |
| Respond With  | `Text`            |
| Response Code | `200`             |
| Response Body | `{{ $json.xml }}` |

Add a custom response header so that Imixs-Connect can correctly parse the response
as an Imixs XMLDocument:

| Header Name    | Header Value      |
| -------------- | ----------------- |
| `Content-Type` | `application/xml` |

> **Important:** The `Content-Type: application/xml` response header is required.
> Without it Imixs-Connect cannot unmarshal the response and will throw a
> `PluginException`.

Your final N8N workflow looks like this:

```
[Webhook] → [Code: Parse XML] → [IF: company known?]
                                        │
                            ┌───────────┴───────────┐
                      TRUE  │                        │  FALSE
                            ▼                        ▼
                [Code: Customer data]   [Code: Empty response]
                            │                        │
                            └───────────┬────────────┘
                                        ▼
                              [Respond to Webhook]
```

**Activate the workflow** using the toggle in the top right corner of N8N.

---

## Part 2: Configure the BPMN Model

Open your BPMN process model in [Open-BPMN](https://www.imixs.org/doc/modelling/index.html)
and find the event where the offer is submitted (e.g. the "Submit" transition).

Add the signal adapter class `org.imixs.connect.workflow.ImixsConnectAdapter` and the
following Imixs-Connect configuration to the event's **Signal** definition:

```xml
<imixs-connect>
    <endpoint-id>N8N</endpoint-id>
    <debug>true</debug>
    <on-error>continue</on-error>
    <request-params>offer.company, offer.email</request-params>
    <result-params>customer.id, customer.name, customer.status</result-params>
</imixs-connect>
```

| Parameter        | Description                                                                                        |
| ---------------- | -------------------------------------------------------------------------------------------------- |
| `endpoint-id`    | References the `N8N` entry in `imixs-connect.xml`                                                  |
| `debug`          | Logs request and response XML to the server log                                                    |
| `on-error`       | `continue` — if N8N is unreachable the workflow continues, error is stored in `adapter.error_code` |
| `request-params` | Items sent to N8N — only these fields are exposed                                                  |
| `result-params`  | Items read from the N8N response and merged into the workitem                                      |

### Optional: Add an Error Flow

If you want the workflow to react when N8N is unreachable, add a conditional
sequence flow on the Submit event:

```
"" != workitem.getItemValueString("adapter.error_code")
```

This allows you to route the process to a fallback state — for example a manual
review task — instead of silently continuing.

---

## Part 3: Test It

1. Open a workitem in your Imixs application
2. Enter `offer.company` = `Acme` and `offer.email` = `test@acme.com`
3. Submit the workitem
4. Check the server log for the `debug` output — you should see the request and
   response XML
5. After processing, the workitem should contain:

| Item              | Value            |
| ----------------- | ---------------- |
| `customer.id`     | `CUST-4711`      |
| `customer.name`   | `Acme Corp GmbH` |
| `customer.status` | `GOLD`           |

In N8N you can inspect the execution history under **Executions** to see the
full request/response flow for each trigger.

---

## See Also

- [Imixs-Connect README](../README.md)
- [Implementing an Endpoint in Java](./endpoint-java.md)
- [Imixs XMLDocument Schema](https://www.imixs.org/doc/core/xml/index.html)
- [Imixs Adapter API](https://www.imixs.org/doc/core/adapter-api.html)
