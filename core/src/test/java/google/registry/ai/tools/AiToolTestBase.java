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
import static google.registry.testing.DatabaseHelper.persistResource;

import com.google.common.base.Ascii;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import google.registry.ai.tools.AiTool.AiToolException;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.registrydash.RoRegistry;
import google.registry.model.registrydash.RoRegistryTld;
import google.registry.model.registrydash.RoRegistryUser;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Shared test setup for {@link AiTool} implementations: JPA extension, fake clock, user fixtures,
 * registry-mapping helpers, and a helper for asserting {@link AiToolException} messages.
 */
abstract class AiToolTestBase {

  protected static final DateTime TEST_TIME = DateTime.parse("2026-04-30T10:00:00.000Z");
  protected static final Gson GSON = new Gson();

  protected final FakeClock clock = new FakeClock(TEST_TIME);

  @RegisterExtension
  protected final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  /** Creates an FTE (admin) user. Bypasses TLD-scope checks. */
  protected User createFteUser(String email) {
    return persistResource(
        new User.Builder()
            .setEmailAddress(email)
            .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build())
            .build());
  }

  /** Creates a registry-operator (non-admin) user. Subject to TLD-scope checks. */
  protected User createRoUser(String email) {
    return persistResource(
        new User.Builder()
            .setEmailAddress(email)
            .setUserRoles(
                new UserRoles.Builder().setGlobalRole(GlobalRole.REGISTRY_OPERATOR).build())
            .build());
  }

  /** Creates a no-role user. Has no TLD access via registry mappings. */
  protected User createNoneRoleUser(String email) {
    return persistResource(
        new User.Builder()
            .setEmailAddress(email)
            .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.NONE).build())
            .build());
  }

  /** Maps {@code email} to {@code tld} via the RoRegistry/RoRegistryTld/RoRegistryUser triple. */
  protected void mapUserToTld(String email, String tld) {
    RoRegistry registry = new RoRegistry("registry-for-" + email);
    tm().transact(
        () -> {
          tm().getEntityManager().persist(registry);
          tm().getEntityManager().persist(new RoRegistryTld(registry.getId(), tld));
          tm().getEntityManager().persist(new RoRegistryUser(registry.getId(), email));
        });
  }

  /** Asserts that {@code action} throws {@link AiToolException} whose message contains {@code
   * expectedMessageFragment} (case-insensitive). */
  protected static void assertAiToolException(
      Executable action, String expectedMessageFragment) {
    try {
      action.run();
    } catch (AiToolException e) {
      assertThat(Ascii.toLowerCase(e.getMessage()))
          .contains(Ascii.toLowerCase(expectedMessageFragment));
      return;
    } catch (Exception e) {
      throw new AssertionError(
          "Expected AiToolException but got " + e.getClass().getSimpleName(), e);
    }
    throw new AssertionError("Expected AiToolException but no exception was thrown");
  }

  /** Builds a JsonObject from inline key-value pairs. Strings only; arrays are JSON-encoded. */
  protected static JsonObject args(String json) {
    return GSON.fromJson(json, JsonObject.class);
  }

  /** Functional interface for {@link #assertAiToolException}. */
  @FunctionalInterface
  protected interface Executable {
    void run() throws Exception;
  }
}
