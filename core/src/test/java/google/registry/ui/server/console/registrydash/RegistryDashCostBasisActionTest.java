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
import static google.registry.testing.DatabaseHelper.persistResource;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.registrydash.RegistryDashboardCostBasis;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.auth.AuthResult;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.tools.GsonUtils;
import google.registry.ui.server.console.ConsoleApiParams;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link RegistryDashCostBasisAction}. */
class RegistryDashCostBasisActionTest {

  private static final Gson GSON = GsonUtils.provideGson();
  private final FakeClock clock = new FakeClock(DateTime.parse("2024-04-15T00:00:00.000Z"));

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @BeforeEach
  void setUp() {
    createTld("tld");
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

  @Test
  void testGetCostBasis_forbiddenForNonRoUser() {
    User user =
        persistResource(
            new User.Builder()
                .setEmailAddress("regular@example.com")
                .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.NONE).build())
                .build());
    ConsoleApiParams params = ConsoleApiParamsUtils.createFake(AuthResult.createUser(user));
    when(params.request().getMethod()).thenReturn("GET");
    RegistryDashCostBasisAction action =
        new RegistryDashCostBasisAction(params, Optional.empty());
    action.run();
    FakeResponse response = (FakeResponse) params.response();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  @Test
  void testGetCostBasis_returnsAllEntries() {
    createRoUser("ro@example.com");

    RegistryDashboardCostBasis cb1 = new RegistryDashboardCostBasis();
    cb1.setTld("tld");
    cb1.setOperation("CREATE");
    cb1.setCostAmount(new BigDecimal("5.00"));
    cb1.setCostCurrency("USD");
    cb1.setEffectiveDate(ZonedDateTime.now(ZoneOffset.UTC));
    tm().transact(() -> tm().getEntityManager().persist(cb1));

    RegistryDashboardCostBasis cb2 = new RegistryDashboardCostBasis();
    cb2.setTld("tld");
    cb2.setOperation("RENEW");
    cb2.setCostAmount(new BigDecimal("4.00"));
    cb2.setCostCurrency("USD");
    cb2.setEffectiveDate(ZonedDateTime.now(ZoneOffset.UTC));
    tm().transact(() -> tm().getEntityManager().persist(cb2));

    User user = createRoUser("ro2@example.com");
    ConsoleApiParams params = ConsoleApiParamsUtils.createFake(AuthResult.createUser(user));
    when(params.request().getMethod()).thenReturn("GET");
    RegistryDashCostBasisAction action =
        new RegistryDashCostBasisAction(params, Optional.empty());
    action.run();
    FakeResponse response = (FakeResponse) params.response();
    assertThat(response.getStatus()).isEqualTo(SC_OK);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results =
        GSON.fromJson(
            (String) response.getPayload(),
            new TypeToken<List<Map<String, Object>>>() {}.getType());
    assertThat(results).hasSize(2);
  }

  @Test
  void testPostCostBasis_createsEntry() {
    User user = createRoUser("ro@example.com");

    RegistryDashboardCostBasis costBasis = new RegistryDashboardCostBasis();
    costBasis.setTld("tld");
    costBasis.setOperation("CREATE");
    costBasis.setCostAmount(new BigDecimal("5.00"));
    costBasis.setCostCurrency("USD");
    costBasis.setNotes("Test cost basis");

    ConsoleApiParams params = ConsoleApiParamsUtils.createFake(AuthResult.createUser(user));
    when(params.request().getMethod()).thenReturn("POST");
    RegistryDashCostBasisAction action =
        new RegistryDashCostBasisAction(params, Optional.of(costBasis));
    action.run();
    FakeResponse response = (FakeResponse) params.response();
    assertThat(response.getStatus()).isEqualTo(SC_OK);

    // Verify persisted
    tm().transact(
        () -> {
          @SuppressWarnings("unchecked")
          List<RegistryDashboardCostBasis> results =
              tm().getEntityManager()
                  .createQuery(
                      "SELECT c FROM RegistryDashboardCostBasis c WHERE c.tld = :tld",
                      RegistryDashboardCostBasis.class)
                  .setParameter("tld", "tld")
                  .getResultList();
          assertThat(results).hasSize(1);
          assertThat(results.get(0).getNotes()).isEqualTo("Test cost basis");
        });
  }
}
