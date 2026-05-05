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

import com.google.gson.JsonObject;
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

/** Tests for {@link QueryRenewalRatesTool}. */
class QueryRenewalRatesToolTest {

  private final FakeClock clock = new FakeClock(DateTime.parse("2026-04-30T00:00:00.000Z"));
  private final Clock javaClock =
      Clock.fixed(Instant.parse("2026-04-30T00:00:00Z"), ZoneOffset.UTC);

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  private final QueryRenewalRatesTool tool = new QueryRenewalRatesTool(javaClock);

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

  private static JsonObject args(String tld, String startDate, String endDate) {
    JsonObject obj = new JsonObject();
    if (tld != null) {
      obj.addProperty("tld", tld);
    }
    if (startDate != null) {
      obj.addProperty("start_date", startDate);
    }
    if (endDate != null) {
      obj.addProperty("end_date", endDate);
    }
    return obj;
  }

  private static void assertStatus(ToolResult result, ToolResult.Status expected) {
    if (result.status() != expected) {
      throw new AssertionError(
          "Expected status "
              + expected
              + " but got "
              + result.status()
              + " (diagnostic="
              + result.diagnostic()
              + ")");
    }
  }

  @Test
  void execute_admin_emptyForRange() {
    User user = createFteUser("admin@example.com");

    ToolResult result = tool.executeWithStatus(args("tld", "2026-01-01", "2026-04-29"), user);

    // No history rows persisted in this test → EMPTY_FOR_RANGE.
    assertStatus(result, ToolResult.Status.EMPTY_FOR_RANGE);
    JsonObject obj = result.data().getAsJsonObject();
    assertThat(obj.has("rows")).isTrue();
  }

  @Test
  void execute_unmappedNonAdmin_returnsPermissionDenied() {
    User user = createNonAdminUser("stranger@example.com");

    ToolResult result =
        tool.executeWithStatus(args("tld", "2026-01-01", "2026-04-29"), user);

    assertStatus(result, ToolResult.Status.PERMISSION_DENIED);
    assertThat(result.diagnostic()).contains("Permission denied");
  }

  @Test
  void execute_missingArg_returnsInvalidArgs() {
    User user = createFteUser("admin@example.com");

    ToolResult result =
        tool.executeWithStatus(args(null, "2026-01-01", "2026-04-29"), user);

    assertStatus(result, ToolResult.Status.INVALID_ARGS);
    assertThat(result.diagnostic()).contains("Missing required arg: tld");
  }

  @Test
  void execute_futureDateRange_returnsOutOfRange() {
    User user = createFteUser("admin@example.com");

    ToolResult result = tool.executeWithStatus(args("tld", "2027-01-01", "2027-03-31"), user);

    assertStatus(result, ToolResult.Status.OUT_OF_RANGE);
  }
}
