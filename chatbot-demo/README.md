# Chatbot Demo (Spring Boot)

A customer-facing chatbot, scoped to one job: **verify the customer, then
let them check their application status by PCN.**

- A **Spring Boot backend** (`/api/chat`) that calls a local **Ollama**
  server running an open-weight model, and keeps a short per-visitor
  conversation history in memory.
- A **floating chat widget** (`widget.js` + `widget.css`) that opens when
  a customer clicks a "Chat with us" link on the page.
- **Conversational identity verification before anything else**: the bot
  gathers name, address, and date of birth naturally - any order, several
  in one message, side questions handled gracefully - then calls a real
  `SecurityCheckApi`. Only on success does the customer reach the actual
  chat - a failure closes the session. The model collects the data; your
  code, not the model, decides when to make that real API call.
- **Deterministic PCN-triggered status lookup**: the moment a customer
  types something that looks like a PCN, the backend calls
  `ApplicationStatusAPI` directly - not the model's decision.
- **Status codes never reach the customer or the model** - only messages
  mapped from a properties file do.
- An **end-of-chat summary** posted to a separate update API once the
  customer is done. Verification transcripts (name/address/DOB) are
  cleared the moment verification passes, so that PII never gets
  summarized or sent anywhere.
- A sibling **`dummy-apis`** project (see its own README) simulating all
  three backend APIs, so this all runs end-to-end today.

No API key, no billing, no internet call for inference - the model runs
on your own machine. This is a proof-of-concept setup; see section 10 for
what changes if you later want a hosted provider instead.

## Why not MCP for the API integrations?

MCP (Model Context Protocol) is for exposing tools to *external* AI
clients over a separate protocol boundary - useful when several different
AI clients need to share tool implementations, or the tools live in an
independently-deployed service. Here, one Spring Boot app owns both the
LLM client and the tool implementations, so MCP would add a server
process, a client library, and protocol translation with no one else
consuming the tools. The `ChatTool` interface already provides the
"wrapper around real APIs" separation - it's just in-process. If these
tools ever need to serve other AI clients, `ChatTool` is the seam to
expose as an MCP server later without touching the rest of the app.

## 1. Install Ollama and pull a model

```bash
ollama pull llama3.2
```

Confirm the server is up (most installers run it as a background service,
so you usually don't need `ollama serve` yourself):

```bash
curl http://localhost:11434
```

## 2. Run the dummy backend APIs

In the sibling `dummy-apis` folder:

```bash
mvn spring-boot:run
```

This starts `SecurityCheckApi`, `ApplicationStatusAPI`, and the update
endpoint on port 9001 - see `dummy-apis/README.md` for exactly what each
one does.

## 3. Run the Spring Boot chatbot

In a separate terminal, in this folder:

```bash
mvn spring-boot:run
```

Open http://localhost:8080, click **Chat with us**, and have a normal
conversation to verify yourself - e.g. "Hi, I'm Jane Doe, I live at 123
Fail Street" then follow up with your date of birth when asked (using
"Fail" in the address triggers the dummy decline path on purpose - see
`dummy-apis/README.md`). Try a normal address afterward (new tab or
reload for a fresh session) to get through to the status check.

## 4. How verification works

The model conducts a natural conversation to collect the fields, but the
decision to actually call `SecurityCheckApi` is **always code, never the
model's call**:

1. Widget opens → calls `POST /api/chat/start`. `ChatController.start(...)`
   sends the fixed `chatbot.verification.opening-prompt` - no model call
   yet, since there's nothing to extract.
2. Every subsequent message goes to
   `OllamaChatService.converseForVerification(...)`, which gives the
   model a `record_customer_info` tool. The model may call it with
   whatever fields the customer just gave it - one, several, or none, in
   any order - and otherwise just replies naturally (including handling
   "why do you need this?" side questions).
3. After each turn, `ChatController` merges whatever was extracted into
   that session's answers and checks, in plain code, whether every
   configured field is now non-blank. This check - not the model - is
   what decides whether verification is "done."
4. The **first turn** every required field becomes filled,
   `SecurityCheckApiClient` does a real `POST` to your SecurityCheckApi
   with `{ sessionId, fields }`. The model is not asked and gets no vote
   on this - it only ever sees the pre-written result message afterward,
   never something it phrases itself.
5. **Success** → session marked `VERIFIED`, the verification transcript
   is cleared (so PII doesn't linger into the real chat's history or
   summary), and the customer's next messages go to the model as normal.
   **Failure** → session marked `FAILED`; the widget receives
   `sessionEnded: true` and locks input.
6. If SecurityCheckApi itself can't be reached, the session **fails
   closed** rather than letting the customer through. This is a default,
   not a hard requirement - flip it in
   `ChatController.handleVerificationStep(...)` if your business wants
   fail-open instead.
7. `chatbot.verification.max-turns` (default 8) caps how many exchanges
   verification can take before it fails closed on its own - a safety
   net against an open-ended back-and-forth that never completes.

**Why the customer never sees a model-authored "you're verified"**: the
`VERIFIED_GREETING` / `VERIFICATION_FAILED_MESSAGE` /
`VERIFICATION_UNAVAILABLE_MESSAGE` constants in `ChatController.java` are
the *only* text ever shown for a pass/fail/error outcome. The model's
only job is collecting data through natural conversation; it never
authors the verdict itself.

**To add, remove, or reorder a field to collect**: edit
`chatbot.verification.fields` in `application.yml` only - no Java
changes needed. Each field needs a `key` (sent to SecurityCheckApi) and
a `label` (used in the model's instructions, e.g. "current address").

**To point this at your real API**: edit `SecurityCheckApiClient.java`.
It currently assumes `POST {base-url}` with body
`{ sessionId, fields: { key: value... } }`, returning
`{ "verified": true|false, "reason": "..." }`.

**A trade-off worth knowing**: because extraction is now up to the
model, there's a small chance it mis-attributes something a customer
says (e.g. reading part of an address as a name). The `record_customer_info`
tool's description explicitly tells the model never to guess or invent
values, but this isn't a hard guarantee the way the old fixed-order
script was. If your real SecurityCheckApi is strict about exact-match
formatting, consider validating/normalizing fields there rather than
assuming what reaches it is perfectly clean.

## 5. How the PCN status lookup works, including the code mapping

1. `PcnExtractor` scans each message with a regex the moment it arrives.
2. If a PCN-shaped token is found, `ChatController` calls
   `ApplicationStatusApiClient.getStatus(pcn)` directly - a real `GET`,
   not a model decision.
3. The response's `statusCode` (e.g. `"APP200"`) is passed to
   `ApplicationStatusMessageMapper`, which looks it up in
   `application-status-messages.properties` and returns the mapped
   sentence. **The raw code itself is discarded here** - it never gets
   injected into the conversation, so the model (and therefore the
   customer) only ever sees the approved message text.
4. That message is injected into the conversation as a system turn, and
   the model relays it to the customer.
5. `get_application_status` also exists as a model-facing tool
   (`ApplicationStatusTool.java`) for PCNs the regex misses - it applies
   the same code-to-message mapping before returning anything to the
   model, so this guarantee holds on both paths.

**Two distinct failure cases, two distinct messages**: a `404` from
`ApplicationStatusAPI` (a real "this PCN doesn't exist" response) is
handled separately from every other failure (timeout, connection
refused, 5xx). The customer hears "no application found with that PCN,
please double-check it" for the first, and a generic "temporary issue,
try again shortly" for the second - so a typo'd PCN doesn't get
mistaken for your API being down, or vice versa. See the
`HttpClientErrorException.NotFound` catch block in
`ChatController.handlePcnLookup(...)` (and the equivalent in
`ApplicationStatusTool.execute(...)`). `dummy-apis` simulates the 404
case for any PCN containing "NOTFOUND" - see its README.

**To add a new status code**: add one line to
`application-status-messages.properties` - `CODE=Message text.` Any code
without a mapping falls back to the `UNKNOWN` line, so a new code from
your real API never leaks through unmapped.

**To point this at your real API**: edit `ApplicationStatusApiClient.java`
the same way as the security-check client - adjust the URL, method, and
the `ApplicationStatus` record's fields.

## 6. How the end-of-chat summary flows

1. Customer clicks **End chat** → `POST /api/chat/end`.
2. `OllamaChatService.summarize(...)` makes a tool-free call to Ollama to
   condense the conversation.
3. `ApplicationUpdateApiClient.submit(...)` posts the summary (with the
   PCN, filled in automatically from whatever was detected during the
   session) to your update API.

## 7. Files that matter

| File | Purpose |
|---|---|
| `ChatController.java` | `/api/chat/start`, `/api/chat`, `/api/chat/end`; verification gate + PCN lookup |
| `VerificationProperties.java` | Binds the configurable verification question list |
| `VerificationStore.java` | Per-session verification progress and pass/fail status |
| `SecurityCheckApiClient.java` | Real call to your SecurityCheckApi |
| `PcnExtractor.java` | Regex-based PCN detection from a raw message |
| `ApplicationStatusApiClient.java` | Real `GET` call to your status API (returns a code) |
| `ApplicationStatusMessageMapper.java` | Maps a status code to the approved customer message |
| `application-status-messages.properties` | The code-to-message mapping table |
| `ApplicationStatusTool.java` | Model-facing fallback tool for status lookup |
| `ApplicationUpdateApiClient.java` | Real `POST` of the end-of-chat summary |
| `OllamaChatService.java` | Talks to Ollama; runs the tool-call loop; summarizes |
| `ChatTool.java` | Interface for any tool the model can call |
| `ChatTurn.java` | Shared `{role, content}` message type |
| `ConversationStore.java` | In-memory per-session history + remembered PCN |
| `ChatRateLimiter.java` | Caps messages per session per minute |
| `CorsConfig.java` | Restricts which origins can call `/api/**` |
| `application.yml` | System prompt, verification fields, PCN pattern, tool API URLs |
| `static/index.html` / `widget.js` | The demo page and embeddable chat panel |

## 8. Troubleshooting

- **"Could not reach Ollama" / 502 from `/api/chat`**: confirm with
  `curl http://localhost:11434`.
- **Verification always fails**: make sure `dummy-apis` is running on
  9001, and your test address doesn't contain "fail".
- **Verification never completes / keeps asking**: `llama3.2` may not be
  calling `record_customer_info` reliably (small models can be
  inconsistent tool-callers - the same caveat as `get_application_status`
  from the status-lookup flow). Try `qwen2.5`. It fails closed after
  `chatbot.verification.max-turns` exchanges either way, so it won't hang
  forever.
- **A field got recorded with the wrong value**: this is the trade-off of
  conversational extraction vs. the old fixed-order script - see the
  callout at the end of section 4.
- **PCN isn't being detected**: the placeholder regex is generic on
  purpose - tighten `chatbot.pcn.pattern` to your real PCN format.
- **Bot answers off-topic questions anyway**: this only applies once
  verified - prompt-based scoping isn't perfect with small local models;
  try `qwen2.5` or make the refusal instruction more explicit.
- **Status message doesn't change**: the dummy API picks a code
  deterministically from the PCN's hash, so a given PCN always maps to
  the same message - try a different PCN.
- **"Chat summary was generated but could not be saved"**: the model
  produced a summary fine, but the update API couldn't be reached.

## 9. Before you put this in front of real customers

- **Persistence**: swap `ConversationStore`/`VerificationStore` for Redis
  or a database keyed by `sessionId`, with a TTL.
- **Auth / abuse**: the rate limiter is per-JVM and resets on restart -
  use a shared store for multiple instances.
- **Auth on your three backend APIs**: none of the API clients send
  credentials right now - add whatever your real APIs require.
- **PII handling**: name, address, DOB, and PCNs flow through this app in
  plaintext right now. Don't log full message bodies or verification
  answers if this becomes anything beyond a local POC - log request IDs
  and status codes instead, and check what your compliance requirements
  say about storing this in `ConversationStore`'s in-memory history at
  all.
- **CORS**: update `chatbot.cors.allowed-origins` to your real domain(s).
- **Hosting the model**: run Ollama on a proper server with a GPU, or
  switch to a hosted provider - see section 10.

## 10. Swapping the model provider later

Only `OllamaChatService.java` and `OllamaApiDtos.java` know about
Ollama's request/response shape. To call a different chat API, write a
new service implementing the same `ask(List<ChatTurn>)` and
`summarize(List<ChatTurn>)` methods, and point `ChatController`'s
constructor at it. `ChatTool` implementations stay the same; only the
translation into the new provider's own tool-calling format needs new
mapping code.
