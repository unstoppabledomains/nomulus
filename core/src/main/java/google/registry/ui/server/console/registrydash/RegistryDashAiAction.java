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

import com.google.common.flogger.FluentLogger;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
    method = {Action.Method.GET, Action.Method.POST},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistryDashAiAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-dash/ai/analyze";
  private static final int SC_TOO_MANY_REQUESTS = 429;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final Gson PLAIN_GSON = new Gson();

  private final Optional<JsonElement> payload;
  private final Optional<String> aiPage;
  private final Optional<String> aiPromptType;
  private final AnthropicClient anthropicClient;
  private final AiRateLimiter rateLimiter;
  private final Gson gson;

  @Inject
  public RegistryDashAiAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("aiAnalyzePayload") Optional<JsonElement> payload,
      @Parameter("aiPage") Optional<String> aiPage,
      @Parameter("aiPromptType") Optional<String> aiPromptType,
      AnthropicClient anthropicClient,
      AiRateLimiter rateLimiter) {
    super(consoleApiParams);
    this.payload = payload;
    this.aiPage = aiPage;
    this.aiPromptType = aiPromptType;
    this.anthropicClient = anthropicClient;
    this.rateLimiter = rateLimiter;
    this.gson = consoleApiParams.gson();
  }

  @Override
  protected void getHandler(User user) {
    boolean isProduction = RegistryEnvironment.get() == RegistryEnvironment.PRODUCTION;
    boolean isAdmin = user.getUserRoles().getGlobalRole() == GlobalRole.FTE;

    if (isProduction || !isAdmin) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    if (aiPage.isEmpty() || aiPromptType.isEmpty()) {
      setFailedResponse("page and promptType query parameters are required", SC_BAD_REQUEST);
      return;
    }

    String prompt = getDefaultSystemPrompt(aiPage.get(), aiPromptType.get(), null, null);
    JsonObject result = new JsonObject();
    result.addProperty("systemPrompt", prompt);
    consoleApiParams.response().setPayload(PLAIN_GSON.toJson(result));
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
      consoleApiParams.response().setHeader(
          "Retry-After", String.valueOf(rateLimiter.getRetryAfterSeconds(userEmail)));
      setFailedResponse("Rate limit exceeded", SC_TOO_MANY_REQUESTS);
      return;
    }

    String systemPrompt = buildSystemPrompt(request, user);
    String model = request.model;
    String resolvedModel = AnthropicClient.resolveModelId(model != null ? model : "sonnet");

    logger.atInfo().log(
        "AI analysis request: user=%s, page=%s, promptType=%s, model=%s, historySize=%d",
        userEmail, request.page, request.promptType, resolvedModel,
        request.conversationHistory != null ? request.conversationHistory.size() : 0);

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
            writer.write("data: " + PLAIN_GSON.toJson(new TextChunk(chunk)) + "\n\n");
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
      try {
        PrintWriter writer = consoleApiParams.response().getWriter();
        consoleApiParams.response().setHeader(
            "Content-Type", "text/event-stream");
        consoleApiParams.response().setStatus(502);
        String detail = e.getMessage() != null ? e.getMessage() : "";
        writer.write("data: " + PLAIN_GSON.toJson(
            new ErrorChunk(detail)) + "\n\n");
        writer.flush();
      } catch (IOException ignored) {
        consoleApiParams.response().setStatus(502);
      }
    }
  }

  private String buildSystemPrompt(AiAnalyzeRequest request, User user) {
    boolean isAdmin = user.getUserRoles().getGlobalRole() == GlobalRole.FTE;

    if (isAdmin
        && request.systemPrompt != null && !request.systemPrompt.isEmpty()) {
      return request.systemPrompt;
    }

    return getDefaultSystemPrompt(request.page, request.promptType, request.chartData,
        request.metadata);
  }

  private String getDefaultSystemPrompt(
      String page, String promptType, JsonElement chartData, JsonObject metadata) {
    StringBuilder sb = new StringBuilder();
    sb.append("You are an expert domain registry analyst. ");
    sb.append("You are analyzing data from the ").append(page)
        .append(" page of a domain registry dashboard.\n\n");

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
        sb.append("Focus on opportunities for growth, risk mitigation, and operational ");
        sb.append("improvements.\n");
        break;
      case "identify_risks":
        sb.append("Identify risks in this data. Look for expiration cliffs, declining ");
        sb.append("registrars, and patterns that could lead to revenue loss.\n");
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

    sb.append("\n## Data\n```json\n");
    sb.append(chartData != null
        ? gson.toJson(chartData)
        : "[chart data will be injected at request time]");
    sb.append("\n```\n");
    sb.append("\nProvide your analysis in clear markdown. Use specific numbers from the data. ");
    sb.append("Keep your response concise and actionable.");

    return sb.toString();
  }

  private record TextChunk(String text) {}

  private record ErrorChunk(String error) {}
}
