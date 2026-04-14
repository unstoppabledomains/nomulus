# Nomulus Claude Code Instructions

## Pull Requests

- **Always** open PRs against `unstoppabledomains/nomulus` (base: `master`)
- **Never** open PRs against the upstream `google/nomulus` — even if git remotes include it

## Upstream Compatibility

This repo is a fork of `google/nomulus`. The upstream is regularly pulled into `unstoppabledomains/nomulus`, so keeping merge conflicts minimal is a priority.

- Prefer making changes in UD-specific files (files prefixed with `ud-`, files in `ud-*` directories, or files that don't exist in upstream) whenever possible
- When a change must touch a file that also exists in upstream, keep the modification as localized and non-structural as possible
- **Before proposing any change that would cause more than minor merge conflicts with upstream** (e.g., restructuring shared files, renaming methods, reformatting large blocks), pause and explain the conflict risk to the user, and ask for their permission before proceeding

## Auto-Generated Golden Files

Several files are auto-generated and checked in. They will conflict on upstream syncs but are trivially resolved by regenerating after merge:

| File | Regenerate Command | Triggered By |
|------|-------------------|-------------|
| `db/src/main/resources/sql/er_diagram/*.html` | `./gradlew devTool --args="-e localhost generate_sql_er_diagram -o ../db/src/main/resources/sql/er_diagram"` | Any schema/entity change |
| `db/src/main/resources/sql/schema/nomulus.golden.sql` | Run `SchemaTest.deploySchema_emptyDb` and copy `db/build/resources/test/testcontainer/mount/dump.txt` over the golden file. **Strip `\restrict`/`\unrestrict` lines** — they're psql meta-commands that break JDBC execution in other tests. | Any Flyway migration or entity change |
| `core/src/test/resources/google/registry/module/routing.txt` | `cd core && java -jar build/libs/nomulus.jar -e localhost get_routing_map -c google.registry.module.RequestComponent > src/test/resources/google/registry/module/routing.txt` (build nomulus jar first: `./gradlew nomulus`). **Strip any jline warning lines** from the top of the output. | Any new or modified API action |
| `db/src/main/resources/sql/schema/db-schema.sql.generated` | `./gradlew :db:generateSqlSchema` (or let Hibernate regenerate) | Any JPA entity change |
