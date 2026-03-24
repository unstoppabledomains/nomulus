// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.allowRegistrarAccess;
import static google.registry.testing.DatabaseHelper.persistResource;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.registrydash.RoRegistry;
import google.registry.model.registrydash.RoRegistryTld;
import google.registry.model.registrydash.RoRegistryUser;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.auth.AuthResult;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.tools.GsonUtils;
import google.registry.ui.server.console.ConsoleApiParams;
import java.util.Map;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link RegistryDashOverviewAction}. */
class RegistryDashOverviewActionTest {

  private static final Gson GSON = GsonUtils.provideGson();
  private final FakeClock clock = new FakeClock(DateTime.parse("2024-04-15T00:00:00.000Z"));

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @BeforeEach
  void setUp() {
    createTld("tld");
    persistNewRegistrar("registrar1");
  }

  private User createRoUser(String email) {
    return persistResource(
        new User.Builder()
            .setEmailAddress(email)
            .setUserRoles(
                new UserRoles.Builder()
                    .setGlobalRole(GlobalRole.REGISTRY_OPERATOR)
                    .build())
            .build());
  }

  private User createNonRoUser(String email) {
    return persistResource(
        new User.Builder()
            .setEmailAddress(email)
            .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.NONE).build())
            .build());
  }

  @Test
  void testGetOverview_forbiddenForNonRoUser() {
    User user = createNonRoUser("regular@example.com");
    ConsoleApiParams params = ConsoleApiParamsUtils.createFake(AuthResult.createUser(user));
    when(params.request().getMethod()).thenReturn("GET");
    RegistryDashOverviewAction action = new RegistryDashOverviewAction(params);
    action.run();
    FakeResponse response = (FakeResponse) params.response();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  @Test
  void testGetOverview_emptyWhenNoMappings() {
    User user = createRoUser("ro@example.com");
    ConsoleApiParams params = ConsoleApiParamsUtils.createFake(AuthResult.createUser(user));
    when(params.request().getMethod()).thenReturn("GET");
    RegistryDashOverviewAction action = new RegistryDashOverviewAction(params);
    action.run();
    FakeResponse response = (FakeResponse) params.response();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = GSON.fromJson((String) response.getPayload(), Map.class);
    assertThat(((Number) payload.get("totalDomains")).longValue()).isEqualTo(0L);
    assertThat(((Number) payload.get("activeRegistrars")).intValue()).isEqualTo(0);
  }

  @Test
  void testGetOverview_returnsDataForMappedRegistrar() {
    User user = createRoUser("ro@example.com");
    allowRegistrarAccess("registrar1", "tld");

    RoRegistry registry = new RoRegistry("testRegistry");
    tm().transact(() -> tm().getEntityManager().persist(registry));
    tm().transact(
        () -> {
          tm().getEntityManager().persist(new RoRegistryTld(registry.getId(), "tld"));
          tm().getEntityManager().persist(
              new RoRegistryUser(registry.getId(), "ro@example.com"));
        });

    ConsoleApiParams params = ConsoleApiParamsUtils.createFake(AuthResult.createUser(user));
    when(params.request().getMethod()).thenReturn("GET");
    RegistryDashOverviewAction action = new RegistryDashOverviewAction(params);
    action.run();
    FakeResponse response = (FakeResponse) params.response();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = GSON.fromJson((String) response.getPayload(), Map.class);
    assertThat(((Number) payload.get("activeRegistrars")).intValue()).isEqualTo(1);
  }
}
