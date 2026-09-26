---
name: legado-source-compat
description: Diagnose or implement Legado book-source JSON rules in new-book-getter, including request planning, CSS/JSONPath/XPath/JS parsing, and staged real-source verification. Use for rule-engine changes or compatibility tests, not unrelated Android UI work.
---

# Legado source compatibility

Read [the project rule contract](../../docs/legado-rule-contract.md) before changing rule semantics. Check the precise behavior in the parent repository's `app/src/main/java/io/legado/app/model/analyzeRule/` and `BookSource`/rule classes; the vendored [legadoSkill](../../legadoSkill/.trae/skills/legado-book-source-tamer/SKILL.md) is a secondary knowledge base, not the implementation authority. Its MIT license does not grant rights over third-party book sources or text.

For each compatibility issue, capture the source snapshot ID and the failing stage/field, build a synthetic regression fixture, then verify the affected real source with `liveSourceProbe` or the Android runtime as appropriate. Distinguish empty results from HTTP/login/WebView/rule failures. For download acceptance, use the rate-limited, resumable `batchDownloadProbe` and independently verify per-book chapter caches, distinct URLs, file size and SHA-256; partial books do not count.

Keep app and core implementation in Java. Do not commit `source-catalog/snapshots/`, `downloads/`, credentials, cookies, full third-party source JSON, or copyrighted book text. Update [the compatibility matrix](../../docs/compatibility.md) and [live test log](../../docs/live-source-tests.md) when verified behavior changes.
