# Security Policy

## Supported versions

Only the latest published version on the JetBrains Marketplace receives security fixes. There is no LTS branch.

## Reporting a vulnerability

**Do not open a public GitHub issue for security problems.** Use one of:

1. **Preferred — GitHub private security advisory**:
   https://github.com/danielalejandroamaro/gitlab-pipeline/security/advisories/new
   This keeps the report private until a fix ships.
2. **Email**: danielalejandro.amaroramos@gmail.com

Include:

- The affected plugin version.
- The IDE + version where the issue reproduces.
- A minimal reproducer (private repo / synthetic CI config is fine).
- Impact assessment from your point of view: token exfiltration, RCE inside the IDE, leaking pipeline data to unauthorised parties, etc.

## Response timeline

Best effort, single maintainer. Typical:

- Acknowledgement: within 7 days.
- Triage (confirm/reject): within 14 days.
- Fix released or mitigation documented: within 30 days of triage for high-impact issues; longer for low-impact.

If you don't get an acknowledgement within 14 days, ping again via email.

## Scope

In scope:

- Token / credential exfiltration via this plugin.
- Remote code execution in the IDE process driven by malicious GitLab responses.
- Bypass of the auth bridge (e.g. reading a GitLab token without IDE consent).

Out of scope:

- Vulnerabilities in the IntelliJ Platform, the official GitLab plugin, or any other JetBrains-shipped component. Report those directly to JetBrains.
- Vulnerabilities in GitLab itself. Report those to GitLab.
- Issues that require an already-compromised local user (we trust the IDE process).
