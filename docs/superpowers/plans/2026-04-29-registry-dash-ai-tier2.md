# Registry Dashboard AI — Tier 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the AI sparkle button on Portfolio and Pricing pages, externalize prompt content from Java/TS into `default-config.yaml`, expose a new `GET /ai/prompts` endpoint backing the frontend menu, and log the active prompt version per request.

**Architecture:** Extend the existing `ai:` config block with a `prompts:` subsection (system-prompt fragments + per-page menus). Refactor `RegistryDashAiAction.getDefaultSystemPrompt` to consume injected config. Add a sibling `RegistryDashAiPromptsAction` for `GET /ai/prompts`. Frontend gains an `AiPromptsService` that fetches and caches the menu per page; `ai-prompts.ts` becomes a fallback-only export.

**Tech Stack:** Java 21, Dagger 2, Jakarta Servlets, Angular 17+ (signals, standalone components), Angular Material, JUnit 5 + Mockito + Truth.

**Spec:** `docs/superpowers/specs/2026-04-29-registry-dash-ai-tier2-design.md`

---

## File Map

### Backend — New Files
| File | Responsibility |
|------|---------------|
| `core/src/main/java/google/registry/ui/server/console/registrydash/RegistryDashAiPromptsAction.java` | GET action returning `{version, menu}` for a page |
| `core/src/test/java/google/registry/ai/AiAnalyzeRequestTest.java` | Unit tests for `VALID_PAGES` allowlist |
| `core/src/test/java/google/registry/ui/server/console/registrydash/RegistryDashAiPromptsActionTest.java` | Unit tests for the new action |

### Backend — Modified Files
| File | Change |
|------|--------|
| `core/src/main/java/google/registry/ai/AiAnalyzeRequest.java` | Extract `VALID_PAGES` constant; add `portfolio`, `pricing` |
| `core/src/main/java/google/registry/config/RegistryConfigSettings.java` | Add nested `Prompts`, `MenuItem` classes on `Ai` |
| `core/src/main/java/google/registry/config/files/default-config.yaml` | Extend `ai:` with `prompts:` block |
| `core/src/main/java/google/registry/config/RegistryConfig.java` | Add `@Config("anthropicPromptConfig")` provider |
| `core/src/main/java/google/registry/ui/server/console/registrydash/RegistryDashAiAction.java` | Refactor `getDefaultSystemPrompt`; inject `Prompts`; log `promptVersion` |
| `core/src/main/java/google/registry/module/RequestComponent.java` | Bind `RegistryDashAiPromptsAction` route |
| `core/src/test/java/google/registry/ui/server/console/registrydash/RegistryDashAiActionTest.java` | Cover config-driven prompts; cover `portfolio`, `pricing` |
| `core/src/test/resources/google/registry/module/routing.txt` | Regenerate (auto, after action added) |

### Frontend — New Files
| File | Responsibility |
|------|---------------|
| `console-webapp/src/app/registry-dash/ai/ai-prompts.service.ts` | Fetch + cache prompts menu per page |
| `console-webapp/src/app/registry-dash/ai/ai-prompts.service.spec.ts` | Service tests |

### Frontend — Modified Files
| File | Change |
|------|--------|
| `console-webapp/src/app/registry-dash/ai/ai-prompts.ts` | Convert exports into `FALLBACK_MENU` map only |
| `console-webapp/src/app/registry-dash/ai/ai-sparkle-button.component.ts` | Replace `PROMPTS_BY_PAGE[page]` with `aiPromptsService.getMenu(page)` |
| `console-webapp/src/app/registry-dash/portfolio/portfolio.component.html` | Add `<app-ai-sparkle-button page="portfolio">` |
| `console-webapp/src/app/registry-dash/pricing/pricing.component.html` | Add `<app-ai-sparkle-button page="pricing">` |

---

## Task 1: Extend the page allowlist

**Files:**
- Modify: `core/src/main/java/google/registry/ai/AiAnalyzeRequest.java:38-47`
- Test: `core/src/test/java/google/registry/ai/AiAnalyzeRequestTest.java` (new)

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/google/registry/ai/AiAnalyzeRequestTest.java`:

```java
// Copyright 2026 The Nomulus Authors. All Rights Reserved.
package google.registry.ai;

import static com.google.common.truth.Truth.assertThat;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class AiAnalyzeRequestTest {

  private static AiAnalyzeRequest validRequestForPage(String page) {
    AiAnalyzeRequest req = new AiAnalyzeRequest();
    req.page = page;
    req.promptType = "summarize_trends";
    req.chartData = new JsonObject();
    return req;
  }

  @Test
  void validPages_includesAllTier1AndTier2() {
    assertThat(AiAnalyzeRequest.VALID_PAGES)
        .containsExactly(
            "domain-activity", "revenue-billing", "forecasting",
            "explore", "overview", "portfolio", "pricing");
  }

  @Test
  void isValid_acceptsPortfolio() {
    assertThat(validRequestForPage("portfolio").isValid()).isTrue();
  }

  @Test
  void isValid_acceptsPricing() {
    assertThat(validRequestForPage("pricing").isValid()).isTrue();
  }

  @Test
  void isValid_rejectsUnknownPage() {
    assertThat(validRequestForPage("domains").isValid()).isFalse();
  }

  @Test
  void isValid_rejectsNullPage() {
    AiAnalyzeRequest req = validRequestForPage("portfolio");
    req.page = null;
    assertThat(req.isValid()).isFalse();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
./nom_build :core:test --tests google.registry.ai.AiAnalyzeRequestTest
```

Expected: FAIL — `AiAnalyzeRequest.VALID_PAGES` does not exist.

- [ ] **Step 3: Edit `AiAnalyzeRequest.java` to introduce `VALID_PAGES`**

Replace the body of `AiAnalyzeRequest.java` (lines 17-48) so the file reads:

```java
package google.registry.ai;

import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;

/** Request payload for the AI analysis endpoint. */
public class AiAnalyzeRequest {

  /** Pages allowed to invoke AI analysis. Add new pages here AND in default-config.yaml menus. */
  public static final ImmutableSet<String> VALID_PAGES =
      ImmutableSet.of(
          "domain-activity", "revenue-billing", "forecasting",
          "explore", "overview", "portfolio", "pricing");

  public String page;
  public String promptType;
  public JsonObject metadata;
  public JsonElement chartData;
  public String model;
  public String systemPrompt;
  public List<ConversationMessage> conversationHistory;

  /** A single message in the conversation history. */
  public static class ConversationMessage {
    public String role;
    public String content;
  }

  public boolean isValid() {
    return page != null && promptType != null && chartData != null && VALID_PAGES.contains(page);
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

```
./nom_build :core:test --tests google.registry.ai.AiAnalyzeRequestTest
```

Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/google/registry/ai/AiAnalyzeRequest.java \
        core/src/test/java/google/registry/ai/AiAnalyzeRequestTest.java
git commit -m "feat(registry-dash): allow portfolio and pricing pages for AI analyze"
```

---

## Task 2: Sparkle button on Portfolio

**Files:**
- Modify: `console-webapp/src/app/registry-dash/portfolio/portfolio.component.html`
- Modify: `console-webapp/src/app/registry-dash/portfolio/portfolio.component.ts` (imports if needed)

- [ ] **Step 1: Open the reference template**

Read `console-webapp/src/app/registry-dash/overview/overview.component.html`. Locate the `<app-ai-sparkle-button>` element and the surrounding header markup. Note its `page` attribute and any container/styling.

- [ ] **Step 2: Add sparkle to portfolio template**

Open `console-webapp/src/app/registry-dash/portfolio/portfolio.component.html`. Find the page header row (the heading element near the top). Add `<app-ai-sparkle-button page="portfolio"></app-ai-sparkle-button>` adjacent to the heading, mirroring the placement style used in `overview.component.html`.

- [ ] **Step 3: Wire imports if standalone**

Open `portfolio.component.ts`. If it is standalone, add `AiSparkleButtonComponent` to the `imports` array. Confirm the import statement: `import { AiSparkleButtonComponent } from '../ai/ai-sparkle-button.component';`.

If `portfolio.component.ts` is not standalone, locate the parent module and add the component to its `imports` array.

- [ ] **Step 4: Build the frontend**

```
cd console-webapp && npm run build
```

Expected: build succeeds, no Angular template errors.

- [ ] **Step 5: Commit**

```bash
git add console-webapp/src/app/registry-dash/portfolio/
git commit -m "feat(registry-dash): add AI sparkle button to Portfolio page"
```

---

## Task 3: Sparkle button on Pricing

**Files:**
- Modify: `console-webapp/src/app/registry-dash/pricing/pricing.component.html`
- Modify: `console-webapp/src/app/registry-dash/pricing/pricing.component.ts` (imports if needed)

- [ ] **Step 1: Add sparkle to pricing template**

Open `console-webapp/src/app/registry-dash/pricing/pricing.component.html`. Add `<app-ai-sparkle-button page="pricing"></app-ai-sparkle-button>` in the header row, mirroring the Portfolio placement from Task 2.

- [ ] **Step 2: Wire imports**

Open `pricing.component.ts`. Add `AiSparkleButtonComponent` to the `imports` array (or to the parent module's `imports`).

- [ ] **Step 3: Build the frontend**

```
cd console-webapp && npm run build
```

Expected: build succeeds.

- [ ] **Step 4: Commit**

```bash
git add console-webapp/src/app/registry-dash/pricing/
git commit -m "feat(registry-dash): add AI sparkle button to Pricing page"
```

---

## Task 4: Audit existing Overview + Explore wiring

**Files (read-only verification):**
- `console-webapp/src/app/registry-dash/overview/overview.component.html`
- `console-webapp/src/app/registry-dash/explore/explore.component.html`

- [ ] **Step 1: Confirm `[page]` values match `PROMPTS_BY_PAGE` keys**

Open each file. Note the value of the `page` attribute on `<app-ai-sparkle-button>`. Open `console-webapp/src/app/registry-dash/ai/ai-prompts.ts` and confirm each value is a key in `PROMPTS_BY_PAGE`.

Expected: Overview uses `page="overview"`, Explore uses `page="explore"`. Both keys exist in `PROMPTS_BY_PAGE`.

If any mismatch is found, file-level fix it inline (rename the page literal or add a missing menu) before continuing.

- [ ] **Step 2: Verify in local dev**

Start the local test server (see `MEMORY.md → reference_local_dev_setup.md`). Log in, navigate to Overview and Explore. Click each sparkle button and confirm a 3-item menu (`Summarize trends`, `Find anomalies`, `Suggest actions`) opens.

- [ ] **Step 3: Commit (if any fix was needed)**

```bash
git commit -m "fix(registry-dash): align Overview/Explore sparkle page identifiers"
```

If no fix was needed, no commit; just record the audit result.

---

## Task 5: `RegistryConfigSettings.Ai.Prompts` POJO

**Files:**
- Modify: `core/src/main/java/google/registry/config/RegistryConfigSettings.java:262-267`

- [ ] **Step 1: Add the nested `Prompts` and `MenuItem` classes**

In `RegistryConfigSettings.java`, replace the existing `Ai` class (line 262) with:

```java
  /** Configuration for AI (Anthropic) integration. */
  public static class Ai {
    public String apiBaseUrl;
    public String apiKeySecretName;
    public String defaultModel;
    public int rateLimitPerHour;
    public Prompts prompts;
  }

  /** AI prompt content, loaded from default-config.yaml. */
  public static class Prompts {
    public String version;
    public String basePreamble;
    public String responseGuidance;
    public Map<String, String> promptTypes;
    public Map<String, String> pageHints;
    public Map<String, List<MenuItem>> menus;
  }

  /** A single entry in a per-page sparkle menu. */
  public static class MenuItem {
    public String promptType;
    public String label;
    public String icon;
    public String userMessage;
  }
```

Add imports if missing: `import java.util.List;`, `import java.util.Map;`.

- [ ] **Step 2: Build the project**

```
./nom_build :core:compileJava
```

Expected: compiles cleanly. The new classes are unused at this point — that's fine.

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/google/registry/config/RegistryConfigSettings.java
git commit -m "feat(registry-dash): add Prompts/MenuItem config POJO for AI"
```

---

## Task 6: `prompts:` block in `default-config.yaml`

**Files:**
- Modify: `core/src/main/java/google/registry/config/files/default-config.yaml:642-651`

- [ ] **Step 1: Append `prompts:` after the existing `ai:` fields**

Below the existing `rateLimitPerHour: 120` line, add:

```yaml
  prompts:
    version: "v1"
    basePreamble: "You are an expert domain registry analyst. You are analyzing data from a domain registry dashboard."
    responseGuidance: "Provide your analysis in clear markdown. Use specific numbers from the data. Keep your response concise and actionable."
    promptTypes:
      summarize_trends: "Summarize the key trends in this data. Identify growth or decline patterns, compare across TLDs, and highlight the most significant changes."
      find_anomalies: "Identify anomalies, outliers, and unusual patterns in this data. Look for unexpected spikes, drops, or ratios that warrant investigation."
      suggest_actions: "Based on this data, suggest specific actionable recommendations. Focus on opportunities for growth, risk mitigation, and operational improvements."
      identify_risks: "Identify risks in this data. Look for expiration cliffs, declining registrars, and patterns that could lead to revenue loss."
    pageHints:
      domain-activity: "You are looking at domain lifecycle activity (creates, transfers, renewals, deletes) across TLDs."
      revenue-billing: "You are looking at billing revenue across TLDs and operations."
      forecasting: "You are looking at renewal-rate forecasts and expiration curves."
      overview: "You are looking at top-level registry health metrics."
      explore: "You are looking at an ad-hoc data exploration result."
      portfolio: "You are looking at registrar portfolio composition and concentration."
      pricing: "You are looking at TLD pricing configuration and effective fees."
    menus:
      domain-activity:
        - { promptType: summarize_trends, label: "Summarize trends", icon: "bar_chart",
            userMessage: "Summarize the key trends in domain activity — lifecycle patterns, growth or decline across TLDs." }
        - { promptType: find_anomalies, label: "Find anomalies", icon: "search",
            userMessage: "Identify anomalies in domain activity — unexpected spikes, unusual create/delete ratios, outlier TLDs." }
        - { promptType: suggest_actions, label: "Suggest actions", icon: "lightbulb",
            userMessage: "Based on this domain activity data, suggest specific actions for retention and growth." }
      revenue-billing:
        - { promptType: summarize_trends, label: "Summarize trends", icon: "bar_chart",
            userMessage: "Summarize revenue trends — key drivers, growth percentages, TLD performance comparison." }
        - { promptType: find_anomalies, label: "Find anomalies", icon: "search",
            userMessage: "Identify revenue anomalies — unexpected spikes or drops, declining segments, unusual patterns." }
        - { promptType: suggest_actions, label: "Suggest actions", icon: "lightbulb",
            userMessage: "Based on this revenue data, suggest pricing adjustments, registrar outreach, or growth opportunities." }
      forecasting:
        - { promptType: summarize_trends, label: "Summarize trends", icon: "bar_chart",
            userMessage: "Summarize renewal health — overall rates, TLD comparison, trajectory." }
        - { promptType: identify_risks, label: "Identify risks", icon: "warning",
            userMessage: "Identify risks — expiration cliffs, declining registrars, TLDs with dropping renewal rates." }
        - { promptType: suggest_actions, label: "Suggest actions", icon: "lightbulb",
            userMessage: "Suggest retention strategies, pricing recommendations, and proactive outreach based on this forecast data." }
      overview:
        - { promptType: summarize_trends, label: "Summarize trends", icon: "bar_chart",
            userMessage: "Summarize the key trends across the registry — activity patterns, renewal health, and overall performance." }
        - { promptType: find_anomalies, label: "Find anomalies", icon: "search",
            userMessage: "Identify any anomalies or concerns in the overview metrics." }
        - { promptType: suggest_actions, label: "Suggest actions", icon: "lightbulb",
            userMessage: "Based on these overview metrics, what should the registry team focus on?" }
      explore:
        - { promptType: summarize_trends, label: "Summarize trends", icon: "bar_chart",
            userMessage: "Summarize the key trends visible in this data." }
        - { promptType: find_anomalies, label: "Find anomalies", icon: "search",
            userMessage: "Identify any anomalies or unusual patterns in this data." }
        - { promptType: suggest_actions, label: "Suggest actions", icon: "lightbulb",
            userMessage: "Based on this data, what actions would you recommend?" }
      portfolio:
        - { promptType: summarize_trends, label: "Summarize trends", icon: "bar_chart",
            userMessage: "Summarize the registrar portfolio — concentration, growth among top registrars, TLD spread." }
        - { promptType: find_anomalies, label: "Find anomalies", icon: "search",
            userMessage: "Identify portfolio anomalies — sudden concentration shifts, registrars with unusual TLD mix." }
        - { promptType: suggest_actions, label: "Suggest actions", icon: "lightbulb",
            userMessage: "Based on this portfolio data, suggest registrar outreach or partnership opportunities." }
      pricing:
        - { promptType: summarize_trends, label: "Summarize trends", icon: "bar_chart",
            userMessage: "Summarize the pricing landscape — premium spread, registrar discount distribution, TLD comparisons." }
        - { promptType: find_anomalies, label: "Find anomalies", icon: "search",
            userMessage: "Identify pricing anomalies — outlier registrar fees, unusual premium gaps, mispriced TLDs." }
        - { promptType: suggest_actions, label: "Suggest actions", icon: "lightbulb",
            userMessage: "Based on this pricing data, suggest pricing adjustments or registrar negotiations." }
```

- [ ] **Step 2: Validate the YAML parses**

```
./nom_build :core:compileJava :core:processResources
```

Expected: succeeds.

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/google/registry/config/files/default-config.yaml
git commit -m "feat(registry-dash): externalize AI prompt content to default-config.yaml"
```

---

## Task 7: `@Config("anthropicPromptConfig")` provider

**Files:**
- Modify: `core/src/main/java/google/registry/config/RegistryConfig.java`

- [ ] **Step 1: Locate the existing AI providers**

Open `RegistryConfig.java`. Find the existing `@Config("anthropicApiBaseUrl")` provider (around line 1452 per the env-config plan). Note the pattern — `@Provides @Config(...) static <Type> name(RegistryConfigSettings cfg)`.

- [ ] **Step 2: Add the prompts provider**

Add immediately after the last AI-related provider:

```java
    @Provides
    @Config("anthropicPromptConfig")
    static RegistryConfigSettings.Prompts provideAnthropicPromptConfig(
        RegistryConfigSettings config) {
      // Defensive: tests and minimal configs may omit the prompts block.
      if (config.ai != null && config.ai.prompts != null) {
        return config.ai.prompts;
      }
      RegistryConfigSettings.Prompts empty = new RegistryConfigSettings.Prompts();
      empty.version = "unset";
      empty.basePreamble = "";
      empty.responseGuidance = "";
      empty.promptTypes = ImmutableMap.of();
      empty.pageHints = ImmutableMap.of();
      empty.menus = ImmutableMap.of();
      return empty;
    }
```

Add `import com.google.common.collect.ImmutableMap;` if not present.

- [ ] **Step 3: Build**

```
./nom_build :core:compileJava
```

Expected: succeeds.

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/google/registry/config/RegistryConfig.java
git commit -m "feat(registry-dash): add @Config provider for AI prompt config"
```

---

## Task 8: Refactor `getDefaultSystemPrompt` to use config (TDD)

**Files:**
- Modify: `core/src/main/java/google/registry/ui/server/console/registrydash/RegistryDashAiAction.java`
- Modify: `core/src/test/java/google/registry/ui/server/console/registrydash/RegistryDashAiActionTest.java`

- [ ] **Step 1: Write the failing test**

In `RegistryDashAiActionTest.java`, add (within the existing test class):

```java
  @Test
  void testSystemPrompt_drawnFromConfig() throws Exception {
    RegistryConfigSettings.Prompts promptConfig = new RegistryConfigSettings.Prompts();
    promptConfig.version = "test-v1";
    promptConfig.basePreamble = "PREAMBLE_FROM_TEST";
    promptConfig.responseGuidance = "GUIDANCE_FROM_TEST";
    promptConfig.promptTypes = ImmutableMap.of("summarize_trends", "BODY_FROM_TEST");
    promptConfig.pageHints = ImmutableMap.of("portfolio", "HINT_FROM_TEST");
    promptConfig.menus = ImmutableMap.of();

    String captured = capturedSystemPrompt(promptConfig, "portfolio", "summarize_trends");

    assertThat(captured).contains("PREAMBLE_FROM_TEST");
    assertThat(captured).contains("BODY_FROM_TEST");
    assertThat(captured).contains("HINT_FROM_TEST");
    assertThat(captured).contains("GUIDANCE_FROM_TEST");
  }
```

Add a helper at the bottom of the test class:

```java
  private String capturedSystemPrompt(
      RegistryConfigSettings.Prompts promptConfig, String page, String promptType)
      throws Exception {
    String payload = String.format(
        "{\"page\":\"%s\",\"promptType\":\"%s\",\"chartData\":{},\"conversationHistory\":[]}",
        page, promptType);
    JsonElement json = JsonParser.parseString(payload);

    String[] capturedPrompt = new String[1];
    doAnswer(invocation -> {
      capturedPrompt[0] = invocation.getArgument(0);
      Consumer<String> onChunk = invocation.getArgument(3);
      onChunk.accept("ok");
      return null;
    }).when(anthropicClient).streamMessage(any(), any(), any(), any());

    RegistryDashAiAction action = new RegistryDashAiAction(
        params, Optional.of(json), anthropicClient, rateLimiter, promptConfig);
    action.run();
    return capturedPrompt[0];
  }
```

Add imports as needed: `com.google.common.collect.ImmutableMap`, `google.registry.config.RegistryConfigSettings`.

- [ ] **Step 2: Run test to verify it fails**

```
./nom_build :core:test --tests google.registry.ui.server.console.registrydash.RegistryDashAiActionTest.testSystemPrompt_drawnFromConfig
```

Expected: FAIL — constructor signature does not yet take `Prompts`, and prompt is built from hardcoded strings.

- [ ] **Step 3: Refactor `RegistryDashAiAction`**

Change the constructor and field set in `RegistryDashAiAction.java`:

```java
  private final Optional<JsonElement> payload;
  private final AnthropicClient anthropicClient;
  private final AiRateLimiter rateLimiter;
  private final Gson gson;
  private final RegistryConfigSettings.Prompts promptConfig;

  @Inject
  public RegistryDashAiAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("aiAnalyzePayload") Optional<JsonElement> payload,
      AnthropicClient anthropicClient,
      AiRateLimiter rateLimiter,
      @Config("anthropicPromptConfig") RegistryConfigSettings.Prompts promptConfig) {
    super(consoleApiParams);
    this.payload = payload;
    this.anthropicClient = anthropicClient;
    this.rateLimiter = rateLimiter;
    this.gson = consoleApiParams.gson();
    this.promptConfig = promptConfig;
  }
```

Replace the body of `getDefaultSystemPrompt(...)` (lines 151-196) with:

```java
  private String getDefaultSystemPrompt(
      String page, String promptType, JsonElement chartData, JsonObject metadata) {
    StringBuilder sb = new StringBuilder();
    sb.append(promptConfig.basePreamble).append("\n\n");

    sb.append("## Analysis Type\n");
    sb.append(
        promptConfig.promptTypes.getOrDefault(
            promptType, "Analyze this data and provide insights."))
        .append("\n");

    String pageHint = promptConfig.pageHints.get(page);
    if (pageHint != null && !pageHint.isEmpty()) {
      sb.append("\n## Page\n").append(pageHint).append("\n");
    }

    sb.append("\n## Context\n");
    if (metadata != null) {
      if (metadata.has("dateRange")) {
        sb.append("Date range: ").append(metadata.get("dateRange")).append("\n");
      }
      if (metadata.has("filteredTlds") && metadata.getAsJsonArray("filteredTlds").size() > 0) {
        sb.append("Filtered to TLDs: ").append(metadata.get("filteredTlds")).append("\n");
      }
    }

    sb.append("\n## Data\n```json\n").append(gson.toJson(chartData)).append("\n```\n\n");
    sb.append(promptConfig.responseGuidance);

    return sb.toString();
  }
```

Add imports: `import google.registry.config.RegistryConfig.Config;`, `import google.registry.config.RegistryConfigSettings;`.

- [ ] **Step 4: Update existing test setUp**

In `RegistryDashAiActionTest.setUp()`, build a default `Prompts` instance and pass it through the constructor anywhere `new RegistryDashAiAction(...)` is invoked. Pattern:

```java
  private RegistryConfigSettings.Prompts defaultPromptConfig() {
    RegistryConfigSettings.Prompts p = new RegistryConfigSettings.Prompts();
    p.version = "test-v1";
    p.basePreamble = "You are an expert domain registry analyst.";
    p.responseGuidance = "Be concise.";
    p.promptTypes = ImmutableMap.of(
        "summarize_trends", "Summarize trends.",
        "find_anomalies", "Find anomalies.",
        "suggest_actions", "Suggest actions.",
        "identify_risks", "Identify risks.");
    p.pageHints = ImmutableMap.of();
    p.menus = ImmutableMap.of();
    return p;
  }
```

Update each existing constructor invocation to pass `defaultPromptConfig()` as the 5th argument.

- [ ] **Step 5: Run all `RegistryDashAiActionTest`s**

```
./nom_build :core:test --tests google.registry.ui.server.console.registrydash.RegistryDashAiActionTest
```

Expected: all PASS, including the new `testSystemPrompt_drawnFromConfig`.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/google/registry/ui/server/console/registrydash/RegistryDashAiAction.java \
        core/src/test/java/google/registry/ui/server/console/registrydash/RegistryDashAiActionTest.java
git commit -m "refactor(registry-dash): build AI system prompt from injected config"
```

---

## Task 9: Log `promptVersion` per request (TDD)

**Files:**
- Modify: `core/src/main/java/google/registry/ui/server/console/registrydash/RegistryDashAiAction.java:104-107`
- Modify: `core/src/test/java/google/registry/ui/server/console/registrydash/RegistryDashAiActionTest.java`

- [ ] **Step 1: Write the failing test**

Add to `RegistryDashAiActionTest`:

```java
  @Test
  void testRequest_logsPromptVersion() throws Exception {
    RegistryConfigSettings.Prompts p = defaultPromptConfig();
    p.version = "logged-version-xyz";

    TestLogHandler handler = new TestLogHandler();
    Logger logger = Logger.getLogger(RegistryDashAiAction.class.getName());
    logger.addHandler(handler);

    capturedSystemPrompt(p, "domain-activity", "summarize_trends");

    assertThat(
        handler.getStoredLogRecords().stream()
            .map(LogRecord::getMessage)
            .anyMatch(m -> m.contains("promptVersion=logged-version-xyz")))
        .isTrue();
  }
```

Add imports: `com.google.common.testing.TestLogHandler`, `java.util.logging.LogRecord`, `java.util.logging.Logger`.

- [ ] **Step 2: Run test to verify it fails**

```
./nom_build :core:test --tests google.registry.ui.server.console.registrydash.RegistryDashAiActionTest.testRequest_logsPromptVersion
```

Expected: FAIL — log line does not include `promptVersion=`.

- [ ] **Step 3: Update the log call**

In `RegistryDashAiAction.java` around line 104, replace:

```java
    logger.atInfo().log(
        "AI analysis request: user=%s, page=%s, promptType=%s, model=%s, historySize=%d",
        userEmail, request.page, request.promptType, resolvedModel,
        request.conversationHistory != null ? request.conversationHistory.size() : 0);
```

with:

```java
    logger.atInfo().log(
        "AI analysis request: user=%s, page=%s, promptType=%s, model=%s,"
            + " promptVersion=%s, historySize=%d",
        userEmail, request.page, request.promptType, resolvedModel,
        promptConfig.version,
        request.conversationHistory != null ? request.conversationHistory.size() : 0);
```

- [ ] **Step 4: Run test to verify it passes**

```
./nom_build :core:test --tests google.registry.ui.server.console.registrydash.RegistryDashAiActionTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/google/registry/ui/server/console/registrydash/RegistryDashAiAction.java \
        core/src/test/java/google/registry/ui/server/console/registrydash/RegistryDashAiActionTest.java
git commit -m "feat(registry-dash): log promptVersion on AI analysis requests"
```

---

## Task 10: New `RegistryDashAiPromptsAction` (TDD)

**Files:**
- Create: `core/src/main/java/google/registry/ui/server/console/registrydash/RegistryDashAiPromptsAction.java`
- Create: `core/src/test/java/google/registry/ui/server/console/registrydash/RegistryDashAiPromptsActionTest.java`

- [ ] **Step 1: Write the failing test**

Create `RegistryDashAiPromptsActionTest.java`:

```java
// Copyright 2026 The Nomulus Authors. All Rights Reserved.
package google.registry.ui.server.console.registrydash;

import static com.google.common.truth.Truth.assertThat;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import google.registry.config.RegistryConfigSettings;
import google.registry.model.console.User;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.auth.AuthResult;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.ui.server.console.ConsoleApiParams;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RegistryDashAiPromptsActionTest {

  private final FakeClock clock = new FakeClock(DateTime.parse("2026-01-01T00:00:00Z"));

  @org.junit.jupiter.api.extension.RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  private ConsoleApiParams params;
  private FakeResponse response;
  private RegistryConfigSettings.Prompts promptConfig;

  @BeforeEach
  void setUp() {
    User user = DatabaseHelper.createAdminUser("fte@test.com");
    AuthResult authResult = AuthResult.createUser(user);
    params = ConsoleApiParamsUtils.createFake(authResult);
    response = (FakeResponse) params.response();
    when(params.request().getMethod()).thenReturn("GET");

    RegistryConfigSettings.MenuItem item = new RegistryConfigSettings.MenuItem();
    item.promptType = "summarize_trends";
    item.label = "Summarize trends";
    item.icon = "bar_chart";
    item.userMessage = "Summarize trends.";

    promptConfig = new RegistryConfigSettings.Prompts();
    promptConfig.version = "v1";
    promptConfig.menus = ImmutableMap.of("portfolio", ImmutableList.of(item));
    promptConfig.promptTypes = ImmutableMap.of();
    promptConfig.pageHints = ImmutableMap.of();
    promptConfig.basePreamble = "";
    promptConfig.responseGuidance = "";
  }

  @Test
  void testSuccess_returnsMenuForPage() {
    new RegistryDashAiPromptsAction(params, "portfolio", promptConfig).run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    JsonObject body = JsonParser.parseString(response.getPayload()).getAsJsonObject();
    assertThat(body.get("version").getAsString()).isEqualTo("v1");
    assertThat(body.getAsJsonArray("menu")).hasSize(1);
    assertThat(body.getAsJsonArray("menu").get(0).getAsJsonObject().get("label").getAsString())
        .isEqualTo("Summarize trends");
  }

  @Test
  void testFailure_unknownPage_returns400() {
    new RegistryDashAiPromptsAction(params, "domains", promptConfig).run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
  }

  @Test
  void testFailure_missingPage_returns400() {
    new RegistryDashAiPromptsAction(params, null, promptConfig).run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
  }

  @Test
  void testFailure_pageNotInMenu_returns404() {
    new RegistryDashAiPromptsAction(params, "pricing", promptConfig).run();
    assertThat(response.getStatus()).isEqualTo(SC_NOT_FOUND);
  }

  @Test
  void testFailure_noPermission_returns403() {
    User noPermUser = DatabaseHelper.createUser("noperm@test.com");
    params = ConsoleApiParamsUtils.createFake(AuthResult.createUser(noPermUser));
    when(params.request().getMethod()).thenReturn("GET");
    new RegistryDashAiPromptsAction(params, "portfolio", promptConfig).run();
    assertThat(((FakeResponse) params.response()).getStatus()).isEqualTo(SC_FORBIDDEN);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
./nom_build :core:test --tests google.registry.ui.server.console.registrydash.RegistryDashAiPromptsActionTest
```

Expected: FAIL — `RegistryDashAiPromptsAction` does not exist.

- [ ] **Step 3: Implement the action**

Create `RegistryDashAiPromptsAction.java`:

```java
// Copyright 2026 The Nomulus Authors. All Rights Reserved.
package google.registry.ui.server.console.registrydash;

import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import google.registry.ai.AiAnalyzeRequest;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryConfigSettings;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.User;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.ui.server.console.ConsoleApiAction;
import google.registry.ui.server.console.ConsoleApiParams;
import jakarta.inject.Inject;

@Action(
    service = Service.CONSOLE,
    path = RegistryDashAiPromptsAction.PATH,
    method = Action.Method.GET,
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistryDashAiPromptsAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-dash/ai/prompts";

  private static final Gson PLAIN_GSON = new Gson();

  private final String page;
  private final RegistryConfigSettings.Prompts promptConfig;

  @Inject
  public RegistryDashAiPromptsAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("page") String page,
      @Config("anthropicPromptConfig") RegistryConfigSettings.Prompts promptConfig) {
    super(consoleApiParams);
    this.page = page;
    this.promptConfig = promptConfig;
  }

  @Override
  protected void getHandler(User user) {
    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.VIEW_DASHBOARD_OVERVIEW)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }
    if (page == null || !AiAnalyzeRequest.VALID_PAGES.contains(page)) {
      setFailedResponse("Invalid or missing page parameter", SC_BAD_REQUEST);
      return;
    }
    if (promptConfig.menus == null || !promptConfig.menus.containsKey(page)) {
      setFailedResponse("No prompt menu configured for page: " + page, SC_NOT_FOUND);
      return;
    }
    JsonObject body = new JsonObject();
    body.addProperty("version", promptConfig.version);
    body.add("menu", PLAIN_GSON.toJsonTree(promptConfig.menus.get(page)));
    consoleApiParams.response().setStatus(SC_OK);
    consoleApiParams.response().setPayload(PLAIN_GSON.toJson(body));
  }
}
```

Remove the unused `ImmutableMap` import if your IDE flags it. Gson serializes `List<MenuItem>` via reflection, producing the array of `{promptType, label, icon, userMessage}` objects expected by the frontend.

- [ ] **Step 4: Wire route in `RequestComponent`**

Open `core/src/main/java/google/registry/module/RequestComponent.java`. Find where other registry-dash actions are bound (look for `RegistryDashAiAction`). Add a binding for `RegistryDashAiPromptsAction` next to it.

- [ ] **Step 5: Add `@Parameter("page")` provider**

Open `core/src/main/java/google/registry/ui/server/console/ConsoleModule.java`. Add a provider that extracts `page` from the request query string:

```java
    @Provides
    @Parameter("page")
    @Nullable
    static String providePage(HttpServletRequest req) {
      return req.getParameter("page");
    }
```

(Skip this step if such a provider already exists — search before adding.)

- [ ] **Step 6: Run all action tests**

```
./nom_build :core:test --tests google.registry.ui.server.console.registrydash.RegistryDashAiPromptsActionTest
```

Expected: all 5 tests PASS.

- [ ] **Step 7: Regenerate routing.txt**

```
./gradlew nomulus
cd core && java -jar build/libs/nomulus.jar -e localhost get_routing_map -c google.registry.module.RequestComponent > src/test/resources/google/registry/module/routing.txt
```

Strip any jline warning lines from the top of the file.

- [ ] **Step 8: Commit**

```bash
git add core/src/main/java/google/registry/ui/server/console/registrydash/RegistryDashAiPromptsAction.java \
        core/src/main/java/google/registry/module/RequestComponent.java \
        core/src/main/java/google/registry/ui/server/console/ConsoleModule.java \
        core/src/test/java/google/registry/ui/server/console/registrydash/RegistryDashAiPromptsActionTest.java \
        core/src/test/resources/google/registry/module/routing.txt
git commit -m "feat(registry-dash): add GET /ai/prompts endpoint"
```

---

## Task 11: Frontend `AiPromptsService` (TDD)

**Files:**
- Create: `console-webapp/src/app/registry-dash/ai/ai-prompts.service.ts`
- Create: `console-webapp/src/app/registry-dash/ai/ai-prompts.service.spec.ts`

- [ ] **Step 1: Write the failing test**

Create `ai-prompts.service.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { AiPromptsService } from './ai-prompts.service';

describe('AiPromptsService', () => {
  let service: AiPromptsService;
  let fetchSpy: jasmine.Spy;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(AiPromptsService);
    fetchSpy = spyOn(window, 'fetch');
  });

  it('fetches and returns the menu for a page', async () => {
    fetchSpy.and.resolveTo(new Response(JSON.stringify({
      version: 'v1',
      menu: [{ promptType: 'summarize_trends', label: 'Summarize trends',
               icon: 'bar_chart', userMessage: '...' }],
    })));
    const result = await service.getMenu('portfolio');
    expect(result.version).toBe('v1');
    expect(result.menu.length).toBe(1);
    expect(fetchSpy).toHaveBeenCalledWith(
      '/console-api/registry-dash/ai/prompts?page=portfolio',
      jasmine.objectContaining({ credentials: 'include' }),
    );
  });

  it('caches results so the second call does not refetch', async () => {
    fetchSpy.and.resolveTo(new Response(JSON.stringify({ version: 'v1', menu: [] })));
    await service.getMenu('portfolio');
    await service.getMenu('portfolio');
    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });

  it('falls back to FALLBACK_MENU on fetch error', async () => {
    fetchSpy.and.resolveTo(new Response('boom', { status: 500 }));
    const result = await service.getMenu('portfolio');
    expect(result.version).toBe('fallback');
    expect(result.menu.length).toBeGreaterThan(0);
  });
});
```

- [ ] **Step 2: Run the test (it fails — service doesn't exist)**

```
cd console-webapp && npm run test -- --include='**/ai-prompts.service.spec.ts' --watch=false
```

Expected: FAIL — cannot resolve `./ai-prompts.service`.

- [ ] **Step 3: Implement the service**

Create `ai-prompts.service.ts`:

```typescript
import { Injectable } from '@angular/core';
import { AiPromptOption } from './ai-analysis.models';
import { FALLBACK_MENU } from './ai-prompts';

export interface AiPromptsResponse {
  version: string;
  menu: AiPromptOption[];
}

@Injectable({ providedIn: 'root' })
export class AiPromptsService {
  private cache = new Map<string, AiPromptsResponse>();

  async getMenu(page: string): Promise<AiPromptsResponse> {
    const cached = this.cache.get(page);
    if (cached) return cached;

    try {
      const res = await fetch(
        `/console-api/registry-dash/ai/prompts?page=${encodeURIComponent(page)}`,
        { credentials: 'include' },
      );
      if (!res.ok) throw new Error(`status ${res.status}`);
      const data = (await res.json()) as AiPromptsResponse;
      this.cache.set(page, data);
      return data;
    } catch {
      return { version: 'fallback', menu: FALLBACK_MENU[page] ?? [] };
    }
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```
cd console-webapp && npm run test -- --include='**/ai-prompts.service.spec.ts' --watch=false
```

Expected: 3 specs PASS.

- [ ] **Step 5: Commit**

```bash
git add console-webapp/src/app/registry-dash/ai/ai-prompts.service.ts \
        console-webapp/src/app/registry-dash/ai/ai-prompts.service.spec.ts
git commit -m "feat(registry-dash): AiPromptsService fetches and caches AI menu per page"
```

---

## Task 12: Convert `ai-prompts.ts` to fallback-only

**Files:**
- Modify: `console-webapp/src/app/registry-dash/ai/ai-prompts.ts`

- [ ] **Step 1: Replace `PROMPTS_BY_PAGE` with `FALLBACK_MENU`**

In `ai-prompts.ts`, change the final export to:

```typescript
/**
 * Fallback prompt menu used only when the backend /ai/prompts endpoint is unreachable.
 * The authoritative source of truth is `default-config.yaml` ai.prompts.menus.
 */
export const FALLBACK_MENU: Record<string, AiPromptOption[]> = {
  'domain-activity': DOMAIN_ACTIVITY_PROMPTS,
  'revenue-billing': REVENUE_BILLING_PROMPTS,
  forecasting: FORECASTING_PROMPTS,
  explore: EXPLORE_PROMPTS,
  overview: OVERVIEW_PROMPTS,
};
```

Delete the existing `PROMPTS_BY_PAGE` export. Keep the per-page constant exports for now (consumed only via `FALLBACK_MENU`).

- [ ] **Step 2: Build to surface any other importers**

```
cd console-webapp && npm run build
```

Expected: build fails on the next file that imports `PROMPTS_BY_PAGE` — that's `ai-sparkle-button.component.ts` and is fixed in Task 13. If any other file imports `PROMPTS_BY_PAGE`, fix the import inline (replace with `FALLBACK_MENU` only as a temporary bridge — Task 13 removes it).

- [ ] **Step 3: Commit**

```bash
git add console-webapp/src/app/registry-dash/ai/ai-prompts.ts
git commit -m "refactor(registry-dash): rename PROMPTS_BY_PAGE to FALLBACK_MENU"
```

---

## Task 13: Wire `AiPromptsService` into `ai-sparkle-button`

**Files:**
- Modify: `console-webapp/src/app/registry-dash/ai/ai-sparkle-button.component.ts`

- [ ] **Step 1: Inject the service and load menu lazily**

Replace the existing `PROMPTS_BY_PAGE[page]` lookup with a call to `aiPromptsService.getMenu(page)`. The component currently reads `PROMPTS_BY_PAGE[this.page]` on init; switch to `effect(() => { this.aiPromptsService.getMenu(this.page).then(r => this.menuItems.set(r.menu)); });` (or equivalent based on how the component manages state — preserve existing pattern).

Update imports: remove `import { PROMPTS_BY_PAGE } from './ai-prompts';`, add `import { AiPromptsService } from './ai-prompts.service';`. Inject in the constructor: `constructor(private aiPromptsService: AiPromptsService, ...) {}`.

- [ ] **Step 2: Run the existing component tests**

```
cd console-webapp && npm run test -- --include='**/ai-sparkle-button*' --watch=false
```

Fix any failures by updating mocks to provide the service instead of relying on the constants module.

- [ ] **Step 3: Build the frontend**

```
cd console-webapp && npm run build
```

Expected: success.

- [ ] **Step 4: Commit**

```bash
git add console-webapp/src/app/registry-dash/ai/ai-sparkle-button.component.ts
git commit -m "feat(registry-dash): sparkle button fetches menu from backend prompts endpoint"
```

---

## Task 14: Local end-to-end verification

**No file changes — verification only.**

- [ ] **Step 1: Start the local test server**

Follow `MEMORY.md → reference_local_dev_setup.md`. Run `local-test-data-setup.sql` and `local-create-domains.sql` once the server is up.

- [ ] **Step 2: Smoke each page**

Log in as a dashboard-permitted user. For each of the seven pages (Overview, Domain Activity, Revenue Billing, Forecasting, Explore, Portfolio, Pricing):

1. Navigate to the page.
2. Click the sparkle button.
3. Confirm the 3-item menu renders (icons + labels match the YAML).
4. Click "Summarize trends" and confirm a streaming response renders in the modal.
5. Type a follow-up question and confirm it streams.

- [ ] **Step 3: Verify config-driven prompts**

Edit `default-config.yaml` `ai.prompts.basePreamble` to a sentinel value (`"PREAMBLE_E2E_TEST"`). Restart the test server. Fire any analysis. Confirm the server log line at INFO level includes the sentinel and `promptVersion=v1`. Revert the file.

- [ ] **Step 4: Verify allowlist regression**

```
curl -i -X POST -b "cookie-from-browser" \
  -H "Content-Type: application/json" \
  -d '{"page":"domains","promptType":"summarize_trends","chartData":{}}' \
  http://localhost:8080/console-api/registry-dash/ai/analyze
```

Expected: HTTP 400.

- [ ] **Step 5: Verify prompts endpoint directly**

```
curl -i -b "cookie-from-browser" \
  http://localhost:8080/console-api/registry-dash/ai/prompts?page=portfolio
```

Expected: 200 with JSON body `{"version": "v1", "menu": [...]}`.

- [ ] **Step 6: Commit nothing (verification only)**

If all steps pass, proceed to `gh pr create --base master`.

---

## Self-Review Checklist (run before handoff)

- [ ] Every spec section has at least one task that implements it (sparkle wiring, YAML migration, prompts endpoint, version log, audit, tests, e2e).
- [ ] No "TODO" / "implement later" / "fill in details" placeholders.
- [ ] `Prompts` POJO field names match between `RegistryConfigSettings.java`, the YAML, the `@Config` provider, and `RegistryDashAiAction`.
- [ ] `VALID_PAGES` membership matches the YAML `menus:` keys (both lists contain the same 7 pages).
- [ ] Each task ends with a commit step.
- [ ] Frontend tasks build (`npm run build`); backend tasks compile and pass tests (`./nom_build :core:test --tests …`).
