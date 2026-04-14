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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.flogger.FluentLogger;
import google.registry.flows.EppException;
import google.registry.flows.FlowMetadata;
import google.registry.flows.SessionMetadata;
import google.registry.flows.domain.FeesAndCredits;
import google.registry.model.domain.fee.BaseFee;
import google.registry.model.domain.fee.Fee;
import google.registry.model.eppinput.EppInput;
import google.registry.model.registrydash.RegistryDashboardRegistrarPricing;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Custom pricing logic that looks up per-registrar pricing from the
 * RegistryDashboardRegistrarPricing table. If an active pricing rule is found for the
 * (registrarId, tld, operation) tuple, the custom
 * price is used; otherwise the original base price is returned unchanged.
 */
public class RegistryDashboardPricingCustomLogic extends DomainPricingCustomLogic {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final String PRICING_QUERY =
      """
      SELECT p FROM RegistryDashboardRegistrarPricing p
      WHERE p.registrarId = :registrarId
        AND p.tld = :tld
        AND p.operation = :operation
        AND p.isActive = true
        AND p.effectiveDate <= :now
        AND (p.expiryDate IS NULL OR p.expiryDate > :now)
      ORDER BY p.effectiveDate DESC
      """;

  public RegistryDashboardPricingCustomLogic(
      @Nullable EppInput eppInput,
      @Nullable SessionMetadata sessionMetadata,
      @Nullable FlowMetadata flowMetadata) {
    super(eppInput, sessionMetadata, flowMetadata);
  }

  @Override
  public FeesAndCredits customizeCreatePrice(CreatePriceParameters priceParameters)
      throws EppException {
    return applyCustomPricing(
        priceParameters.feesAndCredits(),
        priceParameters.tld().getTldStr(),
        "CREATE");
  }

  @Override
  public FeesAndCredits customizeRenewPrice(RenewPriceParameters priceParameters) {
    return applyCustomPricing(
        priceParameters.feesAndCredits(),
        priceParameters.tld().getTldStr(),
        "RENEW");
  }

  @Override
  public FeesAndCredits customizeRestorePrice(RestorePriceParameters priceParameters)
      throws EppException {
    return applyCustomPricing(
        priceParameters.feesAndCredits(),
        priceParameters.tld().getTldStr(),
        "RESTORE");
  }

  @Override
  public FeesAndCredits customizeTransferPrice(TransferPriceParameters priceParameters)
      throws EppException {
    return applyCustomPricing(
        priceParameters.feesAndCredits(),
        priceParameters.tld().getTldStr(),
        "TRANSFER");
  }

  private FeesAndCredits applyCustomPricing(
      FeesAndCredits original, String tld, String operation) {
    Optional<String> registrarId =
        getSessionMetadata().map(SessionMetadata::getRegistrarId);
    if (registrarId.isEmpty()) {
      return original;
    }

    Optional<BigDecimal> customPrice = lookupPrice(registrarId.get(), tld, operation);
    if (customPrice.isEmpty()) {
      return original;
    }

    logger.atInfo().log(
        "Applying custom %s price %s for registrar %s on TLD %s",
        operation, customPrice.get(), registrarId.get(), tld);

    // Rebuild FeesAndCredits replacing fees of the matching type with the custom price
    BaseFee.FeeType targetType = feeTypeForOperation(operation);
    FeesAndCredits.Builder builder = new FeesAndCredits.Builder()
        .setCurrency(original.getCurrency())
        .setFeeExtensionRequired(original.isFeeExtensionRequired());

    for (Fee fee : original.getFees()) {
      if (fee.getType() == targetType) {
        builder.addFeeOrCredit(
            Fee.create(customPrice.get(), targetType, fee.isPremium()));
      } else {
        builder.addFeeOrCredit(fee);
      }
    }
    for (var credit : original.getCredits()) {
      builder.addFeeOrCredit(credit);
    }

    return builder.build();
  }

  private Optional<BigDecimal> lookupPrice(String registrarId, String tld, String operation) {
    try {
      return tm().transact(
          () -> {
            @SuppressWarnings("unchecked")
            List<RegistryDashboardRegistrarPricing> results =
                tm().getEntityManager()
                    .createQuery(PRICING_QUERY, RegistryDashboardRegistrarPricing.class)
                    .setParameter("registrarId", registrarId)
                    .setParameter("tld", tld)
                    .setParameter("operation", operation)
                    .setParameter("now", java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC))
                    .setMaxResults(1)
                    .getResultList();
            return results.isEmpty()
                ? Optional.empty()
                : Optional.of(results.get(0).getPriceAmount());
          });
    } catch (Exception e) {
      logger.atWarning().withCause(e).log(
          "Failed to look up custom pricing for registrar %s, tld %s, operation %s",
          registrarId, tld, operation);
      return Optional.empty();
    }
  }

  private static BaseFee.FeeType feeTypeForOperation(String operation) {
    return switch (operation) {
      case "CREATE" -> BaseFee.FeeType.CREATE;
      case "RENEW" -> BaseFee.FeeType.RENEW;
      case "RESTORE" -> BaseFee.FeeType.RESTORE;
      case "TRANSFER" -> BaseFee.FeeType.RENEW; // Transfer uses renew pricing
      default -> throw new IllegalArgumentException("Unknown operation: " + operation);
    };
  }
}
