# Book Getter agent guidance

For creating, repairing, or explaining Legado book/RSS sources, or for rule parsing, request planning, JS hosts and compatibility tests, read [the unified Legado skill](skills/legado-source/SKILL.md). Select its authoring or engine-development references for the actual task; use both only when the task spans both workflows.

Start project documentation at [docs/README.md](docs/README.md). Use [architecture](docs/architecture.md) and [embedding](docs/embedding.md) for module and host boundaries, [rule contract](docs/legado-rule-contract.md) for semantics, [capability matrix](docs/compatibility.md) for current support, and [verification](docs/verification.md) for checks. Dated probes in `docs/history/` are historical evidence, not current site availability.

The official Legado checkout is `../legado` relative to this project root. Prefer its source code for behavior disputes. The upstream `legadoSkill/` directory is an independent Git clone; preserve its history and license and do not fold local guidance into it.

All new application code must be Java, not Kotlin. Runtime dependencies flow `app → source-runtime-android → source-core`; `source-catalog` is a separate development tool. Keep Android and App types out of the Java core. Build with JDK 21; third-party versions live in `gradle/libs.versions.toml`.
