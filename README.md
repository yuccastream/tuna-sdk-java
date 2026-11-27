# tuna-sdk-java

Open [tuna.am](https://tuna.am) tunnels straight from Java or Kotlin code:
expose a local HTTP or TCP service at a public address without installing the
`tuna` CLI.

- Two ways in: `Tuna.listen` hands you the connections as streams,
  `Tuna.forward` proxies them to a local address.
- Survives network failures: reconnects with backoff and keeps the same
  public address.
- Built on [Apache MINA SSHD](https://mina.apache.org/sshd-project/) and the
  JDK HTTP client.

The API is not stable yet: it may change before v1.

## Install

Maven:

```xml
<dependency>
  <groupId>am.tuna</groupId>
  <artifactId>tuna-sdk</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle:

```kotlin
implementation("am.tuna:tuna-sdk:0.1.0")
```

Requires Java 17 or later. Besides MINA SSHD the SDK depends on Bouncy Castle
(`bcprov-jdk18on`) for Ed25519 host keys. MINA logs through SLF4J: add a
binding such as `slf4j-simple` to see its logs, or `slf4j-nop` to silence the
warning about a missing one.

## Authentication

Create an API key with the `tunnels:connect` scope at
[my.tuna.am](https://my.tuna.am) and pass it in `Options.apiKey` or the
`TUNA_API_KEY` environment variable.

## Forward to a local service

```java
Options options = new Options()
        .protocol("tcp")
        .port("my-db")
        .listener(e -> {
            if (e.type() == EventType.CONNECTED) {
                System.out.println("forwarding " + e.url() + " -> 127.0.0.1:5432");
            }
        });
try (Tunnel tunnel = Tuna.forward(options, "127.0.0.1:5432")) {
    tunnel.await();
}
```

The local address is `host:port` or `unix:///path/to.sock` (Unix sockets are
not available on Windows). `forward` returns once the tunnel is up; `await`
blocks until it is closed or ends for good.

## Handle connections yourself

```java
Tunnel tunnel = Tuna.listen(new Options().protocol("http"));
System.out.println("public URL: " + tunnel.url());
while (true) {
    Connection c = tunnel.accept();
    executor.submit(() -> handle(c.getInputStream(), c.getOutputStream()));
}
```

A `Connection` is a pair of blocking streams, like a `Socket`: writes are sent
at once, closing the output stream signals end of data, `close()` closes both
directions. `Tunnel.close()` stops accepting and reconnecting, but the tunnel
stays connected until the last accepted connection is closed, so in-flight
requests can finish.

The [examples](examples) directory holds a complete program: a command-line
tunnel client that uses both modes.

## Options

| Method | Meaning |
|---|---|
| `protocol` | `http` or `tcp`, required |
| `apiKey` | API key, defaults to `$TUNA_API_KEY` |
| `subdomain`, `domain` | reserved subdomain or custom domain for HTTP |
| `port` | reserved TCP port or its alias |
| `proxyProtocol` | PROXY protocol header version for TCP: `1` or `2` |
| `cidrAllow`, `cidrDeny` | client CIDRs filtered by the tuna.am node, TCP only |
| `location` | only for names and ports reserved in the legacy `ru` or `nl` zones; empty lets the server pick |
| `listener` | callback for tunnel events |
| `connectTimeout` | limit on the wait for the first connection; zero means no limit |
| `serverUrl`, `sslContext`, `clientName` | API address, TLS settings for it, client name reported to the server |

## Events and errors

The listener receives `CONNECTED`, `RECONNECTING`, `RECONNECTED`, `BANNER`,
`SESSION_EXPIRING`, `TERMINATED` and `LOCAL_ERROR`. Calls never overlap and run
on the tunnel's threads, so keep the callback short.

Network failures and temporary server errors are retried: the pause grows from
1 to 15 seconds with random jitter, and `Retry-After` is respected. A lost
connection to the node is detected within 15 seconds and reported as
`KeepAliveTimeoutException` in a `RECONNECTING` event. The tunnel ends with an
exception when retrying is pointless; check it with `Tuna.isPermanent`:

- `ServerException` — the API rejected the request: bad or revoked key,
  missing scope, unknown reserved name, unpaid plan;
- `TerminatedException` — the server closed the tunnel: it was deleted,
  suspended or hit the session time limit.

`listen` and `forward` throw it if the tunnel never came up; afterwards
`accept` and `await` do.

## Behaviour worth knowing

- `Tunnel.close()` of a `forward` tunnel cuts the forwarded connections; of a
  `listen` tunnel it waits for the accepted ones to be closed by your code.
- The `Host` header and the PROXY protocol header reach your service as sent.
- The API client is `java.net.http.HttpClient` and uses the JVM proxy
  settings (`https.proxyHost`, `ProxySelector`), not `HTTPS_PROXY`.
  Connections to tuna.am nodes are plain SSH over TCP and do not use the
  proxy.
- Use `sslContext` for a private CA.

## Contributing

This repository is a read-only mirror of the SDK directory in the tuna.am
monorepo. Issues and pull requests are welcome here: accepted changes are
applied upstream and come back with the next release.

## License

[MIT](LICENSE)
