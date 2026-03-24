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

package google.registry.model.registrydash;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.testing.FakeClock;
import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for registry dashboard JPA entities. */
class RegistryDashboardEntitiesTest {

  private final FakeClock clock = new FakeClock(DateTime.parse("2024-04-15T00:00:00.000Z"));

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @BeforeEach
  void setUp() {
    persistNewRegistrar("registrar1");
    tm().transact(
        () ->
            tm().put(
                new User.Builder()
                    .setEmailAddress("ro@example.com")
                    .setUserRoles(
                        new UserRoles.Builder()
                            .setGlobalRole(GlobalRole.REGISTRY_OPERATOR)
                            .build())
                    .build()));
  }

  @Test
  void testRoRegistrarMapping_persistAndLoad() {
    RegistryDashboardRoRegistrarMapping mapping =
        new RegistryDashboardRoRegistrarMapping("ro@example.com", "registrar1");
    tm().transact(() -> tm().getEntityManager().persist(mapping));

    tm().transact(
        () -> {
          RegistryDashboardRoRegistrarMapping loaded =
              tm().getEntityManager()
                  .find(RegistryDashboardRoRegistrarMapping.class, mapping.getId());
          assertThat(loaded).isNotNull();
          assertThat(loaded.getUserEmailAddress()).isEqualTo("ro@example.com");
          assertThat(loaded.getRegistrarId()).isEqualTo("registrar1");
          assertThat(loaded.getCreatedAt()).isNotNull();
        });
  }

  @Test
  void testRegistrarPricing_persistAndLoad() {
    RegistryDashboardRegistrarPricing pricing = new RegistryDashboardRegistrarPricing();
    pricing.setRegistrarId("registrar1");
    pricing.setTld("tld");
    pricing.setOperation("CREATE");
    pricing.setPriceAmount(new BigDecimal("10.00"));
    pricing.setPriceCurrency("USD");
    pricing.setEffectiveDate(ZonedDateTime.now(ZoneOffset.UTC));
    pricing.setActive(true);

    tm().transact(() -> tm().getEntityManager().persist(pricing));

    tm().transact(
        () -> {
          RegistryDashboardRegistrarPricing loaded =
              tm().getEntityManager()
                  .find(RegistryDashboardRegistrarPricing.class, pricing.getId());
          assertThat(loaded).isNotNull();
          assertThat(loaded.getRegistrarId()).isEqualTo("registrar1");
          assertThat(loaded.getTld()).isEqualTo("tld");
          assertThat(loaded.getOperation()).isEqualTo("CREATE");
          assertThat(loaded.getPriceAmount()).isEqualTo(new BigDecimal("10.00"));
          assertThat(loaded.getPriceCurrency()).isEqualTo("USD");
          assertThat(loaded.isActive()).isTrue();
          assertThat(loaded.getExpiryDate()).isNull();
        });
  }

  @Test
  void testRegistrarPricing_updatePrice() {
    RegistryDashboardRegistrarPricing pricing = new RegistryDashboardRegistrarPricing();
    pricing.setRegistrarId("registrar1");
    pricing.setTld("tld");
    pricing.setOperation("RENEW");
    pricing.setPriceAmount(new BigDecimal("8.00"));
    pricing.setPriceCurrency("USD");
    pricing.setEffectiveDate(ZonedDateTime.now(ZoneOffset.UTC));
    pricing.setActive(true);

    tm().transact(() -> tm().getEntityManager().persist(pricing));

    tm().transact(
        () -> {
          RegistryDashboardRegistrarPricing loaded =
              tm().getEntityManager()
                  .find(RegistryDashboardRegistrarPricing.class, pricing.getId());
          loaded.setPriceAmount(new BigDecimal("12.00"));
          loaded.setUpdatedAt(ZonedDateTime.now(ZoneOffset.UTC));
          tm().getEntityManager().merge(loaded);
        });

    tm().transact(
        () -> {
          RegistryDashboardRegistrarPricing reloaded =
              tm().getEntityManager()
                  .find(RegistryDashboardRegistrarPricing.class, pricing.getId());
          assertThat(reloaded.getPriceAmount()).isEqualTo(new BigDecimal("12.00"));
        });
  }

  @Test
  void testCostBasis_persistAndLoad() {
    RegistryDashboardCostBasis costBasis = new RegistryDashboardCostBasis();
    costBasis.setTld("tld");
    costBasis.setOperation("CREATE");
    costBasis.setCostAmount(new BigDecimal("5.00"));
    costBasis.setCostCurrency("USD");
    costBasis.setEffectiveDate(ZonedDateTime.now(ZoneOffset.UTC));
    costBasis.setNotes("Base cost for TLD");

    tm().transact(() -> tm().getEntityManager().persist(costBasis));

    tm().transact(
        () -> {
          RegistryDashboardCostBasis loaded =
              tm().getEntityManager()
                  .find(RegistryDashboardCostBasis.class, costBasis.getId());
          assertThat(loaded).isNotNull();
          assertThat(loaded.getTld()).isEqualTo("tld");
          assertThat(loaded.getOperation()).isEqualTo("CREATE");
          assertThat(loaded.getCostAmount()).isEqualTo(new BigDecimal("5.00"));
          assertThat(loaded.getCostCurrency()).isEqualTo("USD");
          assertThat(loaded.getNotes()).isEqualTo("Base cost for TLD");
        });
  }

  @Test
  void testCostBasis_nullNotes() {
    RegistryDashboardCostBasis costBasis = new RegistryDashboardCostBasis();
    costBasis.setTld("tld");
    costBasis.setOperation("RESTORE");
    costBasis.setCostAmount(new BigDecimal("40.00"));
    costBasis.setCostCurrency("USD");
    costBasis.setEffectiveDate(ZonedDateTime.now(ZoneOffset.UTC));

    tm().transact(() -> tm().getEntityManager().persist(costBasis));

    tm().transact(
        () -> {
          RegistryDashboardCostBasis loaded =
              tm().getEntityManager()
                  .find(RegistryDashboardCostBasis.class, costBasis.getId());
          assertThat(loaded.getNotes()).isNull();
        });
  }

  // --- Schema constraint tests (V221 migration) ---

  @Test
  void testMapping_duplicateEmailRegistrarPair_throwsException() {
    tm().transact(
        () -> tm().getEntityManager().persist(
            new RegistryDashboardRoRegistrarMapping(
                "dup@example.com", "registrar1")));

    assertThrows(
        PersistenceException.class,
        () ->
            tm().transact(
                () -> tm().getEntityManager().persist(
                    new RegistryDashboardRoRegistrarMapping(
                        "dup@example.com", "registrar1"))));
  }

  @Test
  void testMapping_sameUserDifferentRegistrars_succeeds() {
    persistNewRegistrar("registrar2");
    tm().transact(
        () -> tm().getEntityManager().persist(
            new RegistryDashboardRoRegistrarMapping(
                "multi@example.com", "registrar1")));
    tm().transact(
        () -> tm().getEntityManager().persist(
            new RegistryDashboardRoRegistrarMapping(
                "multi@example.com", "registrar2")));

    tm().transact(
        () -> {
          @SuppressWarnings("unchecked")
          List<RegistryDashboardRoRegistrarMapping> results =
              tm().getEntityManager()
                  .createQuery(
                      "SELECT m FROM"
                          + " RegistryDashboardRoRegistrarMapping m"
                          + " WHERE m.userEmailAddress"
                          + " = :email",
                      RegistryDashboardRoRegistrarMapping.class)
                  .setParameter("email", "multi@example.com")
                  .getResultList();
          assertThat(results).hasSize(2);
        });
  }

  @Test
  void testPricing_duplicateRegistrarTldOperationDate_throws() {
    ZonedDateTime date = ZonedDateTime.now(ZoneOffset.UTC);
    RegistryDashboardRegistrarPricing p1 =
        new RegistryDashboardRegistrarPricing();
    p1.setRegistrarId("registrar1");
    p1.setTld("tld");
    p1.setOperation("CREATE");
    p1.setPriceAmount(new BigDecimal("10.00"));
    p1.setPriceCurrency("USD");
    p1.setEffectiveDate(date);
    p1.setActive(true);
    tm().transact(() -> tm().getEntityManager().persist(p1));

    RegistryDashboardRegistrarPricing p2 =
        new RegistryDashboardRegistrarPricing();
    p2.setRegistrarId("registrar1");
    p2.setTld("tld");
    p2.setOperation("CREATE");
    p2.setPriceAmount(new BigDecimal("15.00"));
    p2.setPriceCurrency("USD");
    p2.setEffectiveDate(date); // same date → unique violation
    p2.setActive(true);

    assertThrows(
        PersistenceException.class,
        () -> tm().transact(
            () -> tm().getEntityManager().persist(p2)));
  }

  @Test
  void testPricing_sameRegistrarDifferentOperations_succeeds() {
    ZonedDateTime date = ZonedDateTime.now(ZoneOffset.UTC);
    RegistryDashboardRegistrarPricing create =
        new RegistryDashboardRegistrarPricing();
    create.setRegistrarId("registrar1");
    create.setTld("tld");
    create.setOperation("CREATE");
    create.setPriceAmount(new BigDecimal("10.00"));
    create.setPriceCurrency("USD");
    create.setEffectiveDate(date);
    create.setActive(true);

    RegistryDashboardRegistrarPricing renew =
        new RegistryDashboardRegistrarPricing();
    renew.setRegistrarId("registrar1");
    renew.setTld("tld");
    renew.setOperation("RENEW");
    renew.setPriceAmount(new BigDecimal("8.00"));
    renew.setPriceCurrency("USD");
    renew.setEffectiveDate(date);
    renew.setActive(true);

    tm().transact(
        () -> {
          tm().getEntityManager().persist(create);
          tm().getEntityManager().persist(renew);
        });

    tm().transact(
        () -> {
          @SuppressWarnings("unchecked")
          List<RegistryDashboardRegistrarPricing> results =
              tm().getEntityManager()
                  .createQuery(
                      "SELECT p FROM"
                          + " RegistryDashboardRegistrarPricing p"
                          + " WHERE p.registrarId = :id",
                      RegistryDashboardRegistrarPricing.class)
                  .setParameter("id", "registrar1")
                  .getResultList();
          assertThat(results).hasSize(2);
        });
  }

  @Test
  void testCostBasis_duplicateTldOperationDate_throws() {
    ZonedDateTime date = ZonedDateTime.now(ZoneOffset.UTC);
    RegistryDashboardCostBasis c1 = new RegistryDashboardCostBasis();
    c1.setTld("tld");
    c1.setOperation("CREATE");
    c1.setCostAmount(new BigDecimal("5.00"));
    c1.setCostCurrency("USD");
    c1.setEffectiveDate(date);
    tm().transact(() -> tm().getEntityManager().persist(c1));

    RegistryDashboardCostBasis c2 = new RegistryDashboardCostBasis();
    c2.setTld("tld");
    c2.setOperation("CREATE");
    c2.setCostAmount(new BigDecimal("6.00"));
    c2.setCostCurrency("USD");
    c2.setEffectiveDate(date); // same → unique violation
    assertThrows(
        PersistenceException.class,
        () -> tm().transact(
            () -> tm().getEntityManager().persist(c2)));
  }

  @Test
  void testPricing_autoGeneratesId() {
    RegistryDashboardRegistrarPricing pricing =
        new RegistryDashboardRegistrarPricing();
    pricing.setRegistrarId("registrar1");
    pricing.setTld("tld");
    pricing.setOperation("CREATE");
    pricing.setPriceAmount(new BigDecimal("10.00"));
    pricing.setPriceCurrency("USD");
    pricing.setEffectiveDate(ZonedDateTime.now(ZoneOffset.UTC));
    pricing.setActive(true);

    assertThat(pricing.getId()).isNull();
    tm().transact(() -> tm().getEntityManager().persist(pricing));
    assertThat(pricing.getId()).isNotNull();
    assertThat(pricing.getId()).isGreaterThan(0L);
  }

  @Test
  void testPricing_timestampsSetOnCreate() {
    RegistryDashboardRegistrarPricing pricing =
        new RegistryDashboardRegistrarPricing();
    pricing.setRegistrarId("registrar1");
    pricing.setTld("tld");
    pricing.setOperation("TRANSFER");
    pricing.setPriceAmount(new BigDecimal("3.00"));
    pricing.setPriceCurrency("USD");
    pricing.setEffectiveDate(ZonedDateTime.now(ZoneOffset.UTC));
    pricing.setActive(true);

    tm().transact(() -> tm().getEntityManager().persist(pricing));

    tm().transact(
        () -> {
          RegistryDashboardRegistrarPricing loaded =
              tm().getEntityManager().find(
                  RegistryDashboardRegistrarPricing.class,
                  pricing.getId());
          assertThat(loaded.getCreatedAt()).isNotNull();
          assertThat(loaded.getUpdatedAt()).isNotNull();
        });
  }

  @Test
  void testMapping_autoGeneratesIdAndTimestamp() {
    RegistryDashboardRoRegistrarMapping mapping =
        new RegistryDashboardRoRegistrarMapping(
            "ts@example.com", "registrar1");
    assertThat(mapping.getId()).isNull();
    tm().transact(() -> tm().getEntityManager().persist(mapping));
    assertThat(mapping.getId()).isNotNull();
    assertThat(mapping.getCreatedAt()).isNotNull();
  }

  @Test
  void testCostBasis_autoGeneratesIdAndTimestamps() {
    RegistryDashboardCostBasis cost = new RegistryDashboardCostBasis();
    cost.setTld("tld");
    cost.setOperation("RENEW");
    cost.setCostAmount(new BigDecimal("4.00"));
    cost.setCostCurrency("USD");
    cost.setEffectiveDate(ZonedDateTime.now(ZoneOffset.UTC));

    assertThat(cost.getId()).isNull();
    tm().transact(() -> tm().getEntityManager().persist(cost));
    assertThat(cost.getId()).isNotNull();
    assertThat(cost.getCreatedAt()).isNotNull();
    assertThat(cost.getUpdatedAt()).isNotNull();
  }
}
