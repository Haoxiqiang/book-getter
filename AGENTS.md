# Book Getter agent guidance

For book-source or RSS rule parsing, request planning, Java compatibility work, and differential tests, read [the compatibility skill](skills/legado-source-compat/SKILL.md) and its relevant references. For creating, repairing, or explaining an importable Legado book source or RSS subscription source, read [the authoring skill](skills/legado-source-authoring/SKILL.md) instead; use both when a task spans authoring and engine behavior.

The official Legado source checkout is located at `../legado` (relative to this project root). Treat it as an important reference for implementation and compatibility work, and prefer its source code for behavior disputes.

The upstream `legadoSkill/` directory is an independent Git clone for maintenance; keep its history and license. All new application code should be Java, not Kotlin.
