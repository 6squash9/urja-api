# PROTOCOL.md: how the Urja portal works, and how I figured it out

I was given a login and a URL and not much else. This is how I worked out what the portal does under the hood, in the order I found things. The short reference version (endpoints, auth, quirks) is at the bottom.

I treated the portal as read-only the whole time and only did what a normal logged-in user can do: click around, look at the browser's DevTools, and read the JavaScript the site sends to the browser.

---

## 1. First, just using it like a person

Before opening any developer tools I logged in and clicked everything.

- Two sections: **Meters** and **Transformers**.
- Meters: "403 total", 20 per page (21 pages), a search box that works with a meter number or a serial number. Columns: Meter, Serial, Make, Phase, Status, DT. No sorting, no filters, no download button.
- Transformers: 40 distribution transformers ("DT-001", "Malviya Nagar DT 1", feeder "F-001", capacity in kVA), 2 pages. No search, no sorting. But there's an **"Export all meters"** button, which felt like it was on the wrong page.
- Each meter number is a link to a detail page.
- An **Ops Desk** button that can't be clicked, and a Sign out button.

What stood out: even with a website, getting anything useful out of it is manual. You can't ask "show me the faulty meters" or "which meters are on this transformer". That ended up shaping what my API offers.

## 2. The export button

I clicked "Export all meters" and got a `meter-export.json` file: a list of **403 meters**, the same as the "403 total" on the Meters page. That looked like the bulk path the assignment hinted at.

It also had more than the screens show: `installType`, `build`, a `geo` object with lat/lng, and a full `hierarchy` for each meter: zone → circle → division → subdivision → substation → feeder → DT. So meters belong to transformers, which belong to feeders, and so on. That's probably why the button is on the Transformers page.

What it didn't have: any consumption data.

## 3. Opening the Network tab

I had never really used the Network tab before, so this was the slowest part.

I opened DevTools → Network, refreshed the Meters page, and got a list of requests. Most were the page's HTML, CSS and a pile of JavaScript files with random-looking names. One row was different: type **fetch**, called `search?q=&page=1`. Its response was JSON containing exactly the 20 meters on the screen.

So the page loads as an empty shell, and then the JavaScript asks the server for the data. On the Transformers page the same thing happened with `dts?page=1`, and clicking Next fired `dts?page=2`. The Headers tab showed the real path is `/portal/dts?page=2`, and the end of the response had `"total":40,"page":2,"pageSize":20`.

This changed the plan. The portal already has a JSON API behind it, just not a documented one. I didn't need to scrape HTML.

## 4. How it knows I'm logged in

Getting logged out twice while exploring made me look at this properly. In the request headers of those data calls there was one interesting line:

```
Cookie: __Secure-better-auth.session_token=<long token>
```

No API key or token header, just a session cookie (from the better-auth library). To see where it comes from, I signed out, cleared the Network log, kept "Preserve log" on and logged in again. The login button sends:

```
POST /login
Content-Type: application/x-www-form-urlencoded
email=...&password=...
```

and the response headers hand over the cookie:

```
Set-Cookie: __Secure-better-auth.session_token=...; Max-Age=3600; Path=/; HttpOnly; Secure; SameSite=Lax
```

`Max-Age=3600` means the session lasts one hour, which explained the logouts. The response body was `{"type":"redirect","status":303,"location":"/meters"}` (SvelteKit's way of saying "go to /meters"), even though the real HTTP status was 200.

## 5. Proving it works without a browser

My service won't be a browser, so before writing any code I tried the same requests with curl, to see what's really required.

Login with just the email and password:

```
HTTP/2 403
Cross-site POST form submissions are forbidden
```

The browser had been sending an `Origin` header without me noticing, and SvelteKit uses it to block form posts coming from other sites (CSRF protection). Adding `-H "Origin: https://urja-ops.flockenergy.tech"` fixed it: 200 plus the session cookie. None of the other ~17 headers the browser sends were needed. I had guessed the login would return a real redirect without the browser's `x-sveltekit-action` header; it didn't, it gave the same JSON.

With the cookie, `GET /portal/dts?page=1` returned the transformer JSON. Without it:

```
HTTP/2 401
{"error":"unauthorized","message":"A valid session is required."}
```

That's a clean signal, and it's how my client knows the session has expired and it needs to log in again.

## 6. Where the consumption data lives

Clicking a meter (J100002) opens a page with the network path as breadcrumbs, a nameplate, the location, and a **consumption table**: a reading every 30 minutes with kWh, kVAh and V(R). That's the data missing from the export. In the Network tab it comes from two requests:

```
GET /portal/meters/J100002/geo
GET /portal/meters/J100002/energy
```

The meter ID is part of the path. A few things I noticed:

- Every number comes back as text: `"kwh":"6850.32"`, `"latitude":"26.84..."`. In the export, lat/lng are real numbers. Same data, different types (and different names: `latitude` vs `lat`).
- Timestamps look like `23/06/2026 23:30`, with no time zone.
- kWh keeps going up (6850.32 → 6850.78 → 6851.23). It's a running total, like an odometer, not usage per half hour. Usage per interval is the difference between two readings, about 0.45 kWh here.
- kVAh is always a bit higher than kWh (kWh/kVAh ≈ 0.93, the power factor). V(R) is the voltage on the R phase, around 224–238 V.
- No pagination: the whole series (about 7 days, 337 readings) comes back at once.
- The latest reading is 30 June 2026, months before today, so the data isn't live.

## 7. The export, again: it's signed

To make my service use the bulk export, I needed to see how the button actually calls it. With the Network tab open, clicking Export fires two requests:

```
GET /portal/keys      → {"data":{"signingSecret":"I3dZPPf5..."}}
GET /portal/export?page=1
```

My first guess was that the data was encrypted and `keys` was for decrypting it. Wrong: the export response is plain JSON (`{"data":[...403 meters...],"total":403}`). Scrolling to the bottom of the export request's headers showed two headers no other request has:

```
X-Signature: 1b2e13a26974f29d5b7f8418cd971e35e2e4f69a0dfba2bd030db3bda1874bc4
X-Timestamp: 1792014827
```

64 hex characters looks like SHA-256, and with a "signing secret" around, probably HMAC-SHA256. But I didn't know what text was being signed. The browser computes it, so the recipe had to be in the JavaScript. I searched every file the page had loaded (Cmd+Option+F in DevTools) for `X-Signature` and found this, after pretty-printing and mentally renaming the minified variables:

```js
async function C(method, path, query, secret) {
  let ts = String(Math.floor(Date.now() / 1000));
  let message = [method, path, query, ts].join("\n");
  // HMAC-SHA256 of message with the secret, as lowercase hex
}
// the Export button calls C("GET", "/portal/export", "page=1", secret)
```

So:

```
message   = "GET\n/portal/export\npage=1\n<unix seconds>"
signature = hex(HMAC-SHA256(key = signingSecret, data = message))
```

The same file explained why my downloaded export was a bare list: the button keeps only `data` from the response and saves that.

## 8. Signing it myself

In the terminal:

```bash
TS=$(date +%s)
SIG=$(printf 'GET\n/portal/export\npage=1\n%s' "$TS" | openssl dgst -sha256 -hmac "$SECRET" | awk '{print $NF}')
curl -b cookies.txt -H "x-timestamp: $TS" -H "x-signature: $SIG" "https://urja-ops.flockenergy.tech/portal/export?page=1"
```

The first try failed:

```
{"error":"signature_invalid","message":"Missing or invalid request signature."}
```

Instead of changing the theory, I checked the recipe against something I knew was right: I recomputed the signature the browser had sent (timestamp 1792014827) with the same openssl command, and it matched exactly. So the formula was fine. The difference was timing: I had made the timestamp, then got distracted before sending. Running everything in one go worked and returned all 403 meters. So the server rejects stale timestamps (I didn't measure the exact window).

Other notes: the signing secret was the same across sessions and days. And since it's handed to every logged-in browser, this is more of a speed bump against simple scripts than real security.

## 9. Messy data I noticed

- Meter hierarchy vs transformer list: one meter's hierarchy says `{"code":"DT-007","name":"Old Malviya Nagar Xfmr"}`, but the Transformers page says DT-007 is "Sanganer DT 7". Same code, different name.
- Feeder numbering: most DTs sit on the feeder with the same number (DT-021 → F-021), but DT-038/039/040 are on F-010/F-011/F-012. Could be legitimate, since a feeder can supply several transformers. I didn't confirm either way.
- `build` isn't always `legacy`. Some meters are `v2`.
- Field names differ between screens, endpoints and the export (`Status` / `installStatus`, `latitude` / `lat`).

---

## Reference

### Authentication
| Step | Request | Notes |
|---|---|---|
| Log in | `POST /login`, form fields `email`, `password`, header `Origin: https://urja-ops.flockenergy.tech` | Without `Origin` → `403 Cross-site POST form submissions are forbidden` |
| Session | `Set-Cookie: __Secure-better-auth.session_token=...` | `Max-Age=3600` (1 hour) |
| Use it | `Cookie: __Secure-better-auth.session_token=...` on every `/portal/...` call | No other headers needed |
| Expired / missing | `401 {"error":"unauthorized"}` | Log in again |

### Data endpoints (all GET, all need the cookie)
| What | Endpoint | Pagination |
|---|---|---|
| Meter list + search | `/portal/search?q=<text>&page=<n>` | 20 per page, `total`/`page`/`pageSize` |
| Transformers | `/portal/dts?page=<n>` | 20 per page, `total`/`page`/`pageSize` |
| Meter location | `/portal/meters/{id}/geo` | none |
| Meter consumption | `/portal/meters/{id}/energy` | none (whole series) |
| Signing secret | `/portal/keys` | none |
| Bulk export (signed) | `/portal/export?page=<n>` + `x-timestamp`, `x-signature` | all 403 on page 1, `total` only |

### Export signature
`hex(HMAC-SHA256(signingSecret, "GET\n/portal/export\npage=1\n<unix seconds>"))`, sent immediately.

### Quirks
1. Login needs an `Origin` header (CSRF check).
2. Sessions last 1 hour, then 401.
3. The export is HMAC-signed and stale timestamps are rejected.
4. Numbers come as strings from `/energy` and `/geo`, but as numbers in the export.
5. The same field has different names in different places.
6. Timestamps are `DD/MM/YYYY HH:mm` with no time zone.
7. kWh and kVAh are cumulative.
8. Transformer names in meter hierarchies don't always match the Transformers list.
9. The export button is on the Transformers page, and the export ignores pagination in practice.
10. The consumption data stops at 30 June 2026.

### Still open
- How old a signature timestamp can be.
- Whether sessions ever end before the hour.
- What `/portal/search` matches on exactly (I didn't need it, since search runs on the cached export).
- Whether three-phase meters return `voltY` / `voltB`.
- Whether faulty or decommissioned meters have readings.
- Whether the portal rate-limits.
