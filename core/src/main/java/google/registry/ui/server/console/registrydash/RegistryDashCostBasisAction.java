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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static google.registry.request.Action.Method.PUT;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableSet;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.registrydash.RegistryDashboardCostBasis;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tlds;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.ui.server.console.ConsoleApiAction;
import google.registry.ui.server.console.ConsoleApiParams;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.joda.time.DateTime;

/** Handles fees-per-TLD CRUD for the registry dashboard. */
@Action(
    service = Service.CONSOLE,
    path = RegistryDashCostBasisAction.PATH,
    method = {GET, POST, PUT},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistryDashCostBasisAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-dash/cost-basis";

  private static final String ALL_COST_BASIS =
      """
      SELECT c FROM RegistryDashboardCostBasis c
      ORDER BY c.tld, c.operation, c.effectiveDate DESC
      """;

  private static final String SCOPED_COST_BASIS =
      """
      SELECT c FROM RegistryDashboardCostBasis c
      WHERE c.tld IN :tlds OR c.tld = '*'
      ORDER BY c.tld, c.operation, c.effectiveDate DESC
      """;

  private final Optional<RegistryDashboardCostBasis> costBasisPayload;

  @Inject
  public RegistryDashCostBasisAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("registryDashCostBasis") Optional<RegistryDashboardCostBasis> costBasisPayload) {
    super(consoleApiParams);
    this.costBasisPayload = costBasisPayload;
  }

  @Override
  protected void getHandler(User user) {
    boolean isAdmin = user.getUserRoles().getGlobalRole() == GlobalRole.FTE;
    if (!isAdmin
        && !user.getUserRoles().hasGlobalPermission(ConsolePermission.MANAGE_COST_BASIS)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    ImmutableSet<String> tlds =
        isAdmin ? ImmutableSet.of()
            : RegistryDashAccessUtil.getMappedTlds(user.getEmailAddress());
    if (!isAdmin && tlds.isEmpty()) {
      consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(List.of()));
      consoleApiParams.response().setStatus(SC_OK);
      return;
    }

    tm().transact(
        () -> {
          @SuppressWarnings("unchecked")
          List<RegistryDashboardCostBasis> results =
              isAdmin
                  ? tm().getEntityManager()
                      .createQuery(ALL_COST_BASIS, RegistryDashboardCostBasis.class)
                      .getResultList()
                  : tm().getEntityManager()
                      .createQuery(SCOPED_COST_BASIS, RegistryDashboardCostBasis.class)
                      .setParameter("tlds", tlds)
                      .getResultList();

          // Separate default entries from TLD-specific entries
          Map<String, RegistryDashboardCostBasis> defaultByOp = new HashMap<>();
          List<RegistryDashboardCostBasis> specificEntries = new ArrayList<>();
          // Track which TLD+operation combos have specific entries (use most recent only)
          java.util.Set<String> coveredKeys = new java.util.HashSet<>();

          for (RegistryDashboardCostBasis c : results) {
            if (c.isDefault()) {
              // Keep the most recent default per operation (results ordered by effectiveDate DESC)
              defaultByOp.putIfAbsent(c.getOperation(), c);
            } else {
              specificEntries.add(c);
              coveredKeys.add(c.getTld() + ":" + c.getOperation());
            }
          }

          // Build a TLD cache for registrar billed amount lookups — scoped to user's TLDs
          java.util.Set<String> tldStrings = isAdmin ? Tlds.getTlds() : tlds;
          Map<String, Tld> tldCache = new HashMap<>();
          for (String tldStr : tldStrings) {
            try {
              tldCache.put(tldStr, Tld.get(tldStr));
            } catch (Exception e) {
              // skip
            }
          }

          List<Map<String, Object>> payload = new ArrayList<>();

          // Add the raw default entries (shown with isDefault=true in the UI)
          for (RegistryDashboardCostBasis c : results) {
            if (c.isDefault()) {
              payload.add(costBasisToMap(c, null));
            }
          }

          // Add specific TLD entries
          for (RegistryDashboardCostBasis c : specificEntries) {
            payload.add(costBasisToMap(c, tldCache.get(c.getTld())));
          }

          // Synthesize virtual entries for TLDs that inherit the default
          for (String tldStr : tldCache.keySet()) {
            for (var defaultEntry : defaultByOp.entrySet()) {
              String key = tldStr + ":" + defaultEntry.getKey();
              if (!coveredKeys.contains(key)) {
                RegistryDashboardCostBasis def = defaultEntry.getValue();
                Map<String, Object> virtual = costBasisToMap(def, tldCache.get(tldStr));
                virtual.put("tld", tldStr);
                virtual.put("isDefault", true);
                virtual.put("inheritedFromDefault", true);
                payload.add(virtual);
              }
            }
          }
          consoleApiParams.response().setPayload(
              consoleApiParams.gson().toJson(payload));
          consoleApiParams.response().setStatus(SC_OK);
        });
  }

  @Override
  protected void postHandler(User user) {
    boolean isAdmin = user.getUserRoles().getGlobalRole() == GlobalRole.FTE;
    if (!isAdmin
        && !user.getUserRoles().hasGlobalPermission(ConsolePermission.MANAGE_COST_BASIS)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    RegistryDashboardCostBasis costBasis =
        costBasisPayload.orElseThrow(
            () -> new IllegalArgumentException("Cost basis data is required"));

    // Non-admin users can only create entries for their own TLDs (not defaults)
    if (!isAdmin) {
      if (costBasis.isDefault()) {
        consoleApiParams.response().setStatus(SC_FORBIDDEN);
        return;
      }
      ImmutableSet<String> tlds =
          RegistryDashAccessUtil.getMappedTlds(user.getEmailAddress());
      if (!tlds.contains(costBasis.getTld())) {
        consoleApiParams.response().setStatus(SC_FORBIDDEN);
        return;
      }
    }

    ZonedDateTime now = ZonedDateTime.now(java.time.ZoneOffset.UTC);
    costBasis.setEffectiveDate(
        costBasis.getEffectiveDate() != null ? costBasis.getEffectiveDate() : now);

    tm().transact(() -> tm().getEntityManager().persist(costBasis));

    Tld tld = null;
    try {
      tld = Tld.get(costBasis.getTld());
    } catch (Exception e) {
      // TLD not found — netAmountToRegistry will be null in response
    }
    consoleApiParams.response().setPayload(
        consoleApiParams.gson().toJson(costBasisToMap(costBasis, tld)));
    consoleApiParams.response().setStatus(SC_OK);
  }

  @Override
  protected void putHandler(User user) {
    boolean isAdmin = user.getUserRoles().getGlobalRole() == GlobalRole.FTE;
    if (!isAdmin
        && !user.getUserRoles().hasGlobalPermission(ConsolePermission.MANAGE_COST_BASIS)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    RegistryDashboardCostBasis costBasis =
        costBasisPayload.orElseThrow(
            () -> new IllegalArgumentException("Cost basis data is required"));
    if (costBasis.getId() == null) {
      setFailedResponse("Cost basis ID is required for update", SC_BAD_REQUEST);
      return;
    }

    tm().transact(
        () -> {
          RegistryDashboardCostBasis existing =
              tm().getEntityManager()
                  .find(RegistryDashboardCostBasis.class, costBasis.getId());
          if (existing == null) {
            setFailedResponse("Cost basis entry not found", SC_BAD_REQUEST);
            return;
          }
          if (!isAdmin) {
            if (existing.isDefault()) {
              consoleApiParams.response().setStatus(SC_FORBIDDEN);
              return;
            }
            ImmutableSet<String> tlds =
                RegistryDashAccessUtil.getMappedTlds(user.getEmailAddress());
            if (!tlds.contains(existing.getTld())) {
              consoleApiParams.response().setStatus(SC_FORBIDDEN);
              return;
            }
          }
          existing.setRspRetainedFeeAmount(costBasis.getRspRetainedFeeAmount());
          existing.setCostCurrency(costBasis.getCostCurrency());
          existing.setNotes(costBasis.getNotes());
          existing.setUpdatedAt(ZonedDateTime.now(java.time.ZoneOffset.UTC));
          tm().getEntityManager().merge(existing);

          Tld tld = null;
          try {
            tld = Tld.get(existing.getTld());
          } catch (Exception e) {
            // TLD not found
          }
          consoleApiParams.response().setPayload(
              consoleApiParams.gson().toJson(costBasisToMap(existing, tld)));
          consoleApiParams.response().setStatus(SC_OK);
        });
  }

  private static Map<String, Object> costBasisToMap(RegistryDashboardCostBasis c, Tld tld) {
    Map<String, Object> map = new HashMap<>();
    map.put("id", c.getId());
    map.put("tld", c.getTld());
    map.put("operation", c.getOperation());
    map.put("rspRetainedFeeAmount", c.getRspRetainedFeeAmount());
    map.put("costCurrency", c.getCostCurrency());
    map.put("effectiveDate",
        c.getEffectiveDate() != null ? c.getEffectiveDate().toString() : null);
    map.put("notes", c.getNotes());
    map.put("isDefault", c.isDefault());

    // Enrich with registrar billed amount (from TLD config) and calculated net to registry
    if (tld != null) {
      BigDecimal registrarBilledAmount = getRegistrarBilledAmount(tld, c.getOperation());
      map.put("registrarBilledAmount", registrarBilledAmount);
      if (registrarBilledAmount != null && c.getRspRetainedFeeAmount() != null) {
        map.put("netAmountToRegistry",
            registrarBilledAmount.subtract(c.getRspRetainedFeeAmount()));
      }
      map.put("currency", tld.getCurrency().getCode());
    }
    return map;
  }

  /**
   * Returns the default amount the registrar is billed for a given TLD and operation.
   * For TRANSFER, uses renew pricing — the gaining registrar is charged a one-year renewal
   * as part of the transfer (see DomainPricingLogic.getTransferPrice).
   */
  static BigDecimal getRegistrarBilledAmount(Tld tld, String operation) {
    DateTime now = DateTime.now(org.joda.time.DateTimeZone.UTC);
    return switch (operation.toUpperCase(Locale.US)) {
      case "CREATE" -> tld.getCreateBillingCost(now).getAmount();
      case "RENEW" -> tld.getStandardRenewCost(now).getAmount();
      case "TRANSFER" -> tld.getStandardRenewCost(now).getAmount();
      case "RESTORE" -> tld.getRestoreBillingCost().getAmount();
      default -> null;
    };
  }
}
