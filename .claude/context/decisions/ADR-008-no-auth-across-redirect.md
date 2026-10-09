# ADR-008: `Authorization` not forwarded across the redirect host

## Status
Accepted (recorded retroactively)

## Date
On or before 2026-10-07 (`HttpTransport`; rewritten onto `HttpURLConnection` in `ec71737`)

## Context
An Apps Script `/exec` POST answers with a 302 to `script.googleusercontent.com`, where the result is
fetched with a GET. Restricted deployments need an OAuth bearer token on the first request.

## Decision
`HttpTransport` follows the redirect manually with a GET and **never sends `Authorization` to a
different host** than the one it was configured for.

## Alternatives considered
- Automatic redirect following: the behaviour around headers varies by platform and is not
  controllable.

## Rationale
It avoids leaking the user's Google token to another host.

## Consequences
- The redirect is handled in code. `HttpTransportTest` covers it against an in-process server.

## Constraints
- Custom headers added in the future must follow the same rule.

## Related components
`transport/HttpTransport.java`, `HttpTransportTest`
