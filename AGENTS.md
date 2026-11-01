# Android Project Guidelines

## Context
- This file is auto-loaded; `IDEA.md` and `PLAN.md` are not. Read both before planning or implementing anything here.
- `IDEA.md` is the product case and rationale. Stable - do not rewrite it.
- `PLAN.md` is the engineering side: phase status, open decisions, and append-only session notes with measured facts. Living - re-derive before trusting it.
- Do not restate their content in this file; keep this file to working rules.

## Scope
- Work only on the requested task and the files necessary to complete it.
- Do not modify unrelated modules or files.
- Do not install dependencies or change Gradle, SDK, plugin, or build configuration without explicit approval.

## Investigation
- Start with the relevant source file, its direct callers, and applicable tests.
- Follow existing architecture, naming, state-management, and error-handling conventions.
- Consider Android lifecycle behaviour, configuration changes, asynchronous operations, nullability, and state restoration when relevant.
- Distinguish confirmed defects from plausible risks.

## Changes
- Investigation and proposed fixes do not authorise implementation.
- Obtain explicit approval before every file-edit operation.
- Keep each change limited to the approved file and purpose.
- Ask again before editing additional files or expanding the task.

## Verification
- Inspect the diff after changes.
- Use targeted tests or build tasks appropriate to the change.
- Obtain approval before running commands that modify files, generate artefacts, install packages, or change device/emulator state.
- Never claim a build or test passed unless its result was observed.