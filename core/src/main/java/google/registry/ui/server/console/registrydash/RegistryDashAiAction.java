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

package google.registry.ui.server.console.registrydash;

import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import google.registry.ai.AiAnalyzeRequest;
import google.registry.ai.AiOrchestrator;
import google.registry.ai.AiRateLimiter;
import google.registry.ai.AnthropicClient;
import google.registry.ai.AnthropicModelCatalog;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryConfigSettings;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.ui.server.console.ConsoleApiAction;
import google.registry.ui.server.console.ConsoleApiParams;
import google.registry.util.Clock;
import google.registry.util.RegistryEnvironment;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.joda.time.DateTimeZone;

@Action(
    service = Service.CONSOLE,
    path = RegistryDashAiAction.PATH,
    method = {Action.Method.GET, Action.Method.POST},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistryDashAiAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-dash/ai/analyze";
  private static final int SC_TOO_MANY_REQUESTS = 429;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final Gson PLAIN_GSON = new Gson();

  private final Optional<JsonElement> payload;
  private final AiOrchestrator orchestrator;
  private final AiRateLimiter rateLimiter;
  private final Gson gson;
  private final RegistryConfigSettings.Prompts promptConfig;
  private final AnthropicModelCatalog modelCatalog;
  private final Clock clock;

  @Inject
  public RegistryDashAiAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("aiAnalyzePayload") Optional<JsonElement> payload,
      AiOrchestrator orchestrator,
      AiRateLimiter rateLimiter,
      @Config("anthropicPromptConfig") RegistryConfigSettings.Prompts promptConfig,
      AnthropicModelCatalog modelCatalog,
      Clock clock) {
    super(consoleApiParams);
    this.payload = payload;
    this.orchestrator = orchestrator;
    this.rateLimiter = rateLimiter;
    this.gson = consoleApiParams.gson();
    this.promptConfig = promptConfig;
    this.modelCatalog = modelCatalog;
    this.clock = clock;
  }

  @Override
  protected void getHandler(User user) {
    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.VIEW_DASHBOARD_OVERVIEW)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }
    Map<String, Object> response = new HashMap<>();
    response.put("catalog", modelCatalog.currentCatalog());
    response.put("fetchedAt", modelCatalog.lastFetchedAt().toString());
    consoleApiParams.response().setPayload(gson.toJson(response));
    consoleApiParams.response().setStatus(200);
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

    AiAnalyzeRequest request = PLAIN_GSON.fromJson(payload.get(), AiAnalyzeRequest.class);
    if (!request.isValid()) {
      setFailedResponse("Invalid request: page and chartData are required", SC_BAD_REQUEST);
      return;
    }

    String userEmail = user.getEmailAddress();
    if (!rateLimiter.tryAcquire(userEmail)) {
      consoleApiParams.response().setStatus(SC_TOO_MANY_REQUESTS);
      consoleApiParams
          .response()
          .setHeader("Retry-After", String.valueOf(rateLimiter.getRetryAfterSeconds(userEmail)));
      setFailedResponse("Rate limit exceeded", SC_TOO_MANY_REQUESTS);
      return;
    }

    String systemPrompt = buildSystemPrompt(request, user);
    String model = request.model;

    try {
      // Set Content-Type (with charset) before getWriter(): the writer's encoding is fixed at the
      // moment getWriter() is called. Without this, Jetty defaults the writer to ISO-8859-1 and
      // any non-Latin-1 characters from Anthropic (em-dash, smart quotes, emoji) get substituted
      // with '?' on the way out. setContentType() is the canonical Jakarta Servlet API for both
      // header and writer encoding (vs. setHeader, which is container-dependent).
      consoleApiParams
          .response()
          .setContentType(MediaType.create("text", "event-stream").withCharset(UTF_8));
      consoleApiParams.response().setHeader("Cache-Control", "no-cache");
      consoleApiParams.response().setHeader("Connection", "keep-alive");
      consoleApiParams.response().setStatus(200);
      PrintWriter writer = consoleApiParams.response().getWriter();

      ImmutableList<String> toolsUsed =
          orchestrator.run(
              systemPrompt,
              request.conversationHistory,
              model,
              user,
              event -> {
                JsonObject frame = new JsonObject();
                if (event instanceof AiOrchestrator.TextEvent te) {
                  frame.addProperty("type", "text");
                  frame.addProperty("text", te.text());
                } else if (event instanceof AiOrchestrator.ToolUseEvent tu) {
                  frame.addProperty("type", "tool_use");
                  frame.addProperty("tool", tu.tool());
                  frame.add("args", tu.args());
                } else if (event instanceof AiOrchestrator.ToolResultEvent tr) {
                  frame.addProperty("type", "tool_result");
                  frame.addProperty("tool", tr.tool());
                  frame.addProperty("ok", tr.ok());
                } else if (event instanceof AiOrchestrator.DoneEvent) {
                  frame.addProperty("type", "done");
                }
                writer.write("data: " + PLAIN_GSON.toJson(frame) + "\n\n");
                writer.flush();
              });

      writer.write("data: [DONE]\n\n");
      writer.flush();

      logger.atInfo().log(
          "AI analysis request: user=%s, page=%s, promptType=%s, modelShorthand=%s,"
              + " promptVersion=%s, historySize=%d, toolsUsed=%s",
          userEmail,
          request.page,
          request.promptType,
          model,
          promptConfig.version,
          request.conversationHistory != null ? request.conversationHistory.size() : 0,
          toolsUsed);

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

    // Admin per-request override is for prompt experimentation in non-prod; the admin owns
    // the entire prompt body in that path (including date instructions if they want them).
    if (!isProduction
        && isAdmin
        && request.systemPrompt != null
        && !request.systemPrompt.isEmpty()) {
      return request.systemPrompt;
    }
    return todayHeader()
        + getDefaultSystemPrompt(
            request.page, request.promptType, request.chartData, request.metadata);
  }

  private String todayHeader() {
    String today = clock.nowUtc().toDateTime(DateTimeZone.UTC).toLocalDate().toString();
    return "Today is " + today + " (UTC).\n\n";
  }

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
      if (metadata.has("dateRange") && hasNonEmptyDateRange(metadata.get("dateRange"))) {
        sb.append("Date range: ").append(metadata.get("dateRange")).append("\n");
      }
      if (metadata.has("filteredTlds") && metadata.getAsJsonArray("filteredTlds").size() > 0) {
        sb.append("Filtered to TLDs: ").append(metadata.get("filteredTlds")).append("\n");
      }
    }

    sb.append("\n## Data\n```json\n").append(gson.toJson(chartData)).append("\n```\n\n");
    sb.append(promptConfig.responseGuidance);

    if (promptConfig.toolsHeader != null && !promptConfig.toolsHeader.isEmpty()) {
      sb.append("\n\n").append(promptConfig.toolsHeader);
    }

    return sb.toString();
  }

  private static boolean hasNonEmptyDateRange(JsonElement dateRange) {
    if (dateRange == null || !dateRange.isJsonObject()) {
      return false;
    }
    JsonObject obj = dateRange.getAsJsonObject();
    return isNonBlankString(obj.get("start")) && isNonBlankString(obj.get("end"));
  }

  private static boolean isNonBlankString(JsonElement el) {
    return el != null && el.isJsonPrimitive() && !el.getAsString().isBlank();
  }
}
