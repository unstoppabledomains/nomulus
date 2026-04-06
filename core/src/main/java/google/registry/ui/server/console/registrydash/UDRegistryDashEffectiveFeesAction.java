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
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableSet;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrydash.RegistryDashboardRegistrarPricing;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tlds;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.auth.Auth;
import google.registry.ui.server.console.ConsoleApiAction;
import google.registry.ui.server.console.ConsoleApiParams;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Returns effective fees per registrar per TLD per operation.
 *
 * <p>Merges TLD default prices with active custom pricing rules. For each registrar x TLD x
 * operation combo: if an active RegistryDashboardRegistrarPricing rule exists, its price is used;
 * otherwise the default TLD price is used.
 */
@Action(
    service = Service.CONSOLE,
    path = UDRegistryDashEffectiveFeesAction.PATH,
    method = {GET},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class UDRegistryDashEffectiveFeesAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-dash/effective-fees";

  private static final String ACTIVE_PRICING_FOR_TLDS_AND_REGISTRARS =
      """
      SELECT p FROM RegistryDashboardRegistrarPricing p
      WHERE p.isActive = true
        AND p.tld IN :tlds
        AND p.registrarId IN :registrarIds
      ORDER BY p.registrarId, p.tld, p.operation
      """;

  private static final String ALL_ACTIVE_PRICING =
      """
      SELECT p FROM RegistryDashboardRegistrarPricing p
      WHERE p.isActive = true
      ORDER BY p.registrarId, p.tld, p.operation
      """;

  @Inject
  public UDRegistryDashEffectiveFeesAction(ConsoleApiParams consoleApiParams) {
    super(consoleApiParams);
  }

  @Override
  protected void getHandler(User user) {
    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.VIEW_PRICING)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    boolean isAdmin = user.getUserRoles().getGlobalRole() == GlobalRole.FTE;
    ImmutableSet<String> scopedTlds =
        isAdmin
            ? ImmutableSet.copyOf(Tlds.getTlds())
            : RegistryDashAccessUtil.getMappedTlds(user.getEmailAddress());
    ImmutableSet<String> registrarIds =
        isAdmin
            ? ImmutableSet.of()
            : RegistryDashAccessUtil.getRegistrarIdsForTlds(scopedTlds);

    if (scopedTlds.isEmpty()) {
      consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(List.of()));
      consoleApiParams.response().setStatus(SC_OK);
      return;
    }

    tm().transact(
        () -> {
          // Load active custom pricing rules
          @SuppressWarnings("unchecked")
          List<RegistryDashboardRegistrarPricing> activeRules =
              isAdmin
                  ? tm().getEntityManager()
                      .createQuery(ALL_ACTIVE_PRICING, RegistryDashboardRegistrarPricing.class)
                      .getResultList()
                  : tm().getEntityManager()
                      .createQuery(
                          ACTIVE_PRICING_FOR_TLDS_AND_REGISTRARS,
                          RegistryDashboardRegistrarPricing.class)
                      .setParameter("tlds", scopedTlds)
                      .setParameter("registrarIds", registrarIds)
                      .getResultList();

          // Build lookup: "registrarId:tld:OPERATION" -> pricing rule
          Map<String, RegistryDashboardRegistrarPricing> ruleLookup = new HashMap<>();
          for (RegistryDashboardRegistrarPricing rule : activeRules) {
            String key = rule.getRegistrarId() + ":" + rule.getTld() + ":"
                + rule.getOperation().toUpperCase(java.util.Locale.US);
            ruleLookup.put(key, rule);
          }

          // Load registrars with their names and allowed TLDs
          List<Registrar> registrars =
              tm().getEntityManager()
                  .createQuery(
                      "SELECT r FROM Registrar r WHERE r.type = :type", Registrar.class)
                  .setParameter("type", Registrar.Type.REAL)
                  .getResultList();

          // Filter registrars to those with at least one scoped TLD
          Map<String, String> registrarNames = new HashMap<>();
          Map<String, Set<String>> registrarTlds = new HashMap<>();
          for (Registrar r : registrars) {
            Set<String> overlap = new TreeSet<>(r.getAllowedTlds());
            overlap.retainAll(scopedTlds);
            if (!overlap.isEmpty() && (isAdmin || registrarIds.contains(r.getRegistrarId()))) {
              registrarNames.put(r.getRegistrarId(), r.getRegistrarName());
              registrarTlds.put(r.getRegistrarId(), overlap);
            }
          }

          // Build TLD cache
          Map<String, Tld> tldCache = new HashMap<>();
          for (String tldStr : scopedTlds) {
            try {
              tldCache.put(tldStr, Tld.get(tldStr));
            } catch (Exception e) {
              // skip misconfigured TLDs
            }
          }

          // Build effective fees: registrar x TLD x operation
          List<Map<String, Object>> payload = new ArrayList<>();
          String[] operations = RegistryDashPriceUtil.getOperations();

          for (var entry : new TreeSet<>(registrarNames.keySet())) {
            String regId = entry;
            String regName = registrarNames.get(regId);
            Set<String> tlds = registrarTlds.get(regId);

            for (String tldStr : tlds) {
              Tld tld = tldCache.get(tldStr);
              if (tld == null) {
                continue;
              }

              String currency = tld.getCurrency().getCode();
              for (String op : operations) {
                String key = regId + ":" + tldStr + ":" + op;
                RegistryDashboardRegistrarPricing customRule = ruleLookup.get(key);

                Map<String, Object> row = new HashMap<>();
                row.put("registrarId", regId);
                row.put("registrarName", regName);
                row.put("tld", tldStr);
                row.put("operation", op);

                if (customRule != null) {
                  row.put("price", customRule.getPriceAmount());
                  row.put("currency", customRule.getPriceCurrency());
                  row.put("source", "Custom");
                } else {
                  BigDecimal defaultPrice = RegistryDashPriceUtil.getDefaultPrice(tld, op);
                  row.put("price", defaultPrice);
                  row.put("currency", currency);
                  row.put("source", "Default");
                }
                payload.add(row);
              }
            }
          }

          consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(payload));
          consoleApiParams.response().setStatus(SC_OK);
        });
  }
}
