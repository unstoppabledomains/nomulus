// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ai.tools;

import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import google.registry.model.console.User;
import google.registry.model.registrar.Registrar;
import google.registry.model.tld.Tld;
import google.registry.ui.server.console.registrydash.RegistryDashAccessUtil;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Optional;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

/**
 * AI tool: returns a TLD's configuration (state, currency, premium/reserved list names, dns
 * writers, and the registrars allowed to operate on it).
 *
 * <p>Wraps the {@link Tld} JPA entity. Permission: TLD-scoped via {@link
 * ToolJpaHelper#assertTldAccess}. The "allowed registrars" listing reuses {@link
 * RegistryDashAccessUtil#getRegistrarIdsForTlds} for the reverse lookup.
 */
@Singleton
public class GetTldConfigTool implements AiTool {

  static final int MAX_REGISTRARS = 100;

  private final java.time.Clock clock;

  @Inject
  public GetTldConfigTool() {
    this(java.time.Clock.systemUTC());
  }

  /** Test-friendly constructor. */
  GetTldConfigTool(java.time.Clock clock) {
    this.clock = clock;
  }

  @Override
  public String name() {
    return "get_tld_config";
  }

  @Override
  public Complexity complexity() {
    return Complexity.EASY;
  }

  @Override
  public String description() {
    return "Returns a TLD's configuration: current state (PREDELEGATION/QUIET_PERIOD/"
        + "GENERAL_AVAILABILITY), currency, premium/reserved list names, DNS writers, and the"
        + " list of registrars allowed to operate on this TLD. Use when the user asks how a TLD"
        + " is configured or which registrars can sell it.";
  }

  @Override
  public JsonObject inputSchema() {
    JsonObject schema = new JsonObject();
    schema.addProperty("type", "object");
    JsonObject props = new JsonObject();

    JsonObject tld = new JsonObject();
    tld.addProperty("type", "string");
    tld.addProperty("description", "TLD to look up (e.g. 'example')");
    props.add("tld", tld);

    schema.add("properties", props);
    JsonArray required = new JsonArray();
    required.add("tld");
    schema.add("required", required);
    return schema;
  }

  @Override
  public JsonElement execute(JsonObject args, User user) throws AiToolException {
    if (!args.has("tld") || args.get("tld").isJsonNull()) {
      throw new AiToolException("Missing required arg: tld");
    }
    String tldStr = args.get("tld").getAsString();

    ToolJpaHelper.assertTldAccess(user, tldStr);

    Tld tld;
    try {
      tld = Tld.get(tldStr);
    } catch (Tld.TldNotFoundException e) {
      JsonObject err = new JsonObject();
      err.addProperty("error", "TLD not found: " + tldStr);
      return err;
    }

    JsonObject out = new JsonObject();
    out.addProperty("tld", tld.getTldStr());
    out.addProperty("tld_state", tld.getTldState(now()).toString());
    out.addProperty("tld_type", tld.getTldType().toString());
    out.addProperty("currency", tld.getCurrency() == null ? null : tld.getCurrency().toString());

    Optional<String> premiumListName = tld.getPremiumListName();
    out.addProperty("premium_list_name", premiumListName.orElse(null));

    JsonArray reservedListNames = new JsonArray();
    for (String name : tld.getReservedListNames()) {
      reservedListNames.add(name);
    }
    out.add("reserved_list_names", reservedListNames);

    JsonArray dnsWriters = new JsonArray();
    for (String writer : tld.getDnsWriters()) {
      dnsWriters.add(writer);
    }
    out.add("dns_writers", dnsWriters);

    ImmutableSet<String> allowedIds =
        RegistryDashAccessUtil.getRegistrarIdsForTlds(ImmutableSet.of(tldStr));
    JsonArray allowedRegistrars = new JsonArray();
    int included = 0;
    for (String id : allowedIds) {
      if (included >= MAX_REGISTRARS) {
        break;
      }
      Optional<Registrar> registrar = Registrar.loadByRegistrarId(id);
      JsonObject entry = new JsonObject();
      entry.addProperty("registrar_id", id);
      entry.addProperty(
          "registrar_name", registrar.map(Registrar::getRegistrarName).orElse(null));
      allowedRegistrars.add(entry);
      included++;
    }
    out.add("allowed_registrars", allowedRegistrars);
    out.addProperty("allowed_registrars_count", allowedIds.size());
    out.addProperty("allowed_registrars_truncated", allowedIds.size() > MAX_REGISTRARS);

    return out;
  }

  private DateTime now() {
    return new DateTime(clock.instant().toEpochMilli(), DateTimeZone.UTC);
  }
}
