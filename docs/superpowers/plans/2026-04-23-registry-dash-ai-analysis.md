# Registry Dashboard AI Analysis — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add AI-powered analysis (sparkle button → prompt menu → streaming modal with follow-ups) to three registry dashboard pages: Domain Activity, Revenue Billing, and Forecasting.

**Architecture:** Backend Java action proxies requests to the Anthropic Messages API with SSE streaming. Angular frontend renders a reusable sparkle button per chart, opens a MatDialog modal, and streams the response via `fetch()` + `ReadableStream`. Rate limiting is in-memory per user. System prompts are editable in dev, backend-controlled in production.

**Tech Stack:** Java 21, Dagger 2, OkHttp 4.x, Jakarta Servlets, Angular 17+ (signals, standalone components), Angular Material (MatDialog, MatMenu), Server-Sent Events.

**Spec:** `docs/superpowers/specs/2026-04-22-registry-dash-ai-analysis-design.md`

---

## File Map

### Backend — New Files
| File | Responsibility |
|------|---------------|
| `core/src/main/java/google/registry/ai/AnthropicClient.java` | OkHttp client for Anthropic Messages API (streaming) |
| `core/src/main/java/google/registry/ai/AnthropicModule.java` | Dagger module: API key from Secret Manager, OkHttpClient, config bindings |
| `core/src/main/java/google/registry/ai/AiAnalyzeRequest.java` | POJO for the JSON request payload |
| `core/src/main/java/google/registry/ai/AiRateLimiter.java` | In-memory per-user rate limiter |
| `core/src/main/java/google/registry/ui/server/console/registrydash/RegistryDashAiAction.java` | POST action: auth, rate limit, build messages, stream response |
| `core/src/test/java/google/registry/ai/AnthropicClientTest.java` | Unit tests for AnthropicClient |
| `core/src/test/java/google/registry/ai/AiRateLimiterTest.java` | Unit tests for rate limiter |
| `core/src/test/java/google/registry/ui/server/console/registrydash/RegistryDashAiActionTest.java` | Action integration tests |

### Backend — Modified Files
| File | Change |
|------|--------|
| `core/src/main/java/google/registry/config/RegistryConfigSettings.java` | Add `Ai` inner class |
| `core/src/main/java/google/registry/config/RegistryConfig.java` | Add `@Config` providers for AI settings |
| `core/src/main/java/google/registry/config/files/default-config.yaml` | Add `ai:` section |
| `core/src/main/java/google/registry/ui/server/console/ConsoleModule.java` | Add `@Parameter("aiAnalyzePayload")` provider |
| `core/src/main/java/google/registry/module/RequestComponent.java` | Add `RegistryDashAiAction` binding |

### Frontend — New Files
| File | Responsibility |
|------|---------------|
| `console-webapp/src/app/registry-dash/ai/ai-analysis.models.ts` | TypeScript interfaces for request/response |
| `console-webapp/src/app/registry-dash/ai/ai-prompts.ts` | Predefined prompt templates per page |
| `console-webapp/src/app/registry-dash/ai/ai-analysis.service.ts` | SSE streaming service using fetch + ReadableStream |
| `console-webapp/src/app/registry-dash/ai/ai-analysis-modal.component.ts` | MatDialog modal: streaming markdown, follow-ups, model switcher |
| `console-webapp/src/app/registry-dash/ai/ai-analysis-modal.component.html` | Modal template |
| `console-webapp/src/app/registry-dash/ai/ai-analysis-modal.component.scss` | Modal styles |
| `console-webapp/src/app/registry-dash/ai/ai-sparkle-button.component.ts` | Reusable sparkle button with MatMenu |
| `console-webapp/src/app/registry-dash/ai/ai-sparkle-button.component.html` | Sparkle button template |
| `console-webapp/src/app/registry-dash/ai/ai-sparkle-button.component.scss` | Sparkle button styles |

### Frontend — Modified Files
| File | Change |
|------|--------|
| `console-webapp/src/app/registry-dash/domain-activity/domain-activity.component.ts` | Import and wire sparkle button |
| `console-webapp/src/app/registry-dash/domain-activity/domain-activity.component.html` | Add sparkle buttons to chart headers |
| `console-webapp/src/app/registry-dash/financials/revenue-billing/revenue-billing.component.ts` | Import and wire sparkle button |
| `console-webapp/src/app/registry-dash/financials/revenue-billing/revenue-billing.component.html` | Add sparkle buttons to chart headers |
| `console-webapp/src/app/registry-dash/financials/forecasting/forecasting.component.ts` | Import and wire sparkle button |
| `console-webapp/src/app/registry-dash/financials/forecasting/forecasting.component.html` | Add sparkle buttons to chart headers |

---

## Task 1: Backend Config — Add AI Section to YAML + RegistryConfigSettings

**Files:**
- Modify: `core/src/main/java/google/registry/config/files/default-config.yaml`
- Modify: `core/src/main/java/google/registry/config/RegistryConfigSettings.java`
- Modify: `core/src/main/java/google/registry/config/RegistryConfig.java`

- [ ] **Step 1: Add `ai` section to default-config.yaml**

Append before the end of the file (after the `mosapi:` section):

```yaml
ai:
  # Anthropic API base URL
  apiBaseUrl: https://api.anthropic.com
  # Secret Manager secret name for the Anthropic API key
  apiKeySecretName: ud_rsp_anthropic_api_key
  # Default model (haiku, sonnet, opus)
  defaultModel: sonnet
  # Rate limit: max requests per user per hour
  rateLimitPerHour: 120
```

- [ ] **Step 2: Add `Ai` inner class to RegistryConfigSettings.java**

Add the field to the top-level class (after the `mosapi` field ~line 45):

```java
public Ai ai;
```

Add the inner class (before the closing brace of RegistryConfigSettings):

```java
public static class Ai {
  public String apiBaseUrl;
  public String apiKeySecretName;
  public String defaultModel;
  public int rateLimitPerHour;
}
```

- [ ] **Step 3: Add @Config providers in RegistryConfig.java**

Add these inside the `ConfigModule` class (after the mosapi providers, ~line 1430):

```java
@Provides
@Config("anthropicApiBaseUrl")
public static String provideAnthropicApiBaseUrl(RegistryConfigSettings config) {
  return config.ai.apiBaseUrl;
}

@Provides
@Config("anthropicApiKeySecretName")
public static String provideAnthropicApiKeySecretName(RegistryConfigSettings config) {
  return config.ai.apiKeySecretName;
}

@Provides
@Config("anthropicDefaultModel")
public static String provideAnthropicDefaultModel(RegistryConfigSettings config) {
  return config.ai.defaultModel;
}

@Provides
@Config("anthropicRateLimitPerHour")
public static int provideAnthropicRateLimitPerHour(RegistryConfigSettings config) {
  return config.ai.rateLimitPerHour;
}
```

- [ ] **Step 4: Verify the build compiles**

Run:
```bash
cd /Users/tjones/conductor/workspaces/nomulus/jerusalem-v2 && ./nom_build :core:compileJava 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/google/registry/config/files/default-config.yaml \
  core/src/main/java/google/registry/config/RegistryConfigSettings.java \
  core/src/main/java/google/registry/config/RegistryConfig.java
git commit -m "feat(registry-dash): add AI config section for Anthropic API settings"
```

---

## Task 2: Backend — AiRateLimiter

**Files:**
- Create: `core/src/main/java/google/registry/ai/AiRateLimiter.java`
- Create: `core/src/test/java/google/registry/ai/AiRateLimiterTest.java`

- [ ] **Step 1: Write the test**

```java
package google.registry.ai;

import static com.google.common.truth.Truth.assertThat;

import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.Test;

class AiRateLimiterTest {

  private final FakeClock clock = new FakeClock(DateTime.parse("2026-01-01T00:00:00Z"));

  @Test
  void testAllowsRequestsUnderLimit() {
    AiRateLimiter limiter = new AiRateLimiter(clock, 5);
    for (int i = 0; i < 5; i++) {
      assertThat(limiter.tryAcquire("user@test.com")).isTrue();
    }
  }

  @Test
  void testBlocksRequestsOverLimit() {
    AiRateLimiter limiter = new AiRateLimiter(clock, 3);
    for (int i = 0; i < 3; i++) {
      assertThat(limiter.tryAcquire("user@test.com")).isTrue();
    }
    assertThat(limiter.tryAcquire("user@test.com")).isFalse();
  }

  @Test
  void testSlidingWindowExpiry() {
    AiRateLimiter limiter = new AiRateLimiter(clock, 2);
    assertThat(limiter.tryAcquire("user@test.com")).isTrue();
    assertThat(limiter.tryAcquire("user@test.com")).isTrue();
    assertThat(limiter.tryAcquire("user@test.com")).isFalse();

    clock.advanceBy(Duration.standardMinutes(61));

    assertThat(limiter.tryAcquire("user@test.com")).isTrue();
  }

  @Test
  void testIndependentPerUser() {
    AiRateLimiter limiter = new AiRateLimiter(clock, 1);
    assertThat(limiter.tryAcquire("a@test.com")).isTrue();
    assertThat(limiter.tryAcquire("a@test.com")).isFalse();
    assertThat(limiter.tryAcquire("b@test.com")).isTrue();
  }

  @Test
  void testGetRetryAfterSeconds() {
    AiRateLimiter limiter = new AiRateLimiter(clock, 1);
    limiter.tryAcquire("user@test.com");
    limiter.tryAcquire("user@test.com");

    long retryAfter = limiter.getRetryAfterSeconds("user@test.com");
    assertThat(retryAfter).isGreaterThan(0);
    assertThat(retryAfter).isAtMost(3600);
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
cd /Users/tjones/conductor/workspaces/nomulus/jerusalem-v2 && ./nom_build :core:test --tests "google.registry.ai.AiRateLimiterTest" 2>&1 | tail -20
```

Expected: FAIL — class AiRateLimiter not found

- [ ] **Step 3: Implement AiRateLimiter**

```java
package google.registry.ai;

import com.google.common.annotations.VisibleForTesting;
import google.registry.util.Clock;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.joda.time.Duration;

@Singleton
public class AiRateLimiter {

  private static final Duration WINDOW = Duration.standardHours(1);

  private final Clock clock;
  private final int maxPerHour;
  private final ConcurrentHashMap<String, Deque<Long>> requests = new ConcurrentHashMap<>();

  @Inject
  public AiRateLimiter(Clock clock, @jakarta.inject.Named("aiRateLimitPerHour") int maxPerHour) {
    this.clock = clock;
    this.maxPerHour = maxPerHour;
  }

  @VisibleForTesting
  AiRateLimiter(Clock clock, int maxPerHour) {
    this.clock = clock;
    this.maxPerHour = maxPerHour;
  }

  public boolean tryAcquire(String userEmail) {
    long now = clock.nowUtc().getMillis();
    long cutoff = now - WINDOW.getMillis();
    Deque<Long> timestamps = requests.computeIfAbsent(userEmail, k -> new ConcurrentLinkedDeque<>());

    while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
      timestamps.pollFirst();
    }

    if (timestamps.size() >= maxPerHour) {
      return false;
    }
    timestamps.addLast(now);
    return true;
  }

  public long getRetryAfterSeconds(String userEmail) {
    long now = clock.nowUtc().getMillis();
    Deque<Long> timestamps = requests.get(userEmail);
    if (timestamps == null || timestamps.isEmpty()) {
      return 0;
    }
    long oldest = timestamps.peekFirst();
    long expiresAt = oldest + WINDOW.getMillis();
    return Math.max(1, (expiresAt - now) / 1000);
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
cd /Users/tjones/conductor/workspaces/nomulus/jerusalem-v2 && ./nom_build :core:test --tests "google.registry.ai.AiRateLimiterTest" 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, all 5 tests pass

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/google/registry/ai/AiRateLimiter.java \
  core/src/test/java/google/registry/ai/AiRateLimiterTest.java
git commit -m "feat(registry-dash): add in-memory per-user AI rate limiter"
```

---

## Task 3: Backend — AiAnalyzeRequest POJO

**Files:**
- Create: `core/src/main/java/google/registry/ai/AiAnalyzeRequest.java`

- [ ] **Step 1: Create the request POJO**

```java
package google.registry.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;

public class AiAnalyzeRequest {

  public String page;
  public String promptType;
  public JsonObject metadata;
  public JsonElement chartData;
  public String model;
  public String systemPrompt;
  public List<ConversationMessage> conversationHistory;

  public static class ConversationMessage {
    public String role;
    public String content;
  }

  public boolean isValid() {
    return page != null
        && promptType != null
        && chartData != null
        && (page.equals("domain-activity")
            || page.equals("revenue-billing")
            || page.equals("forecasting"));
  }
}
```

- [ ] **Step 2: Verify compilation**

Run:
```bash
cd /Users/tjones/conductor/workspaces/nomulus/jerusalem-v2 && ./nom_build :core:compileJava 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/google/registry/ai/AiAnalyzeRequest.java
git commit -m "feat(registry-dash): add AiAnalyzeRequest POJO"
```

---

## Task 4: Backend — AnthropicClient + AnthropicModule

**Files:**
- Create: `core/src/main/java/google/registry/ai/AnthropicClient.java`
- Create: `core/src/main/java/google/registry/ai/AnthropicModule.java`
- Create: `core/src/test/java/google/registry/ai/AnthropicClientTest.java`

- [ ] **Step 1: Write the test**

```java
package google.registry.ai;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnthropicClientTest {

  private MockWebServer server;
  private AnthropicClient client;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
    client = new AnthropicClient(
        new OkHttpClient(),
        server.url("/").toString(),
        "test-api-key",
        "sonnet");
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  @Test
  void testStreamingRequest_sendsCorrectHeaders() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}\n\n"
            + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"));

    List<String> chunks = new ArrayList<>();
    client.streamMessage("system prompt", List.of(), "sonnet", chunks::add);

    RecordedRequest request = server.takeRequest();
    assertThat(request.getHeader("x-api-key")).isEqualTo("test-api-key");
    assertThat(request.getHeader("anthropic-version")).isEqualTo("2023-06-01");
    assertThat(request.getHeader("Content-Type")).isEqualTo("application/json");
    assertThat(request.getBody().readUtf8()).contains("\"stream\":true");
  }

  @Test
  void testStreamingRequest_parsesTextChunks() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello \"}}\n\n"
            + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"world\"}}\n\n"
            + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"));

    List<String> chunks = new ArrayList<>();
    client.streamMessage("system prompt", List.of(), "sonnet", chunks::add);

    assertThat(chunks).containsExactly("Hello ", "world").inOrder();
  }

  @Test
  void testStreamingRequest_handlesApiError() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":{\"message\":\"Internal error\"}}"));

    List<String> chunks = new ArrayList<>();
    IOException thrown = assertThrows(IOException.class,
        () -> client.streamMessage("system prompt", List.of(), "sonnet", chunks::add));
    assertThat(thrown.getMessage()).contains("500");
  }

  @Test
  void testStreamingRequest_handlesRateLimit() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\":{\"message\":\"Rate limited\"}}"));

    List<String> chunks = new ArrayList<>();
    AnthropicClient.AnthropicRateLimitException thrown =
        assertThrows(AnthropicClient.AnthropicRateLimitException.class,
            () -> client.streamMessage("system prompt", List.of(), "sonnet", chunks::add));
    assertThat(thrown.getMessage()).contains("429");
  }

  @Test
  void testModelMapping() {
    assertThat(AnthropicClient.resolveModelId("haiku")).isEqualTo("claude-haiku-4-5-20251001");
    assertThat(AnthropicClient.resolveModelId("sonnet")).isEqualTo("claude-sonnet-4-6-20250514");
    assertThat(AnthropicClient.resolveModelId("opus")).isEqualTo("claude-opus-4-6-20250514");
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
cd /Users/tjones/conductor/workspaces/nomulus/jerusalem-v2 && ./nom_build :core:test --tests "google.registry.ai.AnthropicClientTest" 2>&1 | tail -20
```

Expected: FAIL — class AnthropicClient not found

- [ ] **Step 3: Add `okhttp3:mockwebserver` test dependency if not present**

Check first:
```bash
grep -c "mockwebserver" /Users/tjones/conductor/workspaces/nomulus/jerusalem-v2/core/build.gradle
```

If 0, add to `core/build.gradle` in the `dependencies` block:
```gradle
testImplementation deps['com.squareup.okhttp3:mockwebserver']
```

And ensure the version is in `dependencies.gradle`:
```gradle
'com.squareup.okhttp3:mockwebserver:[4.10.0, 5.0.0)!!',
```

- [ ] **Step 4: Implement AnthropicClient**

```java
package google.registry.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Singleton
public class AnthropicClient {

  private static final String ANTHROPIC_VERSION = "2023-06-01";
  private static final int MAX_TOKENS = 4096;
  private static final Map<String, String> MODEL_MAP = Map.of(
      "haiku", "claude-haiku-4-5-20251001",
      "sonnet", "claude-sonnet-4-6-20250514",
      "opus", "claude-opus-4-6-20250514");
  private static final Gson GSON = new Gson();

  private final OkHttpClient httpClient;
  private final String baseUrl;
  private final String apiKey;
  private final String defaultModel;

  @Inject
  public AnthropicClient(
      @Named("anthropicHttpClient") OkHttpClient httpClient,
      @Named("anthropicApiBaseUrl") String baseUrl,
      @Named("anthropicApiKey") String apiKey,
      @Named("anthropicDefaultModel") String defaultModel) {
    this.httpClient = httpClient;
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.apiKey = apiKey;
    this.defaultModel = defaultModel;
  }

  public void streamMessage(
      String systemPrompt,
      List<AiAnalyzeRequest.ConversationMessage> conversationHistory,
      String modelOverride,
      Consumer<String> onChunk) throws IOException {

    String model = resolveModelId(modelOverride != null ? modelOverride : defaultModel);

    JsonObject body = new JsonObject();
    body.addProperty("model", model);
    body.addProperty("max_tokens", MAX_TOKENS);
    body.addProperty("stream", true);
    body.addProperty("system", systemPrompt);

    JsonArray messages = new JsonArray();
    if (conversationHistory != null) {
      for (AiAnalyzeRequest.ConversationMessage msg : conversationHistory) {
        JsonObject msgObj = new JsonObject();
        msgObj.addProperty("role", msg.role);
        msgObj.addProperty("content", msg.content);
        messages.add(msgObj);
      }
    }
    body.add("messages", messages);

    RequestBody requestBody = RequestBody.create(
        GSON.toJson(body), MediaType.parse("application/json"));

    Request request = new Request.Builder()
        .url(baseUrl + "/v1/messages")
        .post(requestBody)
        .addHeader("x-api-key", apiKey)
        .addHeader("anthropic-version", ANTHROPIC_VERSION)
        .addHeader("Content-Type", "application/json")
        .build();

    try (Response response = httpClient.newCall(request).execute()) {
      if (response.code() == 429) {
        throw new AnthropicRateLimitException("Anthropic API rate limited: " + response.code());
      }
      if (!response.isSuccessful()) {
        throw new IOException("Anthropic API error: " + response.code());
      }

      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (!line.startsWith("data: ")) continue;
          String data = line.substring(6).trim();
          if (data.equals("[DONE]")) break;

          try {
            JsonObject event = GSON.fromJson(data, JsonObject.class);
            if (event.has("type") && event.get("type").getAsString().equals("content_block_delta")) {
              JsonObject delta = event.getAsJsonObject("delta");
              if (delta.has("text")) {
                onChunk.accept(delta.get("text").getAsString());
              }
            }
          } catch (Exception e) {
            // Skip non-JSON or non-delta lines
          }
        }
      }
    }
  }

  public static String resolveModelId(String shortName) {
    return MODEL_MAP.getOrDefault(shortName, MODEL_MAP.get("sonnet"));
  }

  public static class AnthropicRateLimitException extends IOException {
    public AnthropicRateLimitException(String message) {
      super(message);
    }
  }
}
```

- [ ] **Step 5: Implement AnthropicModule**

```java
package google.registry.ai;

import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.Config;
import google.registry.privileges.secretmanager.SecretManagerClient;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

@Module
public final class AnthropicModule {

  private static final String LATEST_SECRET_VERSION = "latest";

  @Provides
  @Singleton
  @Named("anthropicHttpClient")
  static OkHttpClient provideAnthropicHttpClient() {
    return new OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build();
  }

  @Provides
  @Singleton
  @Named("anthropicApiKey")
  static String provideAnthropicApiKey(
      SecretManagerClient secretManagerClient,
      @Config("anthropicApiKeySecretName") String secretName) {
    return secretManagerClient.getSecretData(secretName, Optional.of(LATEST_SECRET_VERSION));
  }

  @Provides
  @Named("anthropicApiBaseUrl")
  static String provideAnthropicApiBaseUrl(@Config("anthropicApiBaseUrl") String baseUrl) {
    return baseUrl;
  }

  @Provides
  @Named("anthropicDefaultModel")
  static String provideAnthropicDefaultModel(@Config("anthropicDefaultModel") String model) {
    return model;
  }

  @Provides
  @Singleton
  @Named("aiRateLimitPerHour")
  static int provideAiRateLimitPerHour(@Config("anthropicRateLimitPerHour") int limit) {
    return limit;
  }
}
```

- [ ] **Step 6: Run the test**

Run:
```bash
cd /Users/tjones/conductor/workspaces/nomulus/jerusalem-v2 && ./nom_build :core:test --tests "google.registry.ai.AnthropicClientTest" 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 7: Commit**

```bash
git add core/src/main/java/google/registry/ai/AnthropicClient.java \
  core/src/main/java/google/registry/ai/AnthropicModule.java \
  core/src/test/java/google/registry/ai/AnthropicClientTest.java
git commit -m "feat(registry-dash): add AnthropicClient with SSE streaming and Dagger module"
```

---

## Task 5: Backend — RegistryDashAiAction + DI Wiring

**Files:**
- Create: `core/src/main/java/google/registry/ui/server/console/registrydash/RegistryDashAiAction.java`
- Create: `core/src/test/java/google/registry/ui/server/console/registrydash/RegistryDashAiActionTest.java`
- Modify: `core/src/main/java/google/registry/ui/server/console/ConsoleModule.java`
- Modify: `core/src/main/java/google/registry/module/RequestComponent.java`

- [ ] **Step 1: Add the JSON payload provider in ConsoleModule.java**

Add after the existing `provideSettingsPayload` provider:

```java
@Provides
@Parameter("aiAnalyzePayload")
public static Optional<JsonElement> provideAiAnalyzePayload(
    @OptionalJsonPayload Optional<JsonElement> payload) {
  return payload;
}
```

- [ ] **Step 2: Add the action binding in RequestComponent.java**

Add in the registry-dash section (after `RegistryDashSettingsAction`):

```java
RegistryDashAiAction registryDashAiAction();
```

- [ ] **Step 3: Add AnthropicModule to RequestComponent's @Subcomponent modules list**

In RequestComponent.java, add `AnthropicModule.class` to the modules array:

```java
@RequestScope
@Subcomponent(
    modules = {
      // ... existing modules ...
      AnthropicModule.class,
      // ... rest of modules ...
    })
```

- [ ] **Step 4: Implement RegistryDashAiAction**

```java
package google.registry.ui.server.console.registrydash;

import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_TOO_MANY_REQUESTS;

import com.google.common.flogger.FluentLogger;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import google.registry.ai.AiAnalyzeRequest;
import google.registry.ai.AiRateLimiter;
import google.registry.ai.AnthropicClient;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.ui.server.console.ConsoleApiAction;
import google.registry.ui.server.console.ConsoleApiParams;
import google.registry.util.RegistryEnvironment;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Optional;

@Action(
    service = Service.CONSOLE,
    path = RegistryDashAiAction.PATH,
    method = Action.Method.POST,
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistryDashAiAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-dash/ai/analyze";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Optional<JsonElement> payload;
  private final AnthropicClient anthropicClient;
  private final AiRateLimiter rateLimiter;
  private final Gson gson;

  @Inject
  public RegistryDashAiAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("aiAnalyzePayload") Optional<JsonElement> payload,
      AnthropicClient anthropicClient,
      AiRateLimiter rateLimiter) {
    super(consoleApiParams);
    this.payload = payload;
    this.anthropicClient = anthropicClient;
    this.rateLimiter = rateLimiter;
    this.gson = consoleApiParams.gson();
  }

  @Override
  protected void postHandler(User user) {
    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.VIEW_DASHBOARD_OVERVIEW)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    if (payload.isEmpty()) {
      setFailedResponse("Request body is required", SC_BAD_REQUEST);
      return;
    }

    AiAnalyzeRequest request = gson.fromJson(payload.get(), AiAnalyzeRequest.class);
    if (!request.isValid()) {
      setFailedResponse("Invalid request: page and chartData are required", SC_BAD_REQUEST);
      return;
    }

    String userEmail = user.getEmailAddress();
    if (!rateLimiter.tryAcquire(userEmail)) {
      consoleApiParams.response().setStatus(SC_TOO_MANY_REQUESTS);
      consoleApiParams.response().setHeader(
          "Retry-After", String.valueOf(rateLimiter.getRetryAfterSeconds(userEmail)));
      setFailedResponse("Rate limit exceeded", SC_TOO_MANY_REQUESTS);
      return;
    }

    String systemPrompt = buildSystemPrompt(request, user);
    String model = request.model;

    try {
      PrintWriter writer = consoleApiParams.response().getWriter();
      consoleApiParams.response().setHeader("Content-Type", "text/event-stream");
      consoleApiParams.response().setHeader("Cache-Control", "no-cache");
      consoleApiParams.response().setHeader("Connection", "keep-alive");
      consoleApiParams.response().setStatus(200);

      anthropicClient.streamMessage(
          systemPrompt,
          request.conversationHistory,
          model,
          chunk -> {
            writer.write("data: " + gson.toJson(new TextChunk(chunk)) + "\n\n");
            writer.flush();
          });

      writer.write("data: [DONE]\n\n");
      writer.flush();

    } catch (AnthropicClient.AnthropicRateLimitException e) {
      logger.atWarning().withCause(e).log("Anthropic rate limit hit");
      consoleApiParams.response().setStatus(503);
      consoleApiParams.response().setHeader("Retry-After", "30");
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Anthropic API error");
      consoleApiParams.response().setStatus(502);
    }
  }

  private String buildSystemPrompt(AiAnalyzeRequest request, User user) {
    boolean isProduction = RegistryEnvironment.get() == RegistryEnvironment.PRODUCTION;
    boolean isAdmin = user.getUserRoles().getGlobalRole() == GlobalRole.FTE;

    if (!isProduction && isAdmin && request.systemPrompt != null && !request.systemPrompt.isEmpty()) {
      return request.systemPrompt;
    }

    return getDefaultSystemPrompt(request.page, request.promptType, request.chartData, request.metadata);
  }

  private String getDefaultSystemPrompt(
      String page, String promptType, JsonElement chartData, com.google.gson.JsonObject metadata) {
    StringBuilder sb = new StringBuilder();
    sb.append("You are an expert domain registry analyst. ");
    sb.append("You are analyzing data from the ").append(page).append(" page of a domain registry dashboard.\n\n");

    sb.append("## Analysis Type\n");
    switch (promptType) {
      case "summarize_trends":
        sb.append("Summarize the key trends in this data. Identify growth or decline patterns, ");
        sb.append("compare across TLDs, and highlight the most significant changes.\n");
        break;
      case "find_anomalies":
        sb.append("Identify anomalies, outliers, and unusual patterns in this data. ");
        sb.append("Look for unexpected spikes, drops, or ratios that warrant investigation.\n");
        break;
      case "suggest_actions":
        sb.append("Based on this data, suggest specific actionable recommendations. ");
        sb.append("Focus on opportunities for growth, risk mitigation, and operational improvements.\n");
        break;
      case "identify_risks":
        sb.append("Identify risks in this data. Look for expiration cliffs, declining registrars, ");
        sb.append("and patterns that could lead to revenue loss.\n");
        break;
      default:
        sb.append("Analyze this data and provide insights.\n");
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

    sb.append("\n## Data\n```json\n").append(gson.toJson(chartData)).append("\n```\n");
    sb.append("\nProvide your analysis in clear markdown. Use specific numbers from the data. ");
    sb.append("Keep your response concise and actionable.");

    return sb.toString();
  }

  private record TextChunk(String text) {}
}
```

- [ ] **Step 5: Write the action test**

```java
package google.registry.ui.server.console.registrydash;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import google.registry.ai.AiAnalyzeRequest;
import google.registry.ai.AiRateLimiter;
import google.registry.ai.AnthropicClient;
import google.registry.model.console.User;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.auth.AuthResult;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.ui.server.console.ConsoleApiParams;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.function.Consumer;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class RegistryDashAiActionTest {

  private final FakeClock clock = new FakeClock(DateTime.parse("2026-01-01T00:00:00Z"));

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @Mock private AnthropicClient anthropicClient;
  private AiRateLimiter rateLimiter;
  private User fteUser;
  private ConsoleApiParams params;
  private FakeResponse response;

  @BeforeEach
  void setUp() {
    fteUser = DatabaseHelper.createAdminUser("fte@test.com");
    AuthResult authResult = AuthResult.createUser(fteUser);
    params = ConsoleApiParamsUtils.createFake(authResult);
    response = (FakeResponse) params.response();
    rateLimiter = new AiRateLimiter(clock, 120);
    org.mockito.Mockito.when(params.request().getMethod()).thenReturn("POST");
  }

  @Test
  void testSuccess_streamsResponse() throws Exception {
    String payload = """
        {"page":"domain-activity","promptType":"summarize_trends",
         "chartData":{"activity":[]},"conversationHistory":[
           {"role":"user","content":"Summarize trends"}
         ]}""";
    JsonElement json = JsonParser.parseString(payload);

    doAnswer(invocation -> {
      Consumer<String> onChunk = invocation.getArgument(3);
      onChunk.accept("Hello ");
      onChunk.accept("world");
      return null;
    }).when(anthropicClient).streamMessage(any(), any(), any(), any());

    RegistryDashAiAction action = new RegistryDashAiAction(
        params, Optional.of(json), anthropicClient, rateLimiter);
    action.run();

    assertThat(response.getStatus()).isEqualTo(200);
    String written = response.getStringWriter().toString();
    assertThat(written).contains("Hello ");
    assertThat(written).contains("world");
    assertThat(written).contains("[DONE]");
  }

  @Test
  void testBadRequest_missingPayload() {
    RegistryDashAiAction action = new RegistryDashAiAction(
        params, Optional.empty(), anthropicClient, rateLimiter);
    action.run();

    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void testBadRequest_invalidPage() {
    String payload = """
        {"page":"invalid","promptType":"summarize_trends",
         "chartData":{},"conversationHistory":[]}""";
    JsonElement json = JsonParser.parseString(payload);

    RegistryDashAiAction action = new RegistryDashAiAction(
        params, Optional.of(json), anthropicClient, rateLimiter);
    action.run();

    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void testRateLimitExceeded() {
    AiRateLimiter strictLimiter = new AiRateLimiter(clock, 0);
    String payload = """
        {"page":"domain-activity","promptType":"summarize_trends",
         "chartData":{"activity":[]},"conversationHistory":[
           {"role":"user","content":"test"}
         ]}""";
    JsonElement json = JsonParser.parseString(payload);

    RegistryDashAiAction action = new RegistryDashAiAction(
        params, Optional.of(json), anthropicClient, strictLimiter);
    action.run();

    assertThat(response.getStatus()).isEqualTo(429);
  }
}
```

- [ ] **Step 6: Run the tests**

Run:
```bash
cd /Users/tjones/conductor/workspaces/nomulus/jerusalem-v2 && ./nom_build :core:test --tests "google.registry.ui.server.console.registrydash.RegistryDashAiActionTest" 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 7: Regenerate the routing golden file**

Run:
```bash
cd /Users/tjones/conductor/workspaces/nomulus/jerusalem-v2 && ./nom_build nomulus && cd core && java -jar build/libs/nomulus.jar -e localhost get_routing_map -c google.registry.module.RequestComponent > src/test/resources/google/registry/module/routing.txt
```

Strip any jline warning lines from the top of the output if present.

- [ ] **Step 8: Run the routing test to verify**

Run:
```bash
cd /Users/tjones/conductor/workspaces/nomulus/jerusalem-v2 && ./nom_build :core:test --tests "google.registry.module.RoutingMapTest" 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add core/src/main/java/google/registry/ui/server/console/registrydash/RegistryDashAiAction.java \
  core/src/main/java/google/registry/ui/server/console/ConsoleModule.java \
  core/src/main/java/google/registry/module/RequestComponent.java \
  core/src/test/java/google/registry/ui/server/console/registrydash/RegistryDashAiActionTest.java \
  core/src/test/resources/google/registry/module/routing.txt
git commit -m "feat(registry-dash): add RegistryDashAiAction with SSE streaming and DI wiring"
```

---

## Task 6: Frontend — Models and Prompt Templates

**Files:**
- Create: `console-webapp/src/app/registry-dash/ai/ai-analysis.models.ts`
- Create: `console-webapp/src/app/registry-dash/ai/ai-prompts.ts`

- [ ] **Step 1: Create models file**

```typescript
export interface AiAnalyzeRequest {
  page: 'domain-activity' | 'revenue-billing' | 'forecasting';
  promptType: string;
  metadata: {
    dateRange: { start: string; end: string };
    granularity?: string;
    filteredTlds: string[];
    filteredRegistrars: string[];
    [key: string]: any;
  };
  chartData: any;
  model?: string;
  systemPrompt?: string;
  conversationHistory: ConversationMessage[];
}

export interface ConversationMessage {
  role: 'user' | 'assistant';
  content: string;
}

export interface AiPromptOption {
  icon: string;
  label: string;
  promptType: string;
  userMessage: string;
}

export type AiModelChoice = 'haiku' | 'sonnet' | 'opus';
```

- [ ] **Step 2: Create prompts file**

```typescript
import { AiPromptOption } from './ai-analysis.models';

export const DOMAIN_ACTIVITY_PROMPTS: AiPromptOption[] = [
  {
    icon: 'bar_chart',
    label: 'Summarize trends',
    promptType: 'summarize_trends',
    userMessage: 'Summarize the key trends in domain activity — lifecycle patterns, growth or decline across TLDs.',
  },
  {
    icon: 'search',
    label: 'Find anomalies',
    promptType: 'find_anomalies',
    userMessage: 'Identify anomalies in domain activity — unexpected spikes, unusual create/delete ratios, outlier TLDs.',
  },
  {
    icon: 'lightbulb',
    label: 'Suggest actions',
    promptType: 'suggest_actions',
    userMessage: 'Based on this domain activity data, suggest specific actions for retention and growth.',
  },
];

export const REVENUE_BILLING_PROMPTS: AiPromptOption[] = [
  {
    icon: 'bar_chart',
    label: 'Summarize trends',
    promptType: 'summarize_trends',
    userMessage: 'Summarize revenue trends — key drivers, growth percentages, TLD performance comparison.',
  },
  {
    icon: 'search',
    label: 'Find anomalies',
    promptType: 'find_anomalies',
    userMessage: 'Identify revenue anomalies — unexpected spikes or drops, declining segments, unusual patterns.',
  },
  {
    icon: 'lightbulb',
    label: 'Suggest actions',
    promptType: 'suggest_actions',
    userMessage: 'Based on this revenue data, suggest pricing adjustments, registrar outreach, or growth opportunities.',
  },
];

export const FORECASTING_PROMPTS: AiPromptOption[] = [
  {
    icon: 'bar_chart',
    label: 'Summarize trends',
    promptType: 'summarize_trends',
    userMessage: 'Summarize renewal health — overall rates, TLD comparison, trajectory.',
  },
  {
    icon: 'warning',
    label: 'Identify risks',
    promptType: 'identify_risks',
    userMessage: 'Identify risks — expiration cliffs, declining registrars, TLDs with dropping renewal rates.',
  },
  {
    icon: 'lightbulb',
    label: 'Suggest actions',
    promptType: 'suggest_actions',
    userMessage: 'Suggest retention strategies, pricing recommendations, and proactive outreach based on this forecast data.',
  },
];

export const PROMPTS_BY_PAGE: Record<string, AiPromptOption[]> = {
  'domain-activity': DOMAIN_ACTIVITY_PROMPTS,
  'revenue-billing': REVENUE_BILLING_PROMPTS,
  'forecasting': FORECASTING_PROMPTS,
};
```

- [ ] **Step 3: Commit**

```bash
git add console-webapp/src/app/registry-dash/ai/ai-analysis.models.ts \
  console-webapp/src/app/registry-dash/ai/ai-prompts.ts
git commit -m "feat(registry-dash): add AI analysis models and prompt templates"
```

---

## Task 7: Frontend — AI Analysis Service (SSE Streaming)

**Files:**
- Create: `console-webapp/src/app/registry-dash/ai/ai-analysis.service.ts`

- [ ] **Step 1: Implement the service**

```typescript
import { Injectable, signal } from '@angular/core';
import { AiAnalyzeRequest, AiModelChoice, ConversationMessage } from './ai-analysis.models';

@Injectable({ providedIn: 'root' })
export class AiAnalysisService {
  streaming = signal(false);
  streamedText = signal('');
  error = signal<string | null>(null);

  async analyze(request: AiAnalyzeRequest): Promise<void> {
    this.streaming.set(true);
    this.streamedText.set('');
    this.error.set(null);

    try {
      const xsrfCookie = document.cookie
        .split('; ')
        .find(c => c.startsWith('X-CSRF-Token='));
      const xsrfToken = xsrfCookie?.split('=')[1] ?? '';

      const response = await fetch('/console-api/registry-dash/ai/analyze', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
        credentials: 'same-origin',
      });

      if (response.status === 429) {
        const retryAfter = response.headers.get('Retry-After');
        const minutes = retryAfter ? Math.ceil(parseInt(retryAfter, 10) / 60) : 5;
        this.error.set(`Analysis limit reached. Try again in ${minutes} minutes.`);
        return;
      }
      if (response.status === 502) {
        this.error.set('Analysis temporarily unavailable. Please try again.');
        return;
      }
      if (response.status === 503) {
        this.error.set('AI service is busy. Please try again shortly.');
        return;
      }
      if (!response.ok) {
        this.error.set('Analysis failed. Please try again.');
        return;
      }

      const reader = response.body?.getReader();
      if (!reader) {
        this.error.set('Streaming not supported.');
        return;
      }

      const decoder = new TextDecoder();
      let buffer = '';
      let accumulated = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() ?? '';

        for (const line of lines) {
          if (!line.startsWith('data: ')) continue;
          const data = line.substring(6).trim();
          if (data === '[DONE]') break;
          try {
            const parsed = JSON.parse(data);
            if (parsed.text) {
              accumulated += parsed.text;
              this.streamedText.set(accumulated);
            }
          } catch {
            // skip malformed chunks
          }
        }
      }
    } catch (e) {
      this.error.set('Response interrupted. Try again?');
    } finally {
      this.streaming.set(false);
    }
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add console-webapp/src/app/registry-dash/ai/ai-analysis.service.ts
git commit -m "feat(registry-dash): add AI analysis service with SSE streaming"
```

---

## Task 8: Frontend — AI Analysis Modal Component

**Files:**
- Create: `console-webapp/src/app/registry-dash/ai/ai-analysis-modal.component.ts`
- Create: `console-webapp/src/app/registry-dash/ai/ai-analysis-modal.component.html`
- Create: `console-webapp/src/app/registry-dash/ai/ai-analysis-modal.component.scss`

- [ ] **Step 1: Create the component TypeScript**

```typescript
import { Component, Inject, computed, signal, OnInit, DestroyRef, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MaterialModule } from '../../material.module';
import { AiAnalysisService } from './ai-analysis.service';
import { AiAnalyzeRequest, AiModelChoice, ConversationMessage } from './ai-analysis.models';
import { RegistryDashService } from '../registry-dash.service';

export interface AiAnalysisModalData {
  title: string;
  page: AiAnalyzeRequest['page'];
  promptType: string;
  userMessage: string;
  metadata: AiAnalyzeRequest['metadata'];
  chartData: any;
  systemPrompt?: string;
  isAdmin: boolean;
  savedModel?: AiModelChoice;
}

@Component({
  selector: 'app-ai-analysis-modal',
  standalone: true,
  imports: [CommonModule, MaterialModule, FormsModule],
  templateUrl: './ai-analysis-modal.component.html',
  styleUrls: ['./ai-analysis-modal.component.scss'],
})
export class AiAnalysisModalComponent implements OnInit {
  private destroyRef = inject(DestroyRef);

  selectedModel = signal<AiModelChoice>('sonnet');
  conversationHistory = signal<ConversationMessage[]>([]);
  followUpInput = signal('');
  showAdvanced = signal(false);
  editableSystemPrompt = signal('');

  streaming = computed(() => this.aiService.streaming());
  streamedText = computed(() => this.aiService.streamedText());
  error = computed(() => this.aiService.error());

  constructor(
    public dialogRef: MatDialogRef<AiAnalysisModalComponent>,
    @Inject(MAT_DIALOG_DATA) public data: AiAnalysisModalData,
    private aiService: AiAnalysisService,
    private dashService: RegistryDashService,
  ) {
    if (data.savedModel) {
      this.selectedModel.set(data.savedModel);
    }
  }

  ngOnInit() {
    this.sendInitialRequest();
  }

  private async sendInitialRequest() {
    const history: ConversationMessage[] = [
      { role: 'user', content: this.data.userMessage },
    ];
    this.conversationHistory.set(history);

    await this.aiService.analyze({
      page: this.data.page,
      promptType: this.data.promptType,
      metadata: this.data.metadata,
      chartData: this.data.chartData,
      model: this.selectedModel(),
      systemPrompt: this.showAdvanced() ? this.editableSystemPrompt() : undefined,
      conversationHistory: history,
    });

    if (!this.error()) {
      this.conversationHistory.update(h => [
        ...h,
        { role: 'assistant', content: this.streamedText() },
      ]);
    }
  }

  async sendFollowUp() {
    const input = this.followUpInput().trim();
    if (!input || this.streaming()) return;

    const updatedHistory: ConversationMessage[] = [
      ...this.conversationHistory(),
      { role: 'user', content: input },
    ];
    this.conversationHistory.set(updatedHistory);
    this.followUpInput.set('');

    await this.aiService.analyze({
      page: this.data.page,
      promptType: this.data.promptType,
      metadata: this.data.metadata,
      chartData: this.data.chartData,
      model: this.selectedModel(),
      systemPrompt: this.showAdvanced() ? this.editableSystemPrompt() : undefined,
      conversationHistory: updatedHistory,
    });

    if (!this.error()) {
      this.conversationHistory.update(h => [
        ...h,
        { role: 'assistant', content: this.streamedText() },
      ]);
    }
  }

  onModelChange(model: AiModelChoice) {
    this.selectedModel.set(model);
    this.dashService.updateSettingsSelf({ aiModel: model }).subscribe();
  }

  onFollowUpKeydown(event: KeyboardEvent) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.sendFollowUp();
    }
  }

  toggleAdvanced() {
    this.showAdvanced.update(v => !v);
    if (this.showAdvanced() && !this.editableSystemPrompt()) {
      this.editableSystemPrompt.set(this.data.systemPrompt ?? '');
    }
  }
}
```

- [ ] **Step 2: Create the modal template**

```html
<h2 mat-dialog-title>{{ data.title }}</h2>
<mat-dialog-content>
  <!-- Context line -->
  <div class="context-bar">
    <span class="context-model">Model:</span>
    <mat-button-toggle-group [value]="selectedModel()" (change)="onModelChange($event.value)">
      <mat-button-toggle value="haiku">Haiku</mat-button-toggle>
      <mat-button-toggle value="sonnet">Sonnet</mat-button-toggle>
      <mat-button-toggle value="opus">Opus</mat-button-toggle>
    </mat-button-toggle-group>
  </div>

  <!-- Advanced section (admin only, dev mode) -->
  <div *ngIf="data.isAdmin" class="advanced-section">
    <button mat-button (click)="toggleAdvanced()" class="advanced-toggle">
      <mat-icon>{{ showAdvanced() ? 'expand_less' : 'expand_more' }}</mat-icon>
      Advanced
    </button>
    <div *ngIf="showAdvanced()" class="advanced-content">
      <textarea
        [(ngModel)]="editableSystemPrompt"
        class="system-prompt-editor"
        placeholder="System prompt (editable in dev mode)"
        rows="6"></textarea>
    </div>
  </div>

  <!-- Conversation -->
  <div class="conversation">
    <ng-container *ngFor="let msg of conversationHistory(); let last = last">
      <div *ngIf="msg.role === 'user'" class="message user-message">
        <mat-icon class="message-icon">person</mat-icon>
        <div class="message-content">{{ msg.content }}</div>
      </div>
      <div *ngIf="msg.role === 'assistant'" class="message assistant-message">
        <mat-icon class="message-icon">auto_awesome</mat-icon>
        <div class="message-content markdown-body" [innerHTML]="msg.content"></div>
      </div>
    </ng-container>

    <!-- Streaming response -->
    <div *ngIf="streaming()" class="message assistant-message streaming">
      <mat-icon class="message-icon">auto_awesome</mat-icon>
      <div class="message-content markdown-body" [innerHTML]="streamedText()"></div>
      <mat-progress-bar mode="indeterminate" class="stream-progress"></mat-progress-bar>
    </div>

    <!-- Error -->
    <div *ngIf="error()" class="error-message">
      <mat-icon>error</mat-icon>
      {{ error() }}
    </div>
  </div>
</mat-dialog-content>

<mat-dialog-actions>
  <div class="follow-up-bar">
    <input
      type="text"
      [(ngModel)]="followUpInput"
      (keydown)="onFollowUpKeydown($event)"
      placeholder="Ask a follow-up question..."
      [disabled]="streaming()"
      class="follow-up-input" />
    <button mat-icon-button (click)="sendFollowUp()" [disabled]="streaming() || !followUpInput()">
      <mat-icon>send</mat-icon>
    </button>
  </div>
  <button mat-button mat-dialog-close>Close</button>
</mat-dialog-actions>
```

- [ ] **Step 3: Create the modal styles**

```scss
:host {
  display: block;
}

.context-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  font-size: 13px;

  .context-model {
    font-weight: 500;
    color: var(--ud-text-secondary);
  }
}

.advanced-section {
  margin-bottom: 12px;

  .advanced-toggle {
    font-size: 12px;
    color: var(--ud-text-secondary);
  }

  .advanced-content {
    margin-top: 8px;
  }

  .system-prompt-editor {
    width: 100%;
    font-family: monospace;
    font-size: 12px;
    padding: 8px;
    border: 1px solid var(--ud-border);
    border-radius: var(--ud-radius-sm);
    resize: vertical;
  }
}

.conversation {
  max-height: 60vh;
  overflow-y: auto;
  margin-bottom: 16px;
}

.message {
  display: flex;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--ud-border-subtle, #eee);

  &:last-child {
    border-bottom: none;
  }

  .message-icon {
    flex-shrink: 0;
    font-size: 20px;
    width: 20px;
    height: 20px;
    color: var(--ud-text-secondary);
  }

  .message-content {
    flex: 1;
    font-size: 14px;
    line-height: 1.6;
    word-break: break-word;
  }
}

.user-message .message-content {
  color: var(--ud-text-secondary);
  font-style: italic;
}

.stream-progress {
  margin-top: 8px;
}

.error-message {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px;
  color: var(--ud-error, #d32f2f);
  font-size: 13px;

  mat-icon {
    font-size: 18px;
    width: 18px;
    height: 18px;
  }
}

mat-dialog-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.follow-up-bar {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 4px;

  .follow-up-input {
    flex: 1;
    padding: 8px 12px;
    border: 1px solid var(--ud-border);
    border-radius: var(--ud-radius-sm);
    font-size: 14px;
    outline: none;

    &:focus {
      border-color: var(--ud-accent);
    }

    &:disabled {
      opacity: 0.5;
    }
  }
}
```

- [ ] **Step 4: Verify frontend build**

Run:
```bash
cd /Users/tjones/conductor/workspaces/nomulus/jerusalem-v2/console-webapp && npx ng build 2>&1 | tail -20
```

Expected: Build success (or warnings only)

- [ ] **Step 5: Commit**

```bash
git add console-webapp/src/app/registry-dash/ai/ai-analysis-modal.component.ts \
  console-webapp/src/app/registry-dash/ai/ai-analysis-modal.component.html \
  console-webapp/src/app/registry-dash/ai/ai-analysis-modal.component.scss
git commit -m "feat(registry-dash): add AI analysis modal with streaming and follow-ups"
```

---

## Task 9: Frontend — Sparkle Button Component

**Files:**
- Create: `console-webapp/src/app/registry-dash/ai/ai-sparkle-button.component.ts`
- Create: `console-webapp/src/app/registry-dash/ai/ai-sparkle-button.component.html`
- Create: `console-webapp/src/app/registry-dash/ai/ai-sparkle-button.component.scss`

- [ ] **Step 1: Create the component TypeScript**

```typescript
import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatDialog } from '@angular/material/dialog';
import { MaterialModule } from '../../material.module';
import { AiAnalysisModalComponent, AiAnalysisModalData } from './ai-analysis-modal.component';
import { AiPromptOption, AiAnalyzeRequest, AiModelChoice } from './ai-analysis.models';
import { RegistryDashService } from '../registry-dash.service';

@Component({
  selector: 'app-ai-sparkle-button',
  standalone: true,
  imports: [CommonModule, MaterialModule],
  templateUrl: './ai-sparkle-button.component.html',
  styleUrls: ['./ai-sparkle-button.component.scss'],
})
export class AiSparkleButtonComponent {
  @Input({ required: true }) page!: AiAnalyzeRequest['page'];
  @Input({ required: true }) prompts!: AiPromptOption[];
  @Input({ required: true }) chartData!: any;
  @Input() isAdmin = false;

  constructor(
    private dialog: MatDialog,
    private dashService: RegistryDashService,
  ) {}

  onPromptSelect(prompt: AiPromptOption) {
    const range = this.dashService.selectedRangeConfig();
    const tlds = this.dashService.selectedTlds();
    const regIds = this.dashService.selectedRegistrarIds();

    const savedModel = this.dashService.settingsCache()?.['aiModel'] as AiModelChoice | undefined;

    const data: AiAnalysisModalData = {
      title: `${prompt.label} — ${this.pageLabel()}`,
      page: this.page,
      promptType: prompt.promptType,
      userMessage: prompt.userMessage,
      metadata: {
        dateRange: { start: '', end: '' },
        granularity: range?.granularity,
        filteredTlds: tlds,
        filteredRegistrars: regIds,
      },
      chartData: this.chartData,
      isAdmin: this.isAdmin,
      savedModel,
    };

    this.dialog.open(AiAnalysisModalComponent, {
      width: '800px',
      maxHeight: '90vh',
      data,
    });
  }

  private pageLabel(): string {
    switch (this.page) {
      case 'domain-activity': return 'Domain Activity';
      case 'revenue-billing': return 'Revenue Billing';
      case 'forecasting': return 'Forecasting';
    }
  }
}
```

- [ ] **Step 2: Create the sparkle button template**

```html
<button mat-icon-button [matMenuTriggerFor]="aiMenu"
        matTooltip="Analyze with AI"
        class="sparkle-button">
  <mat-icon>auto_awesome</mat-icon>
</button>

<mat-menu #aiMenu="matMenu">
  <button mat-menu-item *ngFor="let prompt of prompts" (click)="onPromptSelect(prompt)">
    <mat-icon>{{ prompt.icon }}</mat-icon>
    <span>{{ prompt.label }}</span>
  </button>
</mat-menu>
```

- [ ] **Step 3: Create the sparkle button styles**

```scss
.sparkle-button {
  opacity: 0.6;
  transition: opacity 0.2s;

  &:hover {
    opacity: 1;
  }

  mat-icon {
    font-size: 18px;
    width: 18px;
    height: 18px;
  }
}
```

- [ ] **Step 4: Commit**

```bash
git add console-webapp/src/app/registry-dash/ai/ai-sparkle-button.component.ts \
  console-webapp/src/app/registry-dash/ai/ai-sparkle-button.component.html \
  console-webapp/src/app/registry-dash/ai/ai-sparkle-button.component.scss
git commit -m "feat(registry-dash): add reusable AI sparkle button with prompt menu"
```

---

## Task 10: Frontend — Integrate Sparkle Buttons into Pages

**Files:**
- Modify: `console-webapp/src/app/registry-dash/domain-activity/domain-activity.component.ts`
- Modify: `console-webapp/src/app/registry-dash/domain-activity/domain-activity.component.html`
- Modify: `console-webapp/src/app/registry-dash/financials/revenue-billing/revenue-billing.component.ts`
- Modify: `console-webapp/src/app/registry-dash/financials/revenue-billing/revenue-billing.component.html`
- Modify: `console-webapp/src/app/registry-dash/financials/forecasting/forecasting.component.ts`
- Modify: `console-webapp/src/app/registry-dash/financials/forecasting/forecasting.component.html`

- [ ] **Step 1: Update domain-activity.component.ts**

Add imports:
```typescript
import { AiSparkleButtonComponent } from '../ai/ai-sparkle-button.component';
import { DOMAIN_ACTIVITY_PROMPTS } from '../ai/ai-prompts';
```

Add to `imports` array in `@Component`:
```typescript
imports: [CommonModule, MaterialModule, NgxEchartsDirective, LongPressDirective, FilterPanelComponent, AiSparkleButtonComponent],
```

Add property:
```typescript
readonly aiPrompts = DOMAIN_ACTIVITY_PROMPTS;
```

- [ ] **Step 2: Update domain-activity.component.html**

Replace each chart's `<h3>` with a header row that includes the sparkle button. For the first chart:

```html
<div class="chart-header">
  <h3>Activity Breakdown by TLD</h3>
  <app-ai-sparkle-button
    page="domain-activity"
    [prompts]="aiPrompts"
    [chartData]="data()">
  </app-ai-sparkle-button>
</div>
```

For the second chart:

```html
<div class="chart-header">
  <h3>Current Domain Counts by TLD</h3>
  <app-ai-sparkle-button
    page="domain-activity"
    [prompts]="aiPrompts"
    [chartData]="data()">
  </app-ai-sparkle-button>
</div>
```

- [ ] **Step 3: Add `chart-header` CSS to domain-activity.component.scss**

```scss
.chart-header {
  display: flex;
  align-items: center;
  justify-content: space-between;

  h3 {
    margin: 0;
  }
}
```

- [ ] **Step 4: Update revenue-billing.component.ts**

Add imports:
```typescript
import { AiSparkleButtonComponent } from '../../ai/ai-sparkle-button.component';
import { REVENUE_BILLING_PROMPTS } from '../../ai/ai-prompts';
```

Add to `imports` array:
```typescript
AiSparkleButtonComponent
```

Add property:
```typescript
readonly aiPrompts = REVENUE_BILLING_PROMPTS;
```

- [ ] **Step 5: Update revenue-billing.component.html**

Replace each chart's `<h3>` with a header row:

```html
<div class="chart-header">
  <h3>Registry Revenue by TLD</h3>
  <app-ai-sparkle-button
    page="revenue-billing"
    [prompts]="aiPrompts"
    [chartData]="data()">
  </app-ai-sparkle-button>
</div>
```

```html
<div class="chart-header">
  <h3>Registry Revenue by Operation</h3>
  <app-ai-sparkle-button
    page="revenue-billing"
    [prompts]="aiPrompts"
    [chartData]="data()">
  </app-ai-sparkle-button>
</div>
```

- [ ] **Step 6: Add `chart-header` CSS to revenue-billing.component.scss**

Same pattern as domain-activity:
```scss
.chart-header {
  display: flex;
  align-items: center;
  justify-content: space-between;

  h3 {
    margin: 0;
  }
}
```

- [ ] **Step 7: Update forecasting.component.ts**

Add imports:
```typescript
import { AiSparkleButtonComponent } from '../../ai/ai-sparkle-button.component';
import { FORECASTING_PROMPTS } from '../../ai/ai-prompts';
```

Add to `imports` array:
```typescript
AiSparkleButtonComponent
```

Add property:
```typescript
readonly aiPrompts = FORECASTING_PROMPTS;
```

- [ ] **Step 8: Update forecasting.component.html**

Replace each chart's `<h3>` with a header row:

```html
<div class="chart-header">
  <h3>Net Growth Projection</h3>
  <app-ai-sparkle-button
    page="forecasting"
    [prompts]="aiPrompts"
    [chartData]="data()">
  </app-ai-sparkle-button>
</div>
```

```html
<div class="chart-header">
  <h3>Domain Expirations by TLD</h3>
  <app-ai-sparkle-button
    page="forecasting"
    [prompts]="aiPrompts"
    [chartData]="data()">
  </app-ai-sparkle-button>
</div>
```

- [ ] **Step 9: Add `chart-header` CSS to forecasting.component.scss**

Same pattern:
```scss
.chart-header {
  display: flex;
  align-items: center;
  justify-content: space-between;

  h3 {
    margin: 0;
  }
}
```

- [ ] **Step 10: Verify frontend build**

Run:
```bash
cd /Users/tjones/conductor/workspaces/nomulus/jerusalem-v2/console-webapp && npx ng build 2>&1 | tail -20
```

Expected: Build success

- [ ] **Step 11: Commit**

```bash
git add console-webapp/src/app/registry-dash/domain-activity/ \
  console-webapp/src/app/registry-dash/financials/revenue-billing/ \
  console-webapp/src/app/registry-dash/financials/forecasting/
git commit -m "feat(registry-dash): integrate AI sparkle buttons into all three dashboard pages"
```

---

## Task 11: Verification — Full Backend Test Suite

- [ ] **Step 1: Run all AI-related backend tests**

Run:
```bash
cd /Users/tjones/conductor/workspaces/nomulus/jerusalem-v2 && ./nom_build :core:test --tests "google.registry.ai.*" --tests "google.registry.ui.server.console.registrydash.RegistryDashAiActionTest" 2>&1 | tail -30
```

Expected: All tests pass

- [ ] **Step 2: Run the routing test**

Run:
```bash
cd /Users/tjones/conductor/workspaces/nomulus/jerusalem-v2 && ./nom_build :core:test --tests "google.registry.module.RoutingMapTest" 2>&1 | tail -10
```

Expected: PASS

- [ ] **Step 3: Run frontend tests**

Run:
```bash
cd /Users/tjones/conductor/workspaces/nomulus/jerusalem-v2/console-webapp && npm test 2>&1 | tail -30
```

Expected: All tests pass (existing tests should still work; new components don't have specs yet but shouldn't break anything)

---

## Task 12: Verification — Manual E2E Test Plan

This task is a checklist for manual testing on the local dev server.

- [ ] **Step 1: Set up Anthropic API key in local dev**

The local dev server won't have Secret Manager access. For local testing, you'll need to either:
- Set the key as an environment variable and modify the module to check env vars first
- Or create a `nomulus-config-local.yaml` override with a hardcoded test key (DO NOT commit)

- [ ] **Step 2: Start the local dev server**

Run:
```bash
cd /Users/tjones/conductor/workspaces/nomulus/jerusalem-v2/console-webapp && npm run start:dev
```

- [ ] **Step 3: Test Domain Activity page**

1. Navigate to Domain Activity
2. Verify sparkle button appears on each chart header
3. Click sparkle → verify 3 prompt options appear
4. Select "Summarize trends" → verify modal opens with model switcher
5. Verify streaming response renders incrementally
6. Type a follow-up → verify conversation continues
7. Switch model to Haiku → verify next request uses haiku

- [ ] **Step 4: Test Revenue Billing page**

Same flow as Domain Activity but on the Revenue Billing tab

- [ ] **Step 5: Test Forecasting page**

Same flow but verify the "Identify risks" option appears instead of "Find anomalies"

- [ ] **Step 6: Test error scenarios**

1. Disconnect network → verify "Analysis temporarily unavailable" message
2. Send many rapid requests → verify rate limit message appears
3. Close modal during streaming → verify no errors in console
