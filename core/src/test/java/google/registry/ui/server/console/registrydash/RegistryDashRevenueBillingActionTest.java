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
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistDomainWithDependentResources;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.reporting.HistoryEntry.Type;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.auth.AuthResult;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.tools.GsonUtils;
import google.registry.ui.server.console.ConsoleApiParams;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link RegistryDashRevenueBillingAction}. */
class RegistryDashRevenueBillingActionTest {

  private static final Gson GSON = GsonUtils.provideGson();
  // Fixed test time: 2024-06-15 10:00 UTC
  private static final DateTime TEST_TIME = DateTime.parse("2024-06-15T10:00:00.000Z");
  private final FakeClock clock = new FakeClock(TEST_TIME);
  private final Clock javaClock =
      Clock.fixed(Instant.ofEpochMilli(TEST_TIME.getMillis()), ZoneOffset.UTC);

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @BeforeEach
  void setUp() {
    createTld("tld");
    createTld("tld2");
    persistNewRegistrar("registrar1");
  }

  // --- Helper methods ---

  private User createFteUser(String email) {
    return persistResource(
        new User.Builder()
            .setEmailAddress(email)
            .setUserRoles(
                new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build())
            .build());
  }

  private User createNonRoUser(String email) {
    return persistResource(
        new User.Builder()
            .setEmailAddress(email)
            .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.NONE).build())
            .build());
  }

  private Domain createDomainWithBilling(
      String label, String tld, Reason reason, String cost, DateTime eventTime) {
    Domain domain =
        persistDomainWithDependentResources(
            label, tld, clock.nowUtc(), clock.nowUtc(), clock.nowUtc().plusYears(2));
    DomainHistory history =
        DatabaseHelper.getOnlyHistoryEntryOfType(domain, Type.DOMAIN_CREATE, DomainHistory.class);
    persistResource(
        new BillingEvent.Builder()
            .setReason(reason)
            .setTargetId(domain.getDomainName())
            .setRegistrarId(domain.getCurrentSponsorRegistrarId())
            .setCost(Money.parse(cost))
            .setPeriodYears(1)
            .setEventTime(eventTime)
            .setBillingTime(eventTime)
            .setDomainHistory(history)
            .build());
    return domain;
  }

  private RunResult runAction(
      User user,
      Optional<Integer> months,
      Optional<Integer> lookbackHours,
      Optional<String> granularity) {
    ConsoleApiParams params = ConsoleApiParamsUtils.createFake(AuthResult.createUser(user));
    when(params.request().getMethod()).thenReturn("GET");
    RegistryDashRevenueBillingAction action =
        new RegistryDashRevenueBillingAction(
            params, months, lookbackHours, granularity, javaClock);
    action.run();
    return new RunResult((FakeResponse) params.response());
  }

  @SuppressWarnings("unchecked")
  private record RunResult(FakeResponse response) {
    Map<String, Object> payload() {
      return GSON.fromJson((String) response.getPayload(), Map.class);
    }

    List<Map<String, Object>> periodRevenue() {
      return (List<Map<String, Object>>) payload().get("periodRevenue");
    }

    Map<String, Object> totals() {
      return (Map<String, Object>) payload().get("totals");
    }
  }

  // --- Permission tests ---

  @Test
  void testForbiddenForNonRoUser() {
    User user = createNonRoUser("regular@example.com");
    RunResult result = runAction(user, Optional.empty(), Optional.empty(), Optional.empty());
    assertThat(result.response().getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  // --- Backward compatibility: old months param still works ---

  @Test
  void testMonthsParam_backwardCompatible() {
    User user = createFteUser("admin@example.com");
    // Event 2 months before test time — within 12-month window
    createDomainWithBilling(
        "test1", "tld", Reason.CREATE, "USD 15.00", TEST_TIME.minusMonths(2));
    RunResult result =
        runAction(user, Optional.of(12), Optional.empty(), Optional.empty());
    assertThat(result.response().getStatus()).isEqualTo(SC_OK);
    assertThat(result.periodRevenue()).isNotEmpty();
  }

  // --- Granularity: month ---

  @Test
  void testMonthGranularity_groupsByMonth() {
    User user = createFteUser("admin@example.com");
    // Two events in May 2024, different days — should merge into one "2024-05" entry
    createDomainWithBilling(
        "d1", "tld", Reason.CREATE, "USD 10.00", DateTime.parse("2024-05-10T12:00:00Z"));
    createDomainWithBilling(
        "d2", "tld", Reason.CREATE, "USD 20.00", DateTime.parse("2024-05-20T12:00:00Z"));
    RunResult result =
        runAction(user, Optional.empty(), Optional.of(8760), Optional.of("month"));
    assertThat(result.response().getStatus()).isEqualTo(SC_OK);
    assertThat(result.periodRevenue()).hasSize(1);
    assertThat(result.periodRevenue().get(0).get("period")).isEqualTo("2024-05");
    assertThat(((Number) result.periodRevenue().get(0).get("amount")).doubleValue())
        .isEqualTo(30.0);
  }

  // --- Granularity: day ---

  @Test
  void testDayGranularity_groupsByDay() {
    User user = createFteUser("admin@example.com");
    // Two events on June 10, one on June 11
    createDomainWithBilling(
        "d3", "tld", Reason.CREATE, "USD 10.00", DateTime.parse("2024-06-10T08:00:00Z"));
    createDomainWithBilling(
        "d4", "tld", Reason.CREATE, "USD 5.00", DateTime.parse("2024-06-10T16:00:00Z"));
    createDomainWithBilling(
        "d5", "tld", Reason.CREATE, "USD 7.00", DateTime.parse("2024-06-11T10:00:00Z"));
    RunResult result =
        runAction(user, Optional.empty(), Optional.of(720), Optional.of("day"));
    assertThat(result.response().getStatus()).isEqualTo(SC_OK);
    assertThat(result.periodRevenue()).hasSize(2);
    assertThat(result.periodRevenue().get(0).get("period")).isEqualTo("2024-06-10");
    assertThat(((Number) result.periodRevenue().get(0).get("amount")).doubleValue())
        .isEqualTo(15.0);
    assertThat(result.periodRevenue().get(1).get("period")).isEqualTo("2024-06-11");
    assertThat(((Number) result.periodRevenue().get(1).get("amount")).doubleValue())
        .isEqualTo(7.0);
  }

  // --- Granularity: hour ---

  @Test
  void testHourGranularity_groupsByHour() {
    User user = createFteUser("admin@example.com");
    // Two events in 08:xx hour, one in 09:xx
    createDomainWithBilling(
        "d6", "tld", Reason.CREATE, "USD 10.00", DateTime.parse("2024-06-15T08:15:00Z"));
    createDomainWithBilling(
        "d7", "tld", Reason.CREATE, "USD 5.00", DateTime.parse("2024-06-15T08:45:00Z"));
    createDomainWithBilling(
        "d8", "tld", Reason.CREATE, "USD 3.00", DateTime.parse("2024-06-15T09:30:00Z"));
    RunResult result =
        runAction(user, Optional.empty(), Optional.of(24), Optional.of("hour"));
    assertThat(result.response().getStatus()).isEqualTo(SC_OK);
    assertThat(result.periodRevenue()).hasSize(2);
    assertThat(result.periodRevenue().get(0).get("period")).isEqualTo("2024-06-15T08:00:00Z");
    assertThat(((Number) result.periodRevenue().get(0).get("amount")).doubleValue())
        .isEqualTo(15.0);
    assertThat(result.periodRevenue().get(1).get("period")).isEqualTo("2024-06-15T09:00:00Z");
    assertThat(((Number) result.periodRevenue().get(1).get("amount")).doubleValue())
        .isEqualTo(3.0);
  }

  // --- Granularity: 15min ---

  @Test
  void test15minGranularity_groupsBy15MinBuckets() {
    User user = createFteUser("admin@example.com");
    // Four events across three 15-min buckets
    createDomainWithBilling(
        "d9", "tld", Reason.CREATE, "USD 10.00", DateTime.parse("2024-06-15T08:05:00Z"));
    createDomainWithBilling(
        "d10", "tld", Reason.CREATE, "USD 5.00", DateTime.parse("2024-06-15T08:12:00Z"));
    createDomainWithBilling(
        "d11", "tld", Reason.CREATE, "USD 3.00", DateTime.parse("2024-06-15T08:20:00Z"));
    createDomainWithBilling(
        "d12", "tld", Reason.CREATE, "USD 7.00", DateTime.parse("2024-06-15T08:35:00Z"));
    RunResult result =
        runAction(user, Optional.empty(), Optional.of(6), Optional.of("15min"));
    assertThat(result.response().getStatus()).isEqualTo(SC_OK);
    // 08:00-08:14 → 15.00, 08:15-08:29 → 3.00, 08:30-08:44 → 7.00
    assertThat(result.periodRevenue()).hasSize(3);
    assertThat(result.periodRevenue().get(0).get("period")).isEqualTo("2024-06-15T08:00:00Z");
    assertThat(((Number) result.periodRevenue().get(0).get("amount")).doubleValue())
        .isEqualTo(15.0);
    assertThat(result.periodRevenue().get(1).get("period")).isEqualTo("2024-06-15T08:15:00Z");
    assertThat(((Number) result.periodRevenue().get(1).get("amount")).doubleValue())
        .isEqualTo(3.0);
    assertThat(result.periodRevenue().get(2).get("period")).isEqualTo("2024-06-15T08:30:00Z");
    assertThat(((Number) result.periodRevenue().get(2).get("amount")).doubleValue())
        .isEqualTo(7.0);
  }

  // --- Response shape: totals and byOperation ---

  @Test
  void testResponseIncludesTotalsAndByOperation() {
    User user = createFteUser("admin@example.com");
    createDomainWithBilling(
        "c1", "tld", Reason.CREATE, "USD 15.00", TEST_TIME.minusDays(5));
    createDomainWithBilling(
        "r1", "tld", Reason.RENEW, "USD 10.00", TEST_TIME.minusDays(3));
    RunResult result =
        runAction(user, Optional.empty(), Optional.of(720), Optional.of("day"));
    assertThat(((Number) result.totals().get("totalRevenue")).doubleValue()).isEqualTo(25.0);
    @SuppressWarnings("unchecked")
    Map<String, Number> byOp = (Map<String, Number>) result.totals().get("byOperation");
    assertThat(byOp.get("CREATE").doubleValue()).isEqualTo(15.0);
    assertThat(byOp.get("RENEW").doubleValue()).isEqualTo(10.0);
  }

  // --- Granularity validation ---

  @Test
  void testInvalidGranularity_defaultsToMonth() {
    User user = createFteUser("admin@example.com");
    RunResult result =
        runAction(user, Optional.empty(), Optional.of(8760), Optional.of("invalid"));
    assertThat(result.response().getStatus()).isEqualTo(SC_OK);
  }

  // --- Multi-TLD grouping ---

  @Test
  void testMultipleTlds_groupedSeparately() {
    User user = createFteUser("admin@example.com");
    createDomainWithBilling(
        "a1", "tld", Reason.CREATE, "USD 15.00", TEST_TIME.minusDays(1));
    createDomainWithBilling(
        "b1", "tld2", Reason.CREATE, "USD 20.00", TEST_TIME.minusDays(1));
    RunResult result =
        runAction(user, Optional.empty(), Optional.of(720), Optional.of("day"));
    // Two entries: one per TLD, same day
    assertThat(result.periodRevenue()).hasSize(2);
    assertThat(((Number) result.totals().get("totalRevenue")).doubleValue()).isEqualTo(35.0);
  }
}
