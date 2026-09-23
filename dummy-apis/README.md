# Dummy APIs

Stand-ins for three real backend services, so `chatbot-demo` can be tested
end-to-end before your actual APIs exist. Not real identity checking or
application data - just deterministic, obviously-fake logic.

## Run it

```bash
mvn spring-boot:run
```

Starts on **port 9001** with three endpoints:

| Endpoint | Simulates | Behavior |
|---|---|---|
| `POST /api/security-check` | SecurityCheckApi | Fails if the `address` field contains "fail" (case-insensitive); passes otherwise |
| `GET /api/applications/{pcn}` | ApplicationStatusAPI | Returns `404` if the PCN contains "NOTFOUND" (case-insensitive); otherwise one of `APP100`-`APP500` (deterministic per PCN) |
| `POST /api/application-updates` | End-of-chat update API | Logs the summary it receives to the console |

`chatbot-demo`'s `application.yml` already points at `localhost:9001` for
all three by default - just run both apps and everything connects.

## Testing the negative paths

Two distinct failure scenarios, triggered independently:

- **Verification decline**: when asked for your address, type anything
  containing the word "fail" (e.g. "123 Fail Street"). `chatbot-demo`
  ends the session with its verification-failed message.
- **PCN not found**: once verified, give a PCN containing "NOTFOUND"
  (e.g. "NOTFOUND123" - needs a digit to pass `chatbot-demo`'s PCN
  regex). This returns a real `404`, which `chatbot-demo` reports to the
  customer as "no application found with that PCN" - a different message
  than what it says when this service is simply unreachable (stop this
  app running to test that second case).

## This is a stand-in, not a real service

No validation, no persistence, no auth. When your real APIs are ready,
point `chatbot-demo`'s `application.yml` at them instead and stop running
this project.
