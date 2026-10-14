# ADR 0002 — One Network Consent Gateway, deny by default

**Status:** accepted

* Android grants `INTERNET` at install with no runtime prompt, so consent is enforced in code.
* Exactly one class (`HttpsTransport`) may open sockets; `NetworkBypassScanTest` fails the build if any
  other production file references `java.net.URL`, `URLConnection`, sockets, `InetAddress`, OkHttp,
  WebView, DownloadManager, WorkManager or telemetry SDKs, and checks the manifest/backup/NSC config.
* Every request carries a `ConsentRequest`; the gateway refuses non-HTTPS, IP literals, non-443 ports,
  user-info, unlisted hosts, device identifiers, and bodies whose SHA-256 differs from the approved payload.
* A token is issued only after "Allow this request once", is bound to a fingerprint of
  (operation, method, URL, host list, payload class, payload hash), is consumed immediately, expires in
  2 minutes, and is retired when the operation ends. No "always allow". No automatic retries.
* Redirects are returned to the gateway and re-validated before connecting; an unapproved host is never
  contacted — the user can explicitly approve a new request that includes it.
* Strict Offline Mode (kill switch) is ON for fresh installs and revokes live tokens instantly.
* The only feature that needs the network is downloading models; it always offers the offline
  alternative (import a model file).
