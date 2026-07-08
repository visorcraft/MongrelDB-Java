# Authentication & Authorization

A `mongreldb-server` daemon runs in one of three modes:

1. **Open** (default) - no auth required.
2. **Bearer token** (`--auth-token <TOKEN>`) - every request must carry an
   `Authorization: Bearer <TOKEN>` header.
3. **HTTP Basic** (`--auth-users`) - every request must carry an
   `Authorization: Basic <base64(user:pass)>` header.

The Java client supports all three through its constructors. This guide shows
each mode, how to inspect what was sent, and how to manage users and roles via
SQL when the server is in Basic mode.

---

## Bearer token mode

Start the daemon with a token:

```sh
mongreldb-server --auth-token s3cret-token
```

Connect with the four-argument (or five-argument) constructor, passing the
token as the second argument. The token is sent as `Authorization: Bearer ...`
on every request.

```java
MongrelDB db = new MongrelDB(
        "http://127.0.0.1:8453",
        "s3cret-token",  // token
        null,            // username (unused when token is set)
        null);           // password

if (!db.health()) {
    // A bad/missing token surfaces as AuthException on the first call;
    // health() swallows it and returns false.
    throw new RuntimeException("daemon not reachable (bad token?)");
}
```

A missing or wrong token surfaces as `AuthException` (HTTP 401/403) on any
call. `health()` catches exceptions and returns `false`, so it is a safe
probe; other methods throw.

### Where the token comes from

Hard-coding secrets in source is bad practice. Read it from the environment:

```java
String token = System.getenv("MONGRELDB_TOKEN");
if (token == null || token.isEmpty()) {
    throw new IllegalStateException("MONGRELDB_TOKEN not set");
}
MongrelDB db = new MongrelDB(MongrelDB.DEFAULT_BASE_URL, token, null, null);
```

## Basic auth mode

Start the daemon with a users file or inline users:

```sh
mongreldb-server --auth-users
```

Connect with username and password:

```java
MongrelDB db = new MongrelDB(
        "http://127.0.0.1:8453",
        null,            // token (null to use Basic)
        "admin",         // username
        "s3cret");       // password
```

The client base64-encodes `username:password` and sets `Authorization: Basic
...` on every request.

## Token takes precedence

If you supply both a token and Basic credentials, the token wins and Basic
credentials are ignored. This lets you layer an override without branching:

```java
MongrelDB db = new MongrelDB(
        url,
        "overrides-everything", // token wins
        "fallback",             // ignored
        "user");                // ignored
```

## Custom HttpClient and timeouts

The five-argument constructor accepts a custom `java.net.http.HttpClient` -
use it for a connect/read timeout, custom TLS, a proxy, or an executor:

```java
HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .proxy(ProxySelector.of(new InetSocketAddress("proxy.local", 8080)))
        .build();

MongrelDB db = new MongrelDB(url, token, null, null, http);
```

Without a custom client, the default is built with a 30-second connect
timeout; each request also carries a 30-second request timeout.

## Verifying what gets sent

The auth header is applied in `MongrelDB.applyAuth`, called from every
request. For debugging, point the client at a local echo server or watch the
daemon logs. A quick integration test pattern:

```java
// Use a test HTTP server that echoes the Authorization header, or simply
// log the header to confirm the client sends what you expect.
System.out.println("Connecting with token: " + mask(token));
boolean ok = db.health();
```

## User and role management via SQL

When the daemon is in Basic auth mode, users and roles live in the catalog
and are managed with SQL. Run these statements through `MongrelDB.sql`.

### Create a user

```java
db.sql("CREATE USER alice WITH PASSWORD 'hunter2'");
```

### Alter a user

Change a password:

```java
db.sql("ALTER USER alice WITH PASSWORD 'new-password'");
```

Grant the admin role:

```java
db.sql("ALTER USER alice ADMIN");
```

`ALTER USER ... ADMIN` is how you promote a user to full administrative
privileges (table creation/drop, compaction, user management). Use it
sparingly.

### Drop a user

```java
db.sql("DROP USER alice");
```

### Roles and grants

```java
db.sql("CREATE ROLE analyst");
db.sql("GRANT SELECT ON orders TO analyst");
db.sql("GRANT analyst TO alice");
db.sql("REVOKE SELECT ON orders FROM analyst");
db.sql("DROP ROLE analyst");
```

Exact grant syntax mirrors the server's SQL flavor; consult the server's SQL
reference for the full `GRANT`/`REVOKE` grammar available in your build.

## Common pitfalls

**Auth errors look like other errors without typed catches.** A 401/403
raises `AuthException`; a 404 raises `NotFoundException`. Always catch the
specific subclass rather than string-matching `getMessage()`.

**Forgetting to set auth in production.** A client built with
`new MongrelDB(url)` sends no credentials. Against an auth-enabled daemon,
every call throws `AuthException`. Centralize client construction so the auth
credentials are never accidentally dropped.

**Sharing one client across threads is fine; sharing credentials across users
is not.** A `MongrelDB` instance is thread-safe, but it carries one identity.
If you serve multiple authenticated users, build a client per user (or per
request) with that user's token.

**Token in version control.** Put secrets in the environment, a secret
manager, or a file outside the repo. Never commit a real token.

## Next steps

- [errors.md](errors.md) - `AuthException` and the rest of the exception hierarchy
- [quickstart.md](quickstart.md) - the full end-to-end walkthrough
