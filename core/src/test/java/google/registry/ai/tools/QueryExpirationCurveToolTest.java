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

package google.registry.ai.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.allowRegistrarAccess;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import google.registry.ai.tools.AiTool.AiToolException;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.testing.FakeClock;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link QueryExpirationCurveTool}. */
class QueryExpirationCurveToolTest {

  private final FakeClock clock = new FakeClock(DateTime.parse("2026-04-30T00:00:00.000Z"));
  private final Clock javaClock =
      Clock.fixed(Instant.parse("2026-04-30T00:00:00Z"), ZoneOffset.UTC);

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  private final QueryExpirationCurveTool tool = new QueryExpirationCurveTool(javaClock);

  @BeforeEach
  void setUp() {
    createTld("tld");
    persistNewRegistrar("registrar1");
    allowRegistrarAccess("registrar1", "tld");
  }

  private User createFteUser(String email) {
    return persistResource(
        new User.Builder()
            .setEmailAddress(email)
            .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build())
            .build());
  }

  private User createNonAdminUser(String email) {
    return persistResource(
        new User.Builder()
            .setEmailAddress(email)
            .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.NONE).build())
            .build());
  }

  private static JsonObject args(String tld, Integer monthsAhead) {
    JsonObject obj = new JsonObject();
    if (tld != null) {
      obj.addProperty("tld", tld);
    }
    if (monthsAhead != null) {
      obj.addProperty("months_ahead", monthsAhead);
    }
    return obj;
  }

  @Test
  void execute_admin_returnsRows() throws Exception {
    User user = createFteUser("admin@example.com");

    JsonElement result = tool.execute(args("tld", 12), user);

    JsonObject obj = result.getAsJsonObject();
    assertThat(obj.has("rows")).isTrue();
    assertThat(obj.has("rowCount")).isTrue();
  }

  @Test
  void execute_unmappedNonAdmin_throwsPermissionDenied() {
    User user = createNonAdminUser("stranger@example.com");

    AiToolException ex =
        assertThrows(AiToolException.class, () -> tool.execute(args("tld", 12), user));

    assertThat(ex).hasMessageThat().contains("Permission denied");
  }

  @Test
  void execute_monthsAheadClamped_runsWithoutError() throws Exception {
    User user = createFteUser("admin@example.com");

    // Below min: 0 → clamped to 1.
    tool.execute(args("tld", 0), user);
    // Above max: 120 → clamped to 60.
    tool.execute(args("tld", 120), user);
  }

  @Test
  void execute_missingArg_throws() {
    User user = createFteUser("admin@example.com");

    AiToolException ex1 =
        assertThrows(AiToolException.class, () -> tool.execute(args(null, 12), user));
    assertThat(ex1).hasMessageThat().contains("Missing required arg: tld");

    AiToolException ex2 =
        assertThrows(AiToolException.class, () -> tool.execute(args("tld", null), user));
    assertThat(ex2).hasMessageThat().contains("Missing required arg: months_ahead");
  }
}
