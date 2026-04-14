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

import google.registry.model.tld.Tld;
import java.math.BigDecimal;
import java.util.Locale;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

/**
 * Shared utility for computing default TLD prices from Nomulus TLD configuration.
 *
 * <p>Extracts the duplicated logic previously in both {@link RegistryDashCostBasisAction} and
 * {@link RegistryDashPricingAction}.
 */
public final class RegistryDashPriceUtil {

  private static final String[] OPERATIONS = {"CREATE", "RENEW", "TRANSFER", "RESTORE"};

  private RegistryDashPriceUtil() {}

  /** Returns the canonical list of billable operations. */
  public static String[] getOperations() {
    return OPERATIONS.clone();
  }

  /**
   * Returns the default price for a given TLD and operation from the Nomulus TLD configuration.
   *
   * <p>TRANSFER uses RENEW pricing — the gaining registrar is charged a one-year renewal as part of
   * the transfer (see DomainPricingLogic.getTransferPrice).
   */
  public static BigDecimal getDefaultPrice(Tld tld, String operation) {
    DateTime now = DateTime.now(DateTimeZone.UTC);
    return switch (operation.toUpperCase(Locale.US)) {
      case "CREATE" -> tld.getCreateBillingCost(now).getAmount();
      case "RENEW", "TRANSFER" -> tld.getStandardRenewCost(now).getAmount();
      case "RESTORE" -> tld.getRestoreBillingCost().getAmount();
      default -> null;
    };
  }
}
