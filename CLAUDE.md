# Nomulus Claude Code Instructions

## Pull Requests

- **Always** open PRs against `unstoppabledomains/nomulus` (base: `master`)
- **Never** open PRs against the upstream `google/nomulus` — even if git remotes include it

## Upstream Compatibility

This repo is a fork of `google/nomulus`. The upstream is regularly pulled into `unstoppabledomains/nomulus`, so keeping merge conflicts minimal is a priority.

- Prefer making changes in UD-specific files (files prefixed with `ud-`, files in `ud-*` directories, or files that don't exist in upstream) whenever possible
- When a change must touch a file that also exists in upstream, keep the modification as localized and non-structural as possible
- **Before proposing any change that would cause more than minor merge conflicts with upstream** (e.g., restructuring shared files, renaming methods, reformatting large blocks), pause and explain the conflict risk to the user, and ask for their permission before proceeding
