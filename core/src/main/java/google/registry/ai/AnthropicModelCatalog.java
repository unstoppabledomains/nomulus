// Copyright 2026 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.ai;

import com.google.common.base.Ascii;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Cache of GA Anthropic models, grouped by family (haiku/sonnet/opus).
 *
 * <p>Replaces the old hardcoded {@code MODEL_MAP} in {@link AnthropicClient}. Self-refreshing on
 * read: the first chat request after the TTL expires lazily re-fetches {@code GET /v1/models}.
 * Admins can also force a refresh via {@link #forceRefresh}, which invalidates the local cache so
 * the next read pulls fresh.
 *
 * <p>"GA" is detected heuristically: we drop ids containing {@code -beta}, {@code -preview},
 * {@code -experimental}, {@code -rc}, or {@code -alpha}, and only keep entries where {@code type
 * == "model"}. Anything that isn't a Claude model in one of the three families is discarded.
 */
@Singleton
public class AnthropicModelCatalog {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Gson GSON = new Gson();
  private static final int MAX_PER_FAMILY = 3;
  private static final Pattern FAMILY_PATTERN =
      Pattern.compile("claude-(opus|sonnet|haiku)-", Pattern.CASE_INSENSITIVE);
  private static final ImmutableList<String> NON_GA_MARKERS =
      ImmutableList.of("-beta", "-preview", "-experimental", "-rc", "-alpha");

  /** Hardcoded fallback used when Anthropic is unreachable on first fetch. */
  private static final ImmutableMap<String, ImmutableList<ModelInfo>> SEED =
      ImmutableMap.of(
          "opus",
              ImmutableList.of(new ModelInfo("claude-opus-4-6", "Claude Opus 4.6", null)),
          "sonnet",
              ImmutableList.of(
                  new ModelInfo(
                      "claude-sonnet-4-5-20250929", "Claude Sonnet 4.5", null)),
          "haiku",
              ImmutableList.of(
                  new ModelInfo("claude-haiku-4-5-20251001", "Claude Haiku 4.5", null)));

  private final OkHttpClient httpClient;
  private final String baseUrl;
  private final String apiKey;
  private final long cacheTtlMillis;

  /** Memoized supplier; rebuilt on each {@link #forceRefresh}. */
  private final AtomicReference<Supplier<CatalogSnapshot>> snapshotSupplier =
      new AtomicReference<>();

  @Inject
  public AnthropicModelCatalog(
      @Named("anthropicHttpClient") OkHttpClient httpClient,
      @Named("anthropicApiBaseUrl") String baseUrl,
      @Named("anthropicApiKey") String apiKey,
      @Named("modelCatalogTtlMinutes") int ttlMinutes) {
    // Reuse the connection pool from the shared client but apply tighter per-call
    // timeouts. The chat-streaming client has a 120s read timeout to accommodate
    // long completions; that's wildly too long for a metadata fetch on the modal-
    // open critical path. 10s connect/read keeps modal-open snappy on TTL expiry,
    // and the catalog falls back to its hardcoded seed on timeout.
    this.httpClient =
        httpClient
            .newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.apiKey = apiKey;
    this.cacheTtlMillis = Math.max(1, ttlMinutes) * 60_000L;
    rebuildSupplier();
  }

  /** Returns the current catalog grouped by family, top {@value #MAX_PER_FAMILY} per family. */
  public ImmutableMap<String, ImmutableList<ModelInfo>> currentCatalog() {
    return snapshotSupplier.get().get().catalog();
  }

  /** Timestamp of the last successful (or seeded) catalog snapshot. */
  public Instant lastFetchedAt() {
    return snapshotSupplier.get().get().fetchedAt();
  }

  /** Drops the cached catalog so the next read re-fetches from Anthropic. */
  public void forceRefresh() {
    rebuildSupplier();
  }

  /**
   * Resolves a shorthand family name ({@code haiku}/{@code sonnet}/{@code opus}) or a fully
   * qualified model id to the dated id Anthropic expects in the {@code model} field.
   *
   * <p>For shorthands, returns the newest GA in that family. For anything else, returns the input
   * verbatim if it looks like a Claude model id, otherwise empty.
   */
  public Optional<String> resolveModelId(String shorthandOrId) {
    if (shorthandOrId == null || shorthandOrId.isBlank()) {
      return Optional.empty();
    }
    ImmutableMap<String, ImmutableList<ModelInfo>> catalog = currentCatalog();
    String key = Ascii.toLowerCase(shorthandOrId);
    if (catalog.containsKey(key)) {
      ImmutableList<ModelInfo> family = catalog.get(key);
      return family.isEmpty() ? Optional.empty() : Optional.of(family.get(0).id());
    }
    // Already a fully qualified id — pass through if it looks Claude-shaped.
    if (FAMILY_PATTERN.matcher(shorthandOrId).find()) {
      return Optional.of(shorthandOrId);
    }
    return Optional.empty();
  }

  private void rebuildSupplier() {
    snapshotSupplier.set(
        Suppliers.memoizeWithExpiration(
            this::loadSnapshot, cacheTtlMillis, java.util.concurrent.TimeUnit.MILLISECONDS));
  }

  private CatalogSnapshot loadSnapshot() {
    try {
      ImmutableMap<String, ImmutableList<ModelInfo>> resolved = fetchAndResolve();
      if (resolved.values().stream().allMatch(List::isEmpty)) {
        logger.atWarning().log(
            "Anthropic /v1/models returned no usable Claude models; falling back to seed.");
        return new CatalogSnapshot(SEED, Instant.now());
      }
      return new CatalogSnapshot(resolved, Instant.now());
    } catch (IOException | RuntimeException e) {
      logger.atWarning().withCause(e).log(
          "Anthropic model catalog refresh failed; falling back to seed.");
      return new CatalogSnapshot(SEED, Instant.now());
    }
  }

  private ImmutableMap<String, ImmutableList<ModelInfo>> fetchAndResolve() throws IOException {
    Request req =
        new Request.Builder()
            .url(baseUrl + "/v1/models?limit=100")
            .get()
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .build();
    try (Response resp = httpClient.newCall(req).execute()) {
      if (!resp.isSuccessful()) {
        throw new IOException("Anthropic /v1/models error: " + resp.code());
      }
      String body = resp.body() != null ? resp.body().string() : "";
      JsonObject root = GSON.fromJson(body, JsonObject.class);
      if (root == null || !root.has("data") || !root.get("data").isJsonArray()) {
        throw new IOException("Anthropic /v1/models returned no data array");
      }
      return resolveFromData(root.getAsJsonArray("data"));
    }
  }

  /** Visible for tests. */
  static ImmutableMap<String, ImmutableList<ModelInfo>> resolveFromData(JsonArray data) {
    Map<String, List<ModelInfo>> byFamily = new HashMap<>();
    byFamily.put("opus", new ArrayList<>());
    byFamily.put("sonnet", new ArrayList<>());
    byFamily.put("haiku", new ArrayList<>());

    for (JsonElement el : data) {
      if (!el.isJsonObject()) {
        continue;
      }
      JsonObject obj = el.getAsJsonObject();
      String type = optString(obj, "type");
      if (type != null && !type.equals("model")) {
        continue;
      }
      String id = optString(obj, "id");
      if (id == null) {
        continue;
      }
      String lower = Ascii.toLowerCase(id);
      if (NON_GA_MARKERS.stream().anyMatch(lower::contains)) {
        logger.atInfo().log("Skipping non-GA Anthropic model id: %s", id);
        continue;
      }
      Matcher m = FAMILY_PATTERN.matcher(lower);
      if (!m.find()) {
        continue;
      }
      String family = Ascii.toLowerCase(m.group(1));
      String displayName = optString(obj, "display_name");
      String createdAt = optString(obj, "created_at");
      byFamily.get(family).add(new ModelInfo(id, displayName, createdAt));
    }

    Comparator<ModelInfo> newestFirst =
        Comparator.comparing(
                (ModelInfo mi) -> mi.createdAt() == null ? "" : mi.createdAt(),
                Comparator.reverseOrder())
            .thenComparing(ModelInfo::id, Comparator.reverseOrder());

    ImmutableMap.Builder<String, ImmutableList<ModelInfo>> out = ImmutableMap.builder();
    for (String family : ImmutableList.of("opus", "sonnet", "haiku")) {
      List<ModelInfo> list = byFamily.get(family);
      list.sort(newestFirst);
      out.put(
          family,
          ImmutableList.copyOf(list.subList(0, Math.min(MAX_PER_FAMILY, list.size()))));
    }
    return out.buildOrThrow();
  }

  private static String optString(JsonObject obj, String field) {
    if (!obj.has(field) || obj.get(field).isJsonNull()) {
      return null;
    }
    JsonElement el = obj.get(field);
    return el.isJsonPrimitive() ? el.getAsString() : null;
  }

  /** Public model record exposed via API to the UI. */
  public record ModelInfo(String id, String displayName, String createdAt) {}

  private record CatalogSnapshot(
      ImmutableMap<String, ImmutableList<ModelInfo>> catalog, Instant fetchedAt) {}
}
