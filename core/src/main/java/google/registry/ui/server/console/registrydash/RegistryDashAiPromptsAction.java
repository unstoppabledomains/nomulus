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
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

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
import java.util.Optional;

/** Returns the active AI sparkle menu for a dashboard page, plus the prompt-config version. */
@Action(
    service = Service.CONSOLE,
    path = RegistryDashAiPromptsAction.PATH,
    method = Action.Method.GET,
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistryDashAiPromptsAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-dash/ai/prompts";

  private static final Gson PLAIN_GSON = new Gson();

  private final Optional<String> page;
  private final RegistryConfigSettings.Prompts promptConfig;

  @Inject
  public RegistryDashAiPromptsAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("page") Optional<String> page,
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
    String pageName = page.orElse(null);
    if (pageName == null || !AiAnalyzeRequest.VALID_PAGES.contains(pageName)) {
      setFailedResponse("Invalid or missing page parameter", SC_BAD_REQUEST);
      return;
    }
    if (promptConfig.menus == null || !promptConfig.menus.containsKey(pageName)) {
      setFailedResponse("No prompt menu configured for page: " + pageName, SC_NOT_FOUND);
      return;
    }
    JsonObject body = new JsonObject();
    body.addProperty("version", promptConfig.version);
    body.add("menu", PLAIN_GSON.toJsonTree(promptConfig.menus.get(pageName)));
    consoleApiParams.response().setStatus(SC_OK);
    consoleApiParams.response().setPayload(PLAIN_GSON.toJson(body));
  }
}
