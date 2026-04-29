# Plan: Add Environment-Specific AI Configuration

## Context

PR #114 (AI-powered analysis with Claude) is merged. The feature works on alpha-gke, but the environment-specific YAML configs in `nomulus-secrets` don't have an `ai:` section — they rely on null-safe fallbacks in `RegistryConfig.java` and defaults from `default-config.yaml`. We want explicit per-environment configuration for operational clarity, and production needs a tighter rate limit (60/hr vs 120/hr).

## What Changes

### nomulus repo: NO code changes needed

The existing code is complete:
- `default-config.yaml` (lines 642-651) — has `ai:` section with all defaults
- `RegistryConfig.java` (lines 1452-1474) — null-safe fallbacks (`config.ai != null ? ...`)
- `RegistryConfigSettings.java` (lines 261-267) — `Ai` POJO
- `AnthropicModule.java` — Secret Manager lookup with env var fallback
- `AnthropicClient.java` — model mapping, streaming

Keep the null-safe fallbacks as defensive coding (protects unit tests, local dev, new envs).

### nomulus-secrets repo: Add `ai:` section to 3 files

Files at: `core/src/main/java/google/registry/config/files/`

**`nomulus-config-alpha.yaml`** — append:
```yaml
ai:
  apiBaseUrl: https://api.anthropic.com
  apiKeySecretName: ud_rsp_anthropic_api_key
  defaultModel: sonnet
  rateLimitPerHour: 120
```

**`nomulus-config-sandbox.yaml`** — append:
```yaml
ai:
  apiBaseUrl: https://api.anthropic.com
  apiKeySecretName: ud_rsp_anthropic_api_key
  defaultModel: sonnet
  rateLimitPerHour: 120
```

**`nomulus-config-production.yaml`** — append:
```yaml
ai:
  apiBaseUrl: https://api.anthropic.com
  apiKeySecretName: ud_rsp_anthropic_api_key
  defaultModel: sonnet
  rateLimitPerHour: 60
```

### Secret Manager: Create API key secret

- Secret name: `ud_rsp_anthropic_api_key`
- Create in each project: `ud-registry-alpha-gke`, `ud-registry-sandbox-gke`, `ud-registry-prod-gke`
- Value: Anthropic API key from the team's API account

## Status

- **Not yet implemented.** This is a post-Tier 1 operational task.
- The feature works on alpha using `default-config.yaml` defaults and the secret already exists in alpha.
