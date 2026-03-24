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
import static google.registry.testing.DatabaseHelper.persistResource;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.registrydash.RegistryDashboardRegistrarPricing;
import google.registry.model.registrydash.RegistryDashboardRoRegistrarMapping;
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

/** Tests for {@link RegistryDashPricingAction}. */
class RegistryDashPricingActionTest {

  private static final Gson GSON = GsonUtils.provideGson();
  private final FakeClock clock = new FakeClock(DateTime.parse("2024-04-15T00:00:00.000Z"));

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @BeforeEach
  void setUp() {
    createTld("tld");
    persistNewRegistrar("registrar1");
    persistNewRegistrar("registrar2");
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

  private void addMapping(String email, String registrarId) {
    tm().transact(
        () ->
            tm().getEntityManager()
                .persist(new RegistryDashboardRoRegistrarMapping(email, registrarId)));
  }

  private void addPricingRule(String registrarId, String tld, String operation, String amount) {
    RegistryDashboardRegistrarPricing pricing = new RegistryDashboardRegistrarPricing();
    pricing.setRegistrarId(registrarId);
    pricing.setTld(tld);
    pricing.setOperation(operation);
    pricing.setPriceAmount(new BigDecimal(amount));
    pricing.setPriceCurrency("USD");
    pricing.setEffectiveDate(ZonedDateTime.now(ZoneOffset.UTC));
    pricing.setActive(true);
    tm().transact(() -> tm().getEntityManager().persist(pricing));
  }

  @Test
  void testGetPricing_forbiddenForNonRoUser() {
    User user =
        persistResource(
            new User.Builder()
                .setEmailAddress("regular@example.com")
                .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.NONE).build())
                .build());
    ConsoleApiParams params = ConsoleApiParamsUtils.createFake(AuthResult.createUser(user));
    when(params.request().getMethod()).thenReturn("GET");
    RegistryDashPricingAction action = new RegistryDashPricingAction(params, Optional.empty());
    action.run();
    FakeResponse response = (FakeResponse) params.response();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  @Test
  void testGetPricing_returnsScopedData() {
    User user = createRoUser("ro@example.com");
    addMapping("ro@example.com", "registrar1");
    addPricingRule("registrar1", "tld", "CREATE", "10.00");
    addPricingRule("registrar2", "tld", "CREATE", "15.00"); // should NOT appear

    ConsoleApiParams params = ConsoleApiParamsUtils.createFake(AuthResult.createUser(user));
    when(params.request().getMethod()).thenReturn("GET");
    RegistryDashPricingAction action = new RegistryDashPricingAction(params, Optional.empty());
    action.run();
    FakeResponse response = (FakeResponse) params.response();
    assertThat(response.getStatus()).isEqualTo(SC_OK);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results =
        GSON.fromJson(
            (String) response.getPayload(),
            new TypeToken<List<Map<String, Object>>>() {}.getType());
    assertThat(results).hasSize(1);
    assertThat(results.get(0).get("registrarId")).isEqualTo("registrar1");
  }

  @Test
  void testPostPricing_forbiddenForUnmappedRegistrar() {
    User user = createRoUser("ro@example.com");
    addMapping("ro@example.com", "registrar1");

    RegistryDashboardRegistrarPricing pricing = new RegistryDashboardRegistrarPricing();
    pricing.setRegistrarId("registrar2"); // not mapped
    pricing.setTld("tld");
    pricing.setOperation("CREATE");
    pricing.setPriceAmount(new BigDecimal("10.00"));
    pricing.setPriceCurrency("USD");

    ConsoleApiParams params = ConsoleApiParamsUtils.createFake(AuthResult.createUser(user));
    when(params.request().getMethod()).thenReturn("POST");
    RegistryDashPricingAction action =
        new RegistryDashPricingAction(params, Optional.of(pricing));
    action.run();
    FakeResponse response = (FakeResponse) params.response();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  @Test
  void testPostPricing_createsRule() {
    User user = createRoUser("ro@example.com");
    addMapping("ro@example.com", "registrar1");

    RegistryDashboardRegistrarPricing pricing = new RegistryDashboardRegistrarPricing();
    pricing.setRegistrarId("registrar1");
    pricing.setTld("tld");
    pricing.setOperation("CREATE");
    pricing.setPriceAmount(new BigDecimal("10.00"));
    pricing.setPriceCurrency("USD");

    ConsoleApiParams params = ConsoleApiParamsUtils.createFake(AuthResult.createUser(user));
    when(params.request().getMethod()).thenReturn("POST");
    RegistryDashPricingAction action =
        new RegistryDashPricingAction(params, Optional.of(pricing));
    action.run();
    FakeResponse response = (FakeResponse) params.response();
    assertThat(response.getStatus()).isEqualTo(SC_OK);

    // Verify persisted
    tm().transact(
        () -> {
          @SuppressWarnings("unchecked")
          List<RegistryDashboardRegistrarPricing> results =
              tm().getEntityManager()
                  .createQuery(
                      "SELECT p FROM RegistryDashboardRegistrarPricing p"
                          + " WHERE p.registrarId = :id",
                      RegistryDashboardRegistrarPricing.class)
                  .setParameter("id", "registrar1")
                  .getResultList();
          assertThat(results).hasSize(1);
          assertThat(results.get(0).getPriceAmount())
              .isEqualTo(new BigDecimal("10.00"));
        });
  }
}
