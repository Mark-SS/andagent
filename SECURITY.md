# Security Policy

## Reporting a vulnerability

Please do **not** open a public issue for security vulnerabilities.
Open a private security advisory via GitHub (Security → Advisories → New draft security advisory), or contact the maintainer directly.

We aim to acknowledge reports within 72 hours.

## Scope

- Tool execution safety: destructive tool gating, path traversal protections (`LocalMediaHandler`, QuickJS sandbox, SAF helpers), shell command injection surface.
- The WebView bridge (`AndroidBridge`): any way for web content other than the bundled app assets to reach native methods.
- Credential handling: the app intentionally contains no compiled-in credentials; config arrives via `Download/config.json` or the settings UI.

## Known design boundaries (not vulnerabilities)

- The agent can execute shell commands and edit files on the device — that is the product. Destructive tools are gated by explicit user confirmation, but a user approving a harmful action is within design.
- The embedded Termux bootstrap exposes a real Linux userland inside the app sandbox.
