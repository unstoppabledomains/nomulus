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
import google.registry.model.registrydash.RegistryDashboardRegistrarPricing;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.ui.server.console.ConsoleApiAction;
import google.registry.ui.server.console.ConsoleApiParams;
import jakarta.inject.Inject;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Handles per-registrar pricing CRUD for the registry dashboard. */
@Action(
    service = Service.CONSOLE,
    path = RegistryDashPricingAction.PATH,
    method = {GET, POST, PUT},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistryDashPricingAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-dash/pricing";

  private static final String PRICING_BY_REGISTRARS =
      """
      SELECT p FROM RegistryDashboardRegistrarPricing p
      WHERE p.registrarId IN :registrarIds
      ORDER BY p.registrarId, p.tld, p.operation, p.effectiveDate DESC
      """;

  private final Optional<RegistryDashboardRegistrarPricing> pricingPayload;

  @Inject
  public RegistryDashPricingAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("registryDashPricing")
          Optional<RegistryDashboardRegistrarPricing> pricingPayload) {
    super(consoleApiParams);
    this.pricingPayload = pricingPayload;
  }

  @Override
  protected void getHandler(User user) {
    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.VIEW_PRICING)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    boolean isAdmin = user.getUserRoles().getGlobalRole() == GlobalRole.FTE;
    ImmutableSet<String> registrarIds =
        isAdmin ? ImmutableSet.of()
            : RegistryDashAccessUtil.getMappedRegistrarIds(user.getEmailAddress());
    if (!isAdmin && registrarIds.isEmpty()) {
      consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(List.of()));
      consoleApiParams.response().setStatus(SC_OK);
      return;
    }

    tm().transact(
        () -> {
          @SuppressWarnings("unchecked")
          List<RegistryDashboardRegistrarPricing> results =
              isAdmin
                  ? tm().getEntityManager()
                      .createQuery(
                          "SELECT p FROM RegistryDashboardRegistrarPricing p"
                              + " ORDER BY p.registrarId, p.tld, p.operation,"
                              + " p.effectiveDate DESC",
                          RegistryDashboardRegistrarPricing.class)
                      .getResultList()
                  : tm().getEntityManager()
                      .createQuery(PRICING_BY_REGISTRARS,
                          RegistryDashboardRegistrarPricing.class)
                      .setParameter("registrarIds", registrarIds)
                      .getResultList();
          List<Map<String, Object>> payload = new java.util.ArrayList<>();
          for (RegistryDashboardRegistrarPricing p : results) {
            payload.add(pricingToMap(p));
          }
          consoleApiParams.response().setPayload(
              consoleApiParams.gson().toJson(payload));
          consoleApiParams.response().setStatus(SC_OK);
        });
  }

  @Override
  protected void postHandler(User user) {
    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.MANAGE_PRICING)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    RegistryDashboardRegistrarPricing pricing =
        pricingPayload.orElseThrow(
            () -> new IllegalArgumentException("Pricing data is required"));

    boolean isAdmin = user.getUserRoles().getGlobalRole() == GlobalRole.FTE;
    if (!isAdmin) {
      ImmutableSet<String> registrarIds =
          RegistryDashAccessUtil.getMappedRegistrarIds(user.getEmailAddress());
      if (!registrarIds.contains(pricing.getRegistrarId())) {
        consoleApiParams.response().setStatus(SC_FORBIDDEN);
        return;
      }
    }

    ZonedDateTime now = ZonedDateTime.now(java.time.ZoneOffset.UTC);
    pricing.setEffectiveDate(pricing.getEffectiveDate() != null ? pricing.getEffectiveDate() : now);
    pricing.setActive(true);

    tm().transact(() -> tm().getEntityManager().persist(pricing));
    consoleApiParams.response().setPayload(
        consoleApiParams.gson().toJson(pricingToMap(pricing)));
    consoleApiParams.response().setStatus(SC_OK);
  }

  @Override
  protected void putHandler(User user) {
    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.MANAGE_PRICING)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    RegistryDashboardRegistrarPricing pricing =
        pricingPayload.orElseThrow(
            () -> new IllegalArgumentException("Pricing data is required"));
    if (pricing.getId() == null) {
      setFailedResponse("Pricing ID is required for update", SC_BAD_REQUEST);
      return;
    }

    boolean isAdmin = user.getUserRoles().getGlobalRole() == GlobalRole.FTE;

    tm().transact(
        () -> {
          RegistryDashboardRegistrarPricing existing =
              tm().getEntityManager()
                  .find(RegistryDashboardRegistrarPricing.class, pricing.getId());
          if (existing == null) {
            setFailedResponse("Pricing rule not found", SC_BAD_REQUEST);
            return;
          }
          if (!isAdmin) {
            ImmutableSet<String> registrarIds =
                RegistryDashAccessUtil.getMappedRegistrarIds(user.getEmailAddress());
            if (!registrarIds.contains(existing.getRegistrarId())) {
              consoleApiParams.response().setStatus(SC_FORBIDDEN);
              return;
            }
          }
          existing.setPriceAmount(pricing.getPriceAmount());
          existing.setPriceCurrency(pricing.getPriceCurrency());
          existing.setExpiryDate(pricing.getExpiryDate());
          existing.setActive(pricing.isActive());
          existing.setUpdatedAt(ZonedDateTime.now(java.time.ZoneOffset.UTC));
          tm().getEntityManager().merge(existing);
          consoleApiParams.response().setPayload(
              consoleApiParams.gson().toJson(pricingToMap(existing)));
          consoleApiParams.response().setStatus(SC_OK);
        });
  }

  private static Map<String, Object> pricingToMap(
      RegistryDashboardRegistrarPricing p) {
    Map<String, Object> map = new HashMap<>();
    map.put("id", p.getId());
    map.put("registrarId", p.getRegistrarId());
    map.put("tld", p.getTld());
    map.put("operation", p.getOperation());
    map.put("priceAmount", p.getPriceAmount());
    map.put("priceCurrency", p.getPriceCurrency());
    map.put("effectiveDate", p.getEffectiveDate() != null ? p.getEffectiveDate().toString() : null);
    map.put("expiryDate", p.getExpiryDate() != null ? p.getExpiryDate().toString() : null);
    map.put("isActive", p.isActive());
    return map;
  }
}
