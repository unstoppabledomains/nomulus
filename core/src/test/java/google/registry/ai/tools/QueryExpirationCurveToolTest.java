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
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
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

    ToolResult result = tool.executeWithStatus(args("tld", 12), user);

    assertStatus(result, ToolResult.Status.EMPTY_FOR_RANGE);
    JsonObject obj = result.data().getAsJsonObject();
    assertThat(obj.has("rows")).isTrue();
  }

  @Test
  void execute_unmappedNonAdmin_returnsPermissionDenied() {
    User user = createNonAdminUser("stranger@example.com");

    ToolResult result = tool.executeWithStatus(args("tld", 12), user);

    assertStatus(result, ToolResult.Status.PERMISSION_DENIED);
    assertThat(result.diagnostic()).contains("Permission denied");
  }

  @Test
  void execute_monthsAheadClamped_runsWithoutError() {
    User user = createFteUser("admin@example.com");

    // Below min: 0 → clamped to 1.
    ToolResult low = tool.executeWithStatus(args("tld", 0), user);
    assertThat(low.status()).isAnyOf(ToolResult.Status.OK, ToolResult.Status.EMPTY_FOR_RANGE);
    // Above max: 120 → clamped to 60.
    ToolResult high = tool.executeWithStatus(args("tld", 120), user);
    assertThat(high.status()).isAnyOf(ToolResult.Status.OK, ToolResult.Status.EMPTY_FOR_RANGE);
  }

  /**
   * SRE-1958 review fix: when the requested TLD has no expiring domains in range, the
   * EMPTY_FOR_RANGE diagnostic must be scoped to that TLD and NOT leak the registry-wide
   * min/max from other tenants' Domain rows.
   */
  @Test
  void execute_emptyForRange_diagnosticIsTldScoped() {
    User user = createFteUser("admin@example.com");
    // Create a second TLD with a Domain that has a recognisable expiration time. If the
    // probe is unscoped, the diagnostic will contain its expiration extent. If scoped to
    // "tld", the probe will return Optional.empty() and the diagnostic will be terse.
    createTld("other-tld");
    persistNewRegistrar("registrar2");
    allowRegistrarAccess("registrar2", "other-tld");
    persistActiveDomain(
        "leak.other-tld",
        DateTime.parse("2020-01-01T00:00:00Z"),
        DateTime.parse("2099-12-31T00:00:00Z"));

    ToolResult result = tool.executeWithStatus(args("tld", 12), user);

    assertStatus(result, ToolResult.Status.EMPTY_FOR_RANGE);
    // The unscoped probe would have surfaced the 2099 expiration from the other tenant.
    assertThat(result.diagnostic()).doesNotContain("2099");
  }

  @Test
  void execute_missingArg_returnsInvalidArgs() {
    User user = createFteUser("admin@example.com");

    ToolResult missingTld = tool.executeWithStatus(args(null, 12), user);
    assertStatus(missingTld, ToolResult.Status.INVALID_ARGS);
    assertThat(missingTld.diagnostic()).contains("Missing required arg: tld");

    ToolResult missingMonths = tool.executeWithStatus(args("tld", null), user);
    assertStatus(missingMonths, ToolResult.Status.INVALID_ARGS);
    assertThat(missingMonths.diagnostic()).contains("Missing required arg: months_ahead");
  }
}
