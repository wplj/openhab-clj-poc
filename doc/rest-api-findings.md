# HTTP API Research

Reference baseline: <https://www.openhab.org/docs/configuration/restdocs.html>

---

## Domain model: Things vs Items

**Things** — the hardware/binding side. A physical device or cloud service managed by a binding. Has a status lifecycle. Exposes Channels.

**Items** — the automation and UI side. Hold state. Accept commands. Belong to groups. Carry tags and metadata. Used by rules, UIs, and automations. Items have no inherent connection to hardware — they are an abstraction layer over device state.

**Links (ItemChannelLinks)** — the binding between an Item and a Channel. A single Item maps to at most one Channel; a single Channel can feed multiple Items.

**The UI and rules layer never address Things directly. They address Items.**

---

## Command and state flow

```text
Client → POST /rest/items/{name}     (send command as plain text)
       → Item → Link → Channel → Thing/Binding → device

Device → Thing/Binding → Channel → Link → Item state updated
       → events fired → SSE/WebSocket → client
```

---

## Item

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

## Link

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

`payload` is double-serialized JSON (a JSON string containing JSON).

### State types (the `type` field in payloads)

OnOff, Decimal, Quantity, HSB, Percent, OpenClosed, UpDown, PlayState,
StringType, UnDef, Null, DateTimeType, RawType, StringList.

### SSE endpoints

```text
GET  /rest/events?topics=...              full event bus, topic-filtered
GET  /rest/events/states                  item-state-only stream → returns connectionId
POST /rest/events/states/{connectionId}   update tracked item set for this connection
```

### WebSocket

`ws://{host}/ws` — bidirectional; requires PING heartbeat every ~5 s. Uses the same event type taxonomy as SSE.

---

## OpenHAB REST API resource summary

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

## Proposed HTTP API direction

The HTTP layer is a thin read/write edge over the registry and event bus:

- `GET /things`, `/items`, `/links` — read from the registry atom
- `POST /items/{name}` — call `openhab.commands/dispatch!`
- `GET /events` — subscribe to the `openhab.events` publication

The framework keeps the registry correct; the HTTP layer should expose it without owning domain behavior.

---

## Clojure HTTP stack

| Option | Role | Notes |
| --- | --- | --- |
| Ring | HTTP abstraction | Standard portable base layer; works with any adapter. |
| Reitit | Routing | Data-driven routing, coercion, OpenAPI support. Fits the data-first style of this codebase. |
| http-kit | Ring adapter / SSE / WS | Provides `as-channel` for SSE event streaming and WebSocket. The natural adapter alongside Ring + Reitit. |
| Pedestal | Alternative framework | Interceptor model handles cross-cutting concerns well, but adds more framework weight than the thin edge needs. |
| Aleph | Async networking | Strong for high-concurrency workloads, but pulls in Manifold semantics that the current design does not require. |
| Lacinia | GraphQL | Registry graph reads map to queries, commands to mutations, event bus to subscriptions. Keep as a parallel option; do not couple the core model to it. |
| Kit/Biff | Application frameworks | Appropriate for a product shell (auth, pages, persistence). Too opinionated for the core API edge. |

**Recommendation:** Ring + Reitit + http-kit. Reitit handles routing; http-kit is the Ring adapter and provides `as-channel` for SSE streaming and WebSocket. Keep GraphQL/Lacinia as a parallel option once the read model and subscription semantics are stable.
