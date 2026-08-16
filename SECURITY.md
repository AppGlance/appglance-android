# Security

**Reporting a vulnerability.** Please do not open a public issue. Use GitHub's private
vulnerability reporting on this repository (Security → Report a vulnerability) or email
**support@appglance.app**; both reach us privately. We aim to acknowledge within 72 hours.

**Scope.** This repository is the Android SDK. The hosted service at appglance.app - the ingest
API and the dashboard - is also in scope for responsible disclosure; report it to the same
address. Please do not run destructive tests against the hosted service. The Swift SDK is at
[AppGlance/appglance-apple](https://github.com/AppGlance/appglance-apple).

**What is and is not a secret.** App write keys (`glance_live_…`) ship inside app binaries by
design and only grant *write* access to one app's stream - the ingest resolves the app from the
key and ignores the client's app id. Service keys, push keys and database passwords are
server-side only and have never been in this repository.
