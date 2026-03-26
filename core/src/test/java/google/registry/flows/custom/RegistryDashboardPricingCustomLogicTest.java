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

package google.registry.flows.custom;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;

import com.google.common.net.InternetDomainName;
import google.registry.flows.SessionMetadata;
import google.registry.flows.domain.FeesAndCredits;
import google.registry.model.domain.fee.BaseFee.FeeType;
import google.registry.model.domain.fee.Fee;
import google.registry.model.registrydash.RegistryDashboardRegistrarPricing;
import google.registry.model.tld.Tld;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.testing.FakeClock;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mockito;

/** Tests for {@link RegistryDashboardPricingCustomLogic}. */
class RegistryDashboardPricingCustomLogicTest {

  private static final CurrencyUnit USD = CurrencyUnit.USD;
  private final FakeClock clock = new FakeClock(DateTime.parse("2024-04-15T00:00:00.000Z"));

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @BeforeEach
  void setUp() {
    createTld("tld");
    persistNewRegistrar("customRegistrar");
  }

  private FeesAndCredits baseFeesAndCredits() {
    return new FeesAndCredits.Builder()
        .setCurrency(USD)
        .addFeeOrCredit(Fee.create(new BigDecimal("20.00"), FeeType.CREATE, false))
        .build();
  }

  private SessionMetadata mockSession(String registrarId) {
    SessionMetadata session = Mockito.mock(SessionMetadata.class);
    Mockito.when(session.getRegistrarId()).thenReturn(registrarId);
    return session;
  }

  private void addPricingRule(
      String registrarId, String tld, String operation, String amount) {
    RegistryDashboardRegistrarPricing pricing = new RegistryDashboardRegistrarPricing();
    pricing.setRegistrarId(registrarId);
    pricing.setTld(tld);
    pricing.setOperation(operation);
    pricing.setPriceAmount(new BigDecimal(amount));
    pricing.setPriceCurrency("USD");
    pricing.setEffectiveDate(ZonedDateTime.now(ZoneOffset.UTC).minusDays(1));
    pricing.setActive(true);
    tm().transact(() -> tm().getEntityManager().persist(pricing));
  }

  @Test
  void testCustomizeCreatePrice_withMatchingRule_returnsCustomPrice() throws Exception {
    addPricingRule("customRegistrar", "tld", "CREATE", "10.00");

    RegistryDashboardPricingCustomLogic logic =
        new RegistryDashboardPricingCustomLogic(null, mockSession("customRegistrar"), null);

    DomainPricingCustomLogic.CreatePriceParameters params =
        DomainPricingCustomLogic.CreatePriceParameters.newBuilder()
            .setFeesAndCredits(baseFeesAndCredits())
            .setTld(Tld.get("tld"))
            .setDomainName(InternetDomainName.from("test.tld"))
            .setAsOfDate(DateTime.parse("2024-04-15T00:00:00.000Z"))
            .setYears(1)
            .build();

    FeesAndCredits result = logic.customizeCreatePrice(params);
    assertThat(result.getCreateCost()).isEqualTo(Money.of(USD, new BigDecimal("10.00")));
  }

  @Test
  void testCustomizeCreatePrice_noMatchingRule_returnsOriginalPrice() throws Exception {
    RegistryDashboardPricingCustomLogic logic =
        new RegistryDashboardPricingCustomLogic(null, mockSession("customRegistrar"), null);

    DomainPricingCustomLogic.CreatePriceParameters params =
        DomainPricingCustomLogic.CreatePriceParameters.newBuilder()
            .setFeesAndCredits(baseFeesAndCredits())
            .setTld(Tld.get("tld"))
            .setDomainName(InternetDomainName.from("test.tld"))
            .setAsOfDate(DateTime.parse("2024-04-15T00:00:00.000Z"))
            .setYears(1)
            .build();

    FeesAndCredits result = logic.customizeCreatePrice(params);
    assertThat(result.getCreateCost()).isEqualTo(Money.of(USD, new BigDecimal("20.00")));
  }

  @Test
  void testCustomizeCreatePrice_noSessionMetadata_returnsOriginalPrice() throws Exception {
    addPricingRule("customRegistrar", "tld", "CREATE", "10.00");

    RegistryDashboardPricingCustomLogic logic =
        new RegistryDashboardPricingCustomLogic(null, null, null);

    DomainPricingCustomLogic.CreatePriceParameters params =
        DomainPricingCustomLogic.CreatePriceParameters.newBuilder()
            .setFeesAndCredits(baseFeesAndCredits())
            .setTld(Tld.get("tld"))
            .setDomainName(InternetDomainName.from("test.tld"))
            .setAsOfDate(DateTime.parse("2024-04-15T00:00:00.000Z"))
            .setYears(1)
            .build();

    FeesAndCredits result = logic.customizeCreatePrice(params);
    assertThat(result.getCreateCost()).isEqualTo(Money.of(USD, new BigDecimal("20.00")));
  }

  @Test
  void testCustomizeCreatePrice_expiredRule_returnsOriginalPrice() throws Exception {
    RegistryDashboardRegistrarPricing pricing = new RegistryDashboardRegistrarPricing();
    pricing.setRegistrarId("customRegistrar");
    pricing.setTld("tld");
    pricing.setOperation("CREATE");
    pricing.setPriceAmount(new BigDecimal("10.00"));
    pricing.setPriceCurrency("USD");
    pricing.setEffectiveDate(ZonedDateTime.now(ZoneOffset.UTC).minusDays(10));
    pricing.setExpiryDate(ZonedDateTime.now(ZoneOffset.UTC).minusDays(1));
    pricing.setActive(true);
    tm().transact(() -> tm().getEntityManager().persist(pricing));

    RegistryDashboardPricingCustomLogic logic =
        new RegistryDashboardPricingCustomLogic(null, mockSession("customRegistrar"), null);

    DomainPricingCustomLogic.CreatePriceParameters params =
        DomainPricingCustomLogic.CreatePriceParameters.newBuilder()
            .setFeesAndCredits(baseFeesAndCredits())
            .setTld(Tld.get("tld"))
            .setDomainName(InternetDomainName.from("test.tld"))
            .setAsOfDate(DateTime.parse("2024-04-15T00:00:00.000Z"))
            .setYears(1)
            .build();

    FeesAndCredits result = logic.customizeCreatePrice(params);
    assertThat(result.getCreateCost()).isEqualTo(Money.of(USD, new BigDecimal("20.00")));
  }

  @Test
  void testCustomizeCreatePrice_inactiveRule_returnsOriginalPrice() throws Exception {
    RegistryDashboardRegistrarPricing pricing = new RegistryDashboardRegistrarPricing();
    pricing.setRegistrarId("customRegistrar");
    pricing.setTld("tld");
    pricing.setOperation("CREATE");
    pricing.setPriceAmount(new BigDecimal("10.00"));
    pricing.setPriceCurrency("USD");
    pricing.setEffectiveDate(ZonedDateTime.now(ZoneOffset.UTC).minusDays(1));
    pricing.setActive(false);
    tm().transact(() -> tm().getEntityManager().persist(pricing));

    RegistryDashboardPricingCustomLogic logic =
        new RegistryDashboardPricingCustomLogic(null, mockSession("customRegistrar"), null);

    DomainPricingCustomLogic.CreatePriceParameters params =
        DomainPricingCustomLogic.CreatePriceParameters.newBuilder()
            .setFeesAndCredits(baseFeesAndCredits())
            .setTld(Tld.get("tld"))
            .setDomainName(InternetDomainName.from("test.tld"))
            .setAsOfDate(DateTime.parse("2024-04-15T00:00:00.000Z"))
            .setYears(1)
            .build();

    FeesAndCredits result = logic.customizeCreatePrice(params);
    assertThat(result.getCreateCost()).isEqualTo(Money.of(USD, new BigDecimal("20.00")));
  }
}
