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
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link RegistryDashDomainActivityAction}. */
class RegistryDashDomainActivityActionTest {

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

  // --- Helpers ---

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

  /**
   * Creates a domain with a DomainHistory entry at the given eventTime. Additional DomainHistory
   * entries (renew, delete, etc.) can be added separately via {@link #addDomainHistory}.
   */
  private Domain createDomainAtTime(String label, String tld, DateTime eventTime) {
    clock.setTo(eventTime);
    return persistDomainWithDependentResources(
        label, tld, clock.nowUtc(), clock.nowUtc(), clock.nowUtc().plusYears(2));
  }

  /** Adds a DomainHistory entry of the given type at the given time for an existing domain. */
  private void addDomainHistory(Domain domain, Type historyType, DateTime eventTime) {
    clock.setTo(eventTime);
    persistResource(
        new DomainHistory.Builder()
            .setType(historyType)
            .setModificationTime(clock.nowUtc())
            .setDomain(domain)
            .setRegistrarId(domain.getCurrentSponsorRegistrarId())
            .build());
  }

  private RunResult runAction(
      User user,
      Optional<Integer> lookbackHours,
      Optional<String> granularity) {
    ConsoleApiParams params = ConsoleApiParamsUtils.createFake(AuthResult.createUser(user));
    when(params.request().getMethod()).thenReturn("GET");
    RegistryDashDomainActivityAction action =
        new RegistryDashDomainActivityAction(
            params, Optional.empty(), lookbackHours, granularity, javaClock);
    action.run();
    return new RunResult((FakeResponse) params.response());
  }

  @SuppressWarnings("unchecked")
  private record RunResult(FakeResponse response) {
    Map<String, Object> payload() {
      return GSON.fromJson((String) response.getPayload(), Map.class);
    }

    List<Map<String, Object>> activity() {
      return (List<Map<String, Object>>) payload().get("activity");
    }

    Map<String, Object> currentCounts() {
      return (Map<String, Object>) payload().get("currentCounts");
    }
  }

  // --- Permission tests ---

  @Test
  void testForbiddenForNonRoUser() {
    User user = createNonRoUser("regular@example.com");
    RunResult result = runAction(user, Optional.empty(), Optional.empty());
    assertThat(result.response().getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  // --- Granularity: month ---

  @Test
  void testMonthGranularity_groupsByMonth() {
    User user = createFteUser("admin@example.com");
    // Create domains in May 2024 — the DOMAIN_CREATE history is the activity event
    createDomainAtTime("d1", "tld", DateTime.parse("2024-05-10T12:00:00Z"));
    createDomainAtTime("d2", "tld", DateTime.parse("2024-05-20T12:00:00Z"));
    RunResult result =
        runAction(user, Optional.of(8760), Optional.of("month"));
    assertThat(result.response().getStatus()).isEqualTo(SC_OK);
    List<Map<String, Object>> creates =
        result.activity().stream()
            .filter(e -> "CREATES".equals(e.get("type")))
            .toList();
    assertThat(creates).hasSize(1);
    assertThat(creates.get(0).get("period")).isEqualTo("2024-05");
    assertThat(((Number) creates.get(0).get("count")).longValue()).isEqualTo(2);
  }

  // --- Granularity: day ---

  @Test
  void testDayGranularity_groupsByDay() {
    User user = createFteUser("admin@example.com");
    // Two creates on June 10, one on June 11
    createDomainAtTime("d3", "tld", DateTime.parse("2024-06-10T08:00:00Z"));
    createDomainAtTime("d4", "tld", DateTime.parse("2024-06-10T16:00:00Z"));
    createDomainAtTime("d5", "tld", DateTime.parse("2024-06-11T10:00:00Z"));
    RunResult result =
        runAction(user, Optional.of(720), Optional.of("day"));
    assertThat(result.response().getStatus()).isEqualTo(SC_OK);
    List<Map<String, Object>> creates =
        result.activity().stream()
            .filter(e -> "CREATES".equals(e.get("type")))
            .toList();
    assertThat(creates).hasSize(2);
    assertThat(creates.get(0).get("period")).isEqualTo("2024-06-10");
    assertThat(((Number) creates.get(0).get("count")).longValue()).isEqualTo(2);
    assertThat(creates.get(1).get("period")).isEqualTo("2024-06-11");
    assertThat(((Number) creates.get(1).get("count")).longValue()).isEqualTo(1);
  }

  // --- Multiple activity types ---

  @Test
  void testMultipleActivityTypes_groupedSeparately() {
    User user = createFteUser("admin@example.com");
    Domain domain = createDomainAtTime("d6", "tld", DateTime.parse("2024-06-01T10:00:00Z"));
    // Add a RENEW event in the same month
    addDomainHistory(domain, Type.DOMAIN_RENEW, DateTime.parse("2024-06-05T10:00:00Z"));
    RunResult result =
        runAction(user, Optional.of(8760), Optional.of("month"));
    assertThat(result.response().getStatus()).isEqualTo(SC_OK);
    // Should have two entries for June: CREATES and RENEWS
    List<Map<String, Object>> juneActivity =
        result.activity().stream()
            .filter(e -> "2024-06".equals(e.get("period")))
            .toList();
    assertThat(juneActivity).hasSize(2);
    assertThat(
        juneActivity.stream().map(e -> e.get("type")).toList())
        .containsExactly("CREATES", "RENEWS");
  }

  // --- Multi-TLD grouping ---

  @Test
  void testMultipleTlds_groupedSeparately() {
    User user = createFteUser("admin@example.com");
    createDomainAtTime("a1", "tld", DateTime.parse("2024-06-01T10:00:00Z"));
    createDomainAtTime("b1", "tld2", DateTime.parse("2024-06-01T12:00:00Z"));
    RunResult result =
        runAction(user, Optional.of(720), Optional.of("day"));
    assertThat(result.response().getStatus()).isEqualTo(SC_OK);
    // Two CREATES entries: one per TLD, same day
    List<Map<String, Object>> creates =
        result.activity().stream()
            .filter(e -> "CREATES".equals(e.get("type")))
            .toList();
    assertThat(creates).hasSize(2);
  }

  // --- Current counts ---

  @Test
  void testResponseIncludesCurrentCounts() {
    User user = createFteUser("admin@example.com");
    createDomainAtTime("c1", "tld", TEST_TIME.minusDays(5));
    createDomainAtTime("c2", "tld", TEST_TIME.minusDays(3));
    createDomainAtTime("c3", "tld2", TEST_TIME.minusDays(1));
    RunResult result =
        runAction(user, Optional.of(720), Optional.of("day"));
    assertThat(result.response().getStatus()).isEqualTo(SC_OK);
    Map<String, Object> counts = result.currentCounts();
    assertThat(counts).isNotEmpty();
  }
}
