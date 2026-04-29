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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
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
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.auth.AuthResult;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.ui.server.console.ConsoleApiParams;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Tests for {@link RegistryDashAiPromptsAction}. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RegistryDashAiPromptsActionTest {

  private final FakeClock clock = new FakeClock(DateTime.parse("2026-01-01T00:00:00Z"));

  @RegisterExtension
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
    new RegistryDashAiPromptsAction(params, Optional.of("portfolio"), promptConfig).run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    JsonObject body = JsonParser.parseString(response.getPayload()).getAsJsonObject();
    assertThat(body.get("version").getAsString()).isEqualTo("v1");
    assertThat(body.getAsJsonArray("menu")).hasSize(1);
    assertThat(body.getAsJsonArray("menu").get(0).getAsJsonObject().get("label").getAsString())
        .isEqualTo("Summarize trends");
  }

  @Test
  void testFailure_unknownPage_returns400() {
    new RegistryDashAiPromptsAction(params, Optional.of("domains"), promptConfig).run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
  }

  @Test
  void testFailure_missingPage_returns400() {
    new RegistryDashAiPromptsAction(params, Optional.empty(), promptConfig).run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
  }

  @Test
  void testFailure_pageNotInMenu_returns404() {
    new RegistryDashAiPromptsAction(params, Optional.of("pricing"), promptConfig).run();
    assertThat(response.getStatus()).isEqualTo(SC_NOT_FOUND);
  }

  @Test
  void testFailure_noPermission_returns403() {
    User noPermUser =
        tm().transact(
                () -> {
                  User u =
                      new User.Builder()
                          .setEmailAddress("noperm@test.com")
                          .setUserRoles(
                              new UserRoles.Builder().setGlobalRole(GlobalRole.NONE).build())
                          .build();
                  tm().put(u);
                  return u;
                });
    ConsoleApiParams nopermParams =
        ConsoleApiParamsUtils.createFake(AuthResult.createUser(noPermUser));
    when(nopermParams.request().getMethod()).thenReturn("GET");
    new RegistryDashAiPromptsAction(nopermParams, Optional.of("portfolio"), promptConfig).run();
    assertThat(((FakeResponse) nopermParams.response()).getStatus()).isEqualTo(SC_FORBIDDEN);
  }
}
