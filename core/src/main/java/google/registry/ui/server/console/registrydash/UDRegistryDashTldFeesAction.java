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

import static google.registry.request.Action.Method.GET;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableSet;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
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
 * Returns default TLD fees derived directly from Nomulus TLD configuration.
 *
 * <p>This endpoint is independent of the RegistryDashboardCostBasis table — it reads TLD billing
 * costs from the Tld entity (getCreateBillingCost, getStandardRenewCost, getRestoreBillingCost).
 */
@Action(
    service = Service.CONSOLE,
    path = UDRegistryDashTldFeesAction.PATH,
    method = {GET},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class UDRegistryDashTldFeesAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-dash/tld-fees";

  @Inject
  public UDRegistryDashTldFeesAction(ConsoleApiParams consoleApiParams) {
    super(consoleApiParams);
  }

  @Override
  protected void getHandler(User user) {
    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.VIEW_PRICING)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    boolean isAdmin = user.getUserRoles().getGlobalRole() == GlobalRole.FTE;
    Set<String> tlds =
        isAdmin
            ? new TreeSet<>(Tlds.getTlds())
            : new TreeSet<>(RegistryDashAccessUtil.getMappedTlds(user.getEmailAddress()));

    if (tlds.isEmpty()) {
      consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(List.of()));
      consoleApiParams.response().setStatus(SC_OK);
      return;
    }

    List<Map<String, Object>> payload = new ArrayList<>();
    String[] operations = RegistryDashPriceUtil.getOperations();

    for (String tldStr : tlds) {
      Tld tld;
      try {
        tld = Tld.get(tldStr);
      } catch (Exception e) {
        continue;
      }
      String currency = tld.getCurrency().getCode();
      for (String op : operations) {
        BigDecimal price = RegistryDashPriceUtil.getDefaultPrice(tld, op);
        if (price != null) {
          Map<String, Object> row = new HashMap<>();
          row.put("tld", tldStr);
          row.put("operation", op);
          row.put("defaultPrice", price);
          row.put("currency", currency);
          payload.add(row);
        }
      }
    }

    consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(payload));
    consoleApiParams.response().setStatus(SC_OK);
  }
}
