# OpenHAB REST API — Architectural Findings

Source: <https://www.openhab.org/docs/configuration/restdocs.html>

---

## The fundamental split: Things vs Items

This is the most important design insight from the REST API.

**Things** — the hardware/binding side. Physical device or cloud service.
Managed by bindings. Has status lifecycle. Exposes Channels.

**Items** — the user-facing, automation side. Hold state. Accept commands.
Belong to groups. Carry tags and metadata. Used by rules, UIs, automations.
Have no inherent connection to hardware — they are an abstraction.

**Links (ItemChannelLinks)** — the glue. A Link connects one Item to one
Channel. This decoupling means the same Item can be linked to channels from
different Things, and the same Channel can feed multiple Items.

**The UI never talks to Things directly. It talks to Items.**

---

## Command / state flow

```text
Client → POST /rest/items/{name}     (send command as plain text)
       → Item → Link → Channel → Thing/Binding → device

Device → Thing/Binding → Channel → Link → Item state updated
       → events fired → SSE/WebSocket → client
```

---

## Missing abstractions in our framework (pre-findings)

| Concept    | Status | Where               |
| ---        | ---    | ---                 |
| Thing      | done   | openhab.thing       |
| Channel    | done   | openhab.thing       |
| Bridge     | done   | openhab.bridge      |
| Status     | done   | openhab.thing       |
| Registry   | done   | openhab.registry    |
| Polling    | done   | openhab.polling     |
| Commands   | done   | openhab.commands    |
| Item       | done   | openhab.item        |
| Link       | done   | openhab.link        |
| Events     | done   | openhab.events      |
| Reporting  | done   | openhab.reporting   |
| Projection | done   | openhab.projection  |

---

## Item model (from REST API)

```text
item-name       string    unique stable identifier (e.g. "LivingRoom_Temp")
item-type       string    "Switch", "Dimmer", "Number", "Number:Temperature", etc.
label           string?   human-readable name
category        string?   icon category
tags            set<str>  semantic tags: "Point", "Measurement", "Temperature", etc.
group-names     set<str>  parent group item names
state           string?   current state as REST string ("ON", "21.5 °C", "NULL")
state-type      keyword?  :on-off :decimal :quantity :hsb :percent :open-closed
                          :up-down :play-state :string-type :undef :null
metadata        map       namespace → {:value str :config map}
last-change     Instant?  when state last changed
```

Item types: Switch, Dimmer, Number, Number:\<dimension\>, Color, String, DateTime,
Contact, Rollershutter, Player, Location, Call, Image, Group.

Groups are Items with members and an optional aggregation function.

---

## Link model

```text
item-name    string    the Item being linked
thing-id     string    the Thing owning the Channel
channel-id   keyword   the Channel on the Thing
profile      string?   e.g. "system:default"
```

Composite key: [item-name thing-id channel-id]

---

## Event system (SSE + WebSocket)

### Topic patterns

```text
openhab/items/{name}/statechanged       ItemStateChangedEvent
openhab/items/{name}/state              ItemStateEvent (every update)
openhab/items/{name}/command            ItemCommandEvent
openhab/items/{name}/added              ItemAddedEvent
openhab/items/{name}/removed            ItemRemovedEvent
openhab/things/{uid}/statuschanged      ThingStatusInfoChangedEvent
openhab/things/{uid}/status             ThingStatusInfoEvent
openhab/things/{uid}/added              ThingAddedEvent
openhab/things/{uid}/removed            ThingRemovedEvent
openhab/channels/{uid}/triggered        ChannelTriggeredEvent
```

### Event wire format (SSE)

```json
{
  "type":    "ItemStateChangedEvent",
  "topic":   "openhab/items/LivingRoom_Temp/statechanged",
  "payload": "{\"type\":\"Quantity\",\"value\":\"21.5 °C\",\"oldType\":\"Quantity\",\"oldValue\":\"20.0 °C\"}",
  "source":  ""
}
```

payload is double-serialized JSON (string within JSON).

### State types (the `type` field in payloads)

OnOff, Decimal, Quantity, HSB, Percent, OpenClosed, UpDown, PlayState,
StringType, UnDef, Null, DateTimeType, RawType, StringList.

### SSE endpoints

GET /rest/events?topics=...           full event bus, topic-filtered
GET /rest/events/states               item-state-only stream → returns connectionId
POST /rest/events/states/{connectionId}  update tracked item set for this connection

### WebSocket

ws://{host}/ws  — bidirectional; requires PING heartbeat every ~5s
Same event type taxonomy as SSE.

---

## REST API resource summary

| Resource      | Base path            | Notes                              |
| ---           | ---                  | ---                                |
| Items         | /rest/items          | Primary user-facing resource       |
| Things        | /rest/things         | Admin-only in production           |
| Links         | /rest/links          | ItemChannelLink management         |
| Thing Types   | /rest/thing-types    | Read-only schemas                  |
| Channel Types | /rest/channel-types  | Read-only schemas                  |
| Sitemaps      | /rest/sitemaps       | UI navigation trees                |
| Rules         | /rest/rules          | Automation rules                   |
| Inbox         | /rest/inbox          | Discovered things pending approval |
| Persistence   | /rest/persistence    | Historical state queries           |
| Events        | /rest/events         | SSE stream                         |

---

## Key design implication for our framework

The presentation layer (REST API + SSE/WebSocket) should be thin:

- GET /things, /items, /links → read from registry atom
- POST /items/{name} → call openhab.commands/dispatch!
- GET /events → subscribe to openhab.events publication

The framework's job is to keep the registry correct.
The presentation layer's job is to expose it.

---

## Clojure HTTP/Web options

The external API should stay a thin edge over registry reads, `commands/dispatch!`, and event subscriptions. That points toward small composable libraries rather than a heavy full-stack framework.

| Option | Fit | Notes |
| --- | --- | --- |
| Ring | foundation | Standard Clojure HTTP abstraction. Good as the portable base layer regardless of routing/server choice. |
| Reitit | strong default | Data-driven routing, coercion, OpenAPI support, Ring integration. Fits this project's data-first style and keeps REST endpoints thin. |
| http-kit | Ring adapter for SSE/WS | Provides `as-channel` for SSE event streaming and WebSocket; pairs with Reitit for routing. The natural server choice when Ring + Reitit is the routing layer. |
| Pedestal | powerful but heavier | Interceptor model is excellent for cross-cutting concerns, but adds more framework weight than the thin API edge requires. |
| Aleph | async/networking-heavy | Strong async stack for high concurrency, but introduces Manifold/Aleph concepts that are not necessary until the API edge proves it needs them. |
| Lacinia | GraphQL layer | Good match if we choose GraphQL deliberately: registry graph reads map well to queries, commands map to mutations, and the event bus maps to subscriptions. Do not make core depend on it. |
| Kit/Biff | application frameworks | Useful for building a product shell, auth, pages, persistence, etc. Too opinionated for the core API edge. |

Recommendation: Ring + Reitit + http-kit. Reitit handles routing; http-kit is the Ring adapter and provides the `as-channel` API for SSE streaming and WebSocket. Together they are idiomatic, data-driven, and thin. Keep GraphQL/Lacinia as a parallel API option once the read model and subscription semantics are stable; do not couple the core registry/transition model to either REST or GraphQL.
