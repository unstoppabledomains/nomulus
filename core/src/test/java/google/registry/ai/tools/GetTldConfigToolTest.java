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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.allowRegistrarAccess;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;

import com.google.gson.JsonObject;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.registrydash.RoRegistry;
import google.registry.model.registrydash.RoRegistryTld;
import google.registry.model.registrydash.RoRegistryUser;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.testing.FakeClock;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link GetTldConfigTool}. */
class GetTldConfigToolTest {

  private final FakeClock clock = new FakeClock(DateTime.parse("2026-04-30T00:00:00.000Z"));
  private final Clock javaClock =
      Clock.fixed(Instant.parse("2026-04-30T00:00:00Z"), ZoneOffset.UTC);

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  private final GetTldConfigTool tool = new GetTldConfigTool(javaClock);

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

  private void addMapping(String email, String tld) {
    RoRegistry registry = new RoRegistry("registry-for-" + email);
    tm().transact(() -> tm().getEntityManager().persist(registry));
    tm().transact(
        () -> {
          tm().getEntityManager().persist(new RoRegistryTld(registry.getId(), tld));
          tm().getEntityManager().persist(new RoRegistryUser(registry.getId(), email));
        });
  }

  private static JsonObject args(String tld) {
    JsonObject obj = new JsonObject();
    if (tld != null) {
      obj.addProperty("tld", tld);
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
              + ", data="
              + result.data()
              + ")");
    }
  }

  @Test
  void execute_admin_returnsConfig() {
    User user = createFteUser("admin@example.com");

    ToolResult result = tool.executeWithStatus(args("tld"), user);

    assertStatus(result, ToolResult.Status.OK);
    JsonObject obj = result.data().getAsJsonObject();
    assertThat(obj.get("tld").getAsString()).isEqualTo("tld");
    assertThat(obj.has("tld_state")).isTrue();
    assertThat(obj.has("currency")).isTrue();
    assertThat(obj.has("dns_writers")).isTrue();
    assertThat(obj.has("allowed_registrars")).isTrue();
    // createTld auto-grants TheRegistrar + NewRegistrar; setUp adds registrar1.
    assertThat(obj.get("allowed_registrars_count").getAsInt()).isEqualTo(3);
    assertThat(obj.get("allowed_registrars_truncated").getAsBoolean()).isFalse();
  }

  @Test
  void execute_mappedNonAdmin_returnsConfig() {
    User user = createNonAdminUser("ro@example.com");
    addMapping("ro@example.com", "tld");

    ToolResult result = tool.executeWithStatus(args("tld"), user);

    assertStatus(result, ToolResult.Status.OK);
    JsonObject obj = result.data().getAsJsonObject();
    assertThat(obj.get("tld").getAsString()).isEqualTo("tld");
  }

  @Test
  void execute_unmappedNonAdmin_returnsPermissionDenied() {
    User user = createNonAdminUser("stranger@example.com");

    ToolResult result = tool.executeWithStatus(args("tld"), user);

    assertStatus(result, ToolResult.Status.PERMISSION_DENIED);
    assertThat(result.diagnostic()).contains("Permission denied for tld");
  }

  @Test
  void execute_unknownTld_returnsInvalidArgs() {
    User user = createFteUser("admin@example.com");

    ToolResult result = tool.executeWithStatus(args("nonexistent"), user);

    assertStatus(result, ToolResult.Status.INVALID_ARGS);
    assertThat(result.diagnostic()).contains("TLD not found");
  }

  @Test
  void execute_missingArg_returnsInvalidArgs() {
    User user = createFteUser("admin@example.com");

    ToolResult result = tool.executeWithStatus(args(null), user);

    assertStatus(result, ToolResult.Status.INVALID_ARGS);
    assertThat(result.diagnostic()).contains("Missing required arg: tld");
  }

  @Test
  void execute_allowedRegistrarsCappedAt100() {
    User user = createFteUser("admin@example.com");
    // setUp + createTld defaults give 3 registrars on "tld" already; add 109 more = 112 total.
    for (int i = 2; i <= 110; i++) {
      String registrarId = "registrar" + i;
      persistNewRegistrar(registrarId);
      allowRegistrarAccess(registrarId, "tld");
    }

    ToolResult result = tool.executeWithStatus(args("tld"), user);

    assertStatus(result, ToolResult.Status.OK);
    JsonObject obj = result.data().getAsJsonObject();
    assertThat(obj.get("allowed_registrars").getAsJsonArray()).hasSize(100);
    assertThat(obj.get("allowed_registrars_count").getAsInt()).isEqualTo(112);
    assertThat(obj.get("allowed_registrars_truncated").getAsBoolean()).isTrue();
  }
}
