# Urja Meter API

Urja Meter Ops is a web portal that utility staff use to look up smart meters. It works fine for people clicking around, but it has no API, so anyone who needs the data in a program ends up copy-pasting from the browser.

This project is a small service that sits in front of the portal. It logs in the same way a person would, pulls the data out, cleans it up and serves it as normal JSON endpoints. It also answers a few things the portal can't, like "show me only the faulty meters" or "which meters are on this transformer".

Other files:
- [PROTOCOL.md](PROTOCOL.md): how the portal actually works, from my investigation. Probably the most useful thing in this repo.
- [openapi.json](openapi.json): OpenAPI 3.1 description of this API, generated from the code with springdoc. While the app is running you can also browse it at http://localhost:8080/swagger-ui.html
- Reflection is at the bottom of this file.

## Endpoints

I started by asking who would use this (product and data teams) and what they'd want to ask. Mostly it's what staff already do in the portal, plus things the portal can't do. Each question turned into one endpoint:

| Question | Endpoint |
|---|---|
| All meters | `GET /api/meters` |
| Only some of them (status, make, phase, transformer, feeder) | `GET /api/meters?status=Faulty&make=Genus` |
| Search by meter number or serial | `GET /api/meters?q=J10001` |
| One meter (details, where it sits in the network, location) | `GET /api/meters/{meterId}` |
| How much electricity it used | `GET /api/meters/{meterId}/readings` |
| All transformers, with how many meters each one feeds | `GET /api/transformers` |
| One transformer | `GET /api/transformers/{code}` |
| Meters under a transformer | `GET /api/transformers/{code}/meters` |
| Is the data loaded and how old is it | `GET /api/status` |

## How it works

There are three parts:

```
your request
    -> controllers (api/)      receive the request, apply filters, return JSON
    -> MeterStore (store/)     keeps all meters and transformers in memory
    -> PortalClient (portal/)  the only part that talks to the portal
    -> Urja portal
```

- **PortalClient** logs in, keeps the session cookie, logs in again when the session expires (the session cookie lasts an hour, and without a valid session the portal returns 401), and signs the bulk export request.
- **MeterStore** downloads all meters (through the signed export, one request) and all transformers when the app starts, then again every 10 minutes. If a refresh fails it keeps the old data.
- **Readings** aren't cached. They're fetched from the portal each time and cleaned up in `ApiMapper`.

Main files:

```
src/main/java/com/suyash/urjaapi/
  portal/PortalClient.java     login, session, re-login on 401, portal calls
  portal/RequestSigner.java    HMAC-SHA256 signature for the export
  portal/PortalModels.java     what the portal sends back
  store/MeterStore.java        the in-memory cache and refresh
  api/MeterController.java     /api/meters endpoints
  api/TransformerController.java
  api/StatusController.java
  api/ApiModels.java           what this API sends back
  api/ApiMapper.java           the data cleaning
  api/ApiExceptionHandler.java error responses (404, 503, 502)
```

## Running it

You need Java 21 or newer. Maven comes with the repo (`./mvnw`), and there's no database or paid service.

```bash
git clone https://github.com/6squash9/urja-api.git
cd urja-api
PORTAL_EMAIL="<portal email>" PORTAL_PASSWORD="<portal password>" ./mvnw spring-boot:run
```

Use the portal login from the assignment. It's passed as environment variables so it isn't stored in the code or in this repo. When it starts you should see:

```
Logged in to the portal
Cache refreshed: 403 meters, 40 transformers
```

Interactive docs: http://localhost:8080/swagger-ui.html (raw spec at `/v3/api-docs`).

Other settings are in `src/main/resources/application.properties`: the portal URL, the refresh interval (`cache.refresh-interval-ms`, default 10 minutes) and the port (8080).

Tests:

```bash
./mvnw test
```

The two that matter: one checks that the signer produces exactly the signature I captured from the browser, the other checks the readings cleanup. The third is Spring's default "does the app start" test.

### Example requests

```bash
curl "http://localhost:8080/api/status"
curl "http://localhost:8080/api/meters?status=Faulty&size=3"
curl "http://localhost:8080/api/meters/J100002"
curl "http://localhost:8080/api/meters/J100002/readings"
curl "http://localhost:8080/api/transformers/DT-003/meters"
```

The portal sends a reading like this:

```json
{"timestamp":"24/06/2026 00:00","kwh":"6850.78","kvah":"7398.84","voltR":"236"}
```

and this API returns:

```json
{
  "timestamp": "2026-06-24T00:00+05:30",
  "kwh": 6850.78,
  "kvah": 7398.84,
  "voltage": { "R": 236.0 },
  "kwhSincePrevious": 0.46
}
```

## Decisions and trade-offs

**Using the portal's own JSON endpoints instead of scraping HTML.** The portal is a SvelteKit app, and every page loads its data from `/portal/...` endpoints that return JSON. Reading that is a lot simpler and less fragile than pulling values out of HTML.

**Caching meters and transformers, but not readings.** The portal has no filters, so filtering live would mean going through all 21 pages of meters on every request. The export gives all 403 meters in one call, so I load that into memory and filter with Java streams. Meter details (make, status, location) don't change often, so being up to 10 minutes old seemed fine, and `/api/status` shows when the data was loaded. Readings change and are only needed one meter at a time, so those are fetched live.

**Re-login on 401.** Sessions only last an hour (`Max-Age=3600` on the cookie). Instead of tracking a timer, the client just reacts to the 401, logs in again and retries the request once.

**Signing right before sending.** The export needs an HMAC signature that includes the current time. When I tested by hand, a signature sent a little late was rejected, so the client signs immediately before each request.

**Separate models for the portal and for this API.** If the portal changes its format, only `PortalModels` and `ApiMapper` need to change.

**Cleaning the data.** Numbers come back as real numbers, not strings. Dates are ISO-8601 with an offset instead of `DD/MM/YYYY`. `voltR` becomes `voltage.R`, so if a three-phase meter sends Y and B voltages they'd show up there too (I didn't check a three-phase meter). I added `kwhSincePrevious` because `kwh` is a running total, like an odometer, and the usage per half hour is what people actually want. I also renamed `installStatus` to `status` and `dtCode` to `transformerCode` so they make sense without knowing the portal.

**Not crashing on messy data.** Unknown fields are ignored, blank or bad numbers become `null`, rows with unreadable dates are skipped, and duplicate IDs keep the first one.

**Generating the OpenAPI spec instead of writing it.** springdoc builds `openapi.json` from the controllers and record types, so the spec matches the code, and it also serves a Swagger UI page where you can try every endpoint in the browser.

**Java and Spring Boot**, because it's what I know best, so I could spend my time understanding the portal instead of the language.

## Assumptions

- The portal's timestamps are Indian Standard Time (+05:30). It doesn't say, but the utility is in Jaipur.
- The Transformers list is the source of truth for transformer names. Some meters list a different name for the same DT code (details in PROTOCOL.md). I left the meter's hierarchy as the portal sends it instead of guessing which name is right.
- `kwh` and `kvah` only go up, so the difference between two readings is the usage in that interval.
- One portal account is shared by everyone using this API.

## What I left out

- Authentication on this API. It's meant to run locally, and in a real setup it would sit behind the company's own auth.
- A database. 403 meters fit in memory easily.
- Caching readings.
- The optional extras: a web client, "what's near this location" and a separate hierarchy endpoint. The data for most of these is already in the cache.
- Fixing the inconsistent hierarchy names. I documented them but didn't try to resolve them.

## With more time

- Tests against a fake portal (login, 401 then re-login, bad signature, broken data).
- A hierarchy endpoint (zone down to meters) and a small report of the data problems.
- Location queries like "meters within 2 km". A simple scan is fine for 403 meters. With a lot more, I'd use a spatial index or PostGIS.
- Find out the real limits: how old a signature timestamp can be, and whether sessions ever end before the hour. Add timeouts and retries with backoff in case the portal is slow or flaky.
- Add descriptions and examples to the generated OpenAPI spec (right now it's mostly what springdoc infers from the code).
- Cache readings for a short time per meter.

## Reflection

**What assumptions did you make?**
The main ones: timestamps are IST, the Transformers list has the right DT names, meter data that's up to 10 minutes old is fine, and `kwh` is a running total. I wrote them down because the portal doesn't tell you any of this.

**Which part was the most difficult, and how did you get unstuck?**
The export. Clicking "Export all meters" made a request to `/portal/keys` first, which I didn't understand, and the export request had `X-Signature` and `X-Timestamp` headers that nothing else had. I first guessed the data itself was encrypted, but the response was plain JSON, so that was wrong. What got me unstuck was searching all the JavaScript the page had loaded for `X-Signature`. I found the function that builds the signature and reproduced it with `openssl` in the terminal. My first signed request still failed. Before changing anything, I recomputed a signature I had captured from the browser using the same method, and it matched. So the method was right, and the real problem was that I had waited too long between making the signature and sending it.

**If you had another day, what would you improve?**
Tests against a fake portal, the hierarchy and location endpoints, and actually measuring the portal's limits instead of guessing them.

**What mistake did you make?**
A few. At the start I thought the main deliverable was the OpenAPI file, not a working service. My first curl login got a 403 because I didn't realise the browser was quietly sending an `Origin` header that the server needs. And my first signed export failed because the timestamp went stale. More generally, I spent most of my time on the investigation and didn't time-box it, so the coding part ended up rushed.

**If you were reviewing this, what would you criticise?**
There are no integration tests, so the portal-facing code is only tested by hand. The hierarchy problems are documented but not fixed. The 10-minute refresh is a guess, not based on how often the data actually changes. And the committed `openapi.json` is a snapshot of the generated spec, so it has to be regenerated when endpoints change.
