# Contributing

Thanks for taking the time. Please read this short doc before opening an issue or PR.

## License of contributions

By opening a PR you agree that your contribution is licensed under the **GNU AGPL v3**, the same license as the rest of the repository (see [`LICENSE`](LICENSE)). The maintainer reserves the right to **dual-license the codebase**: AGPL v3 for the public, and separate commercial licenses negotiated case-by-case. By contributing, you grant the maintainer the right to relicense your contribution under those commercial terms.

If that is not acceptable to you, please open an issue to discuss before sending code.

## Before opening an issue

1. Search [open and closed issues](https://github.com/danielalejandroamaro/gitlab-pipeline/issues?q=is%3Aissue) first.
2. Use the **Bug report** template. Vague reports ("doesn't work") will be closed without comment.
3. For security issues, **don't open a public issue** — see [`SECURITY.md`](SECURITY.md).
4. For commercial use / closed-source integration, email directly; don't open an issue (see [`README.md`](README.md#commercial-license-dual-licensing)).

## Before opening a PR

1. **Open an issue first** for anything bigger than a one-line typo fix. Avoid sinking time into a PR that won't be merged because the approach doesn't fit.
2. Run `./gradlew check` locally; it must pass.
3. Run `./gradlew verifyPlugin` locally if you're touching anything that could affect plugin loading or API surface. The CI runs it too.
4. Update [`CHANGELOG.md`](CHANGELOG.md) with a bullet under a new version section. The maintainer will decide the actual version bump.
5. Don't bump `gradle.properties` `version` — the maintainer does this as part of the release.
6. Keep PRs focused: one feature or one fix per PR. Mixed PRs get split or rejected.

## Style

- Kotlin code follows the JetBrains Kotlin code style. IntelliJ's default formatter is fine.
- Commit messages: Conventional Commits (`feat(scope): …`, `fix(scope): …`, `chore(scope): …`, `docs(scope): …`, `refactor(scope): …`).
- One commit per logical change inside a PR; keep history clean. The maintainer squashes only when a PR has noisy review fixups.

## What's likely to be merged

- Bug fixes with a clear reproducer.
- Compatibility fixes for new IntelliJ Platform versions.
- Performance improvements with measurements.
- Small UX improvements with screenshots.

## What's unlikely to be merged

- Adding configuration UI / settings that this plugin intentionally avoids (zero-config is a design goal).
- Building a competing auth flow instead of reusing the official GitLab plugin's account manager.
- Re-architecting around frameworks that aren't already in the dependency set.
- Translations of the README — the canonical README is English; if you want a translation, link to it from a fork.
