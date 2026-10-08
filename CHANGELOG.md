# Changelog

## [Unreleased]

## [1.0.1] - 2026-10-08

### Changed

- Updated build tooling (Gradle 9.8.0); no functional changes.

## [1.0.0] - 2026-10-08

### Added

- Button in the commit message toolbar that generates a commit message for the included changes with the local Claude Code CLI, respecting unchecked files and lines.
- Clicking the button again while it runs cancels the generation.
- Settings for the prompt, model and effort, with the model list read from the CLI.
- Per-project override of prompt, model and effort.
- The CLI runs isolated from the repository by default; an opt-in global setting uses the project's Claude Code context instead.

[Unreleased]: https://github.com/koenhendriks/commit-message-for-claude-code/compare/v1.0.1...HEAD
[1.0.1]: https://github.com/koenhendriks/commit-message-for-claude-code/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/koenhendriks/commit-message-for-claude-code/commits/v1.0.0
