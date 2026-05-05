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
import com.google.gson.JsonObject;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.ui.server.console.registrydash.RegistryDashAccessUtil;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Optional;

/**
 * AI tool: returns a registrar's profile (type, state, allowed TLDs, contacts).
 *
 * <p>Wraps the {@link Registrar} JPA entity. Permission: admin ({@link GlobalRole#FTE}) bypass;
 * non-admins must be mapped (via RoRegistry) to a registry whose TLDs include at least one of the
 * registrar's {@code allowedTlds}.
 */
@Singleton
public class GetRegistrarDetailsTool implements AiTool {

  @Inject
  public GetRegistrarDetailsTool() {}

  @Override
  public String name() {
    return "get_registrar_details";
  }

  @Override
  public Complexity complexity() {
    return Complexity.EASY;
  }

  @Override
  public String description() {
    return "Returns a registrar's profile: type (REAL/OTE/PDT/...), state (ACTIVE/SUSPENDED/...),"
        + " IANA identifier, allowed TLDs, and contacts. Use when the user asks about a specific"
        + " registrar by id or name (beyond what the chart snapshot already shows).";
  }

  @Override
  public JsonObject inputSchema() {
    JsonObject schema = new JsonObject();
    schema.addProperty("type", "object");
    JsonObject props = new JsonObject();

    JsonObject registrarId = new JsonObject();
    registrarId.addProperty("type", "string");
    registrarId.addProperty(
        "description", "Registrar id (e.g. 'TheRegistrar') — the stable internal identifier");
    props.add("registrar_id", registrarId);

    schema.add("properties", props);
    JsonArray required = new JsonArray();
    required.add("registrar_id");
    schema.add("required", required);
    return schema;
  }

  @Override
  public ToolResult executeWithStatus(JsonObject args, User user) {
    if (!args.has("registrar_id") || args.get("registrar_id").isJsonNull()) {
      return ToolResult.invalidArgs("Missing required arg: registrar_id");
    }
    String registrarId = args.get("registrar_id").getAsString();

    boolean isAdmin = user.getUserRoles().getGlobalRole() == GlobalRole.FTE;
    if (!isAdmin) {
      ImmutableSet<String> mapped =
          RegistryDashAccessUtil.getMappedRegistrarIds(user.getEmailAddress());
      if (!mapped.contains(registrarId)) {
        return ToolResult.permissionDenied("Permission denied for registrar: " + registrarId);
      }
    }

    Optional<Registrar> maybe = Registrar.loadByRegistrarId(registrarId);
    if (maybe.isEmpty()) {
      return ToolResult.invalidArgs("Registrar not found: " + registrarId);
    }
    Registrar registrar = maybe.get();

    JsonObject out = new JsonObject();
    out.addProperty("registrar_id", registrar.getRegistrarId());
    out.addProperty("registrar_name", registrar.getRegistrarName());
    out.addProperty("type", registrar.getType() == null ? null : registrar.getType().toString());
    out.addProperty("state", registrar.getState() == null ? null : registrar.getState().toString());
    out.addProperty("iana_identifier", registrar.getIanaIdentifier());
    out.addProperty("email_address", registrar.getEmailAddress());
    out.addProperty("phone_number", registrar.getPhoneNumber());
    out.addProperty("fax_number", registrar.getFaxNumber());
    out.addProperty("whois_server", registrar.getWhoisServer());

    JsonArray allowedTlds = new JsonArray();
    for (String tld : registrar.getAllowedTlds()) {
      allowedTlds.add(tld);
    }
    out.add("allowed_tlds", allowedTlds);

    JsonArray rdapBaseUrls = new JsonArray();
    for (String url : registrar.getRdapBaseUrls()) {
      rdapBaseUrls.add(url);
    }
    out.add("rdap_base_urls", rdapBaseUrls);

    JsonArray contacts = new JsonArray();
    for (RegistrarPoc poc : registrar.getContacts()) {
      JsonObject c = new JsonObject();
      c.addProperty("name", poc.getName());
      c.addProperty("email", poc.getEmailAddress());
      c.addProperty("phone", poc.getPhoneNumber());
      c.addProperty("fax", poc.getFaxNumber());
      JsonArray types = new JsonArray();
      poc.getTypes().forEach(t -> types.add(t.toString()));
      c.add("types", types);
      contacts.add(c);
    }
    out.add("contacts", contacts);

    return ToolResult.ok(out);
  }
}
