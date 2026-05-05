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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.console.User;
import google.registry.model.domain.Domain;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * AI tool: returns full lifecycle details for a single domain — current registrar, status flags,
 * creation/expiration timestamps, and recent history events.
 */
@Singleton
public class QueryDomainDetailsTool implements AiTool {

  private static final int MAX_HISTORY_EVENTS = 50;

  private static final String HISTORY_QUERY =
      """
      SELECT dh.history_modification_time, dh.history_type, dh.history_registrar_id
      FROM "DomainHistory" dh
      WHERE dh.domain_repo_id = :repoId
      ORDER BY dh.history_modification_time DESC
      LIMIT :maxRows
      """;

  @Inject
  public QueryDomainDetailsTool() {}

  @Override
  public String name() {
    return "query_domain_details";
  }

  @Override
  public String description() {
    return "Returns full lifecycle details for a single domain: current registrar, status flags,"
        + " creation/expiration timestamps, and recent history events. Use when the user asks"
        + " about a specific domain by name.";
  }

  @Override
  public JsonObject inputSchema() {
    JsonObject schema = new JsonObject();
    schema.addProperty("type", "object");
    JsonObject props = new JsonObject();

    JsonObject domainName = new JsonObject();
    domainName.addProperty("type", "string");
    domainName.addProperty("description", "Fully-qualified domain name (e.g. 'foo.example')");
    props.add("domain_name", domainName);

    schema.add("properties", props);
    JsonArray required = new JsonArray();
    required.add("domain_name");
    schema.add("required", required);
    return schema;
  }

  @Override
  public ToolResult executeWithStatus(JsonObject args, User user) {
    if (!args.has("domain_name") || args.get("domain_name").isJsonNull()) {
      return ToolResult.invalidArgs("Missing required arg: domain_name");
    }
    String domainName = args.get("domain_name").getAsString();

    return tm().transact(
        () -> {
          Optional<Domain> maybe =
              ForeignKeyUtils.loadResourceByCacheIfEnabled(
                  Domain.class, domainName, tm().getTransactionTime());
          if (maybe.isEmpty()) {
            return ToolResult.invalidArgs("Domain not found: " + domainName);
          }
          Domain domain = maybe.get();

          try {
            ToolJpaHelper.assertTldAccess(user, domain.getTld());
          } catch (AiToolException e) {
            return ToolResult.permissionDenied(e.getMessage());
          }

          JsonObject out = new JsonObject();
          out.addProperty("domain_name", domain.getDomainName());
          out.addProperty("tld", domain.getTld());
          out.addProperty("current_registrar", domain.getCurrentSponsorRegistrarId());
          out.addProperty(
              "creation_time",
              domain.getCreationTime() == null ? null : domain.getCreationTime().toString());
          out.addProperty(
              "expiration_time",
              domain.getRegistrationExpirationTime() == null
                  ? null
                  : domain.getRegistrationExpirationTime().toString());

          JsonArray statusFlags = new JsonArray();
          if (domain.getStatusValues() != null) {
            for (Object s : domain.getStatusValues()) {
              statusFlags.add(s.toString());
            }
          }
          out.add("status_flags", statusFlags);

          JsonArray history = new JsonArray();
          Query q =
              tm().getEntityManager()
                  .createNativeQuery(HISTORY_QUERY)
                  .setParameter("repoId", domain.getRepoId())
                  .setParameter("maxRows", MAX_HISTORY_EVENTS);
          @SuppressWarnings("unchecked")
          List<Object[]> rows = q.getResultList();
          for (Object[] row : rows) {
            JsonObject ev = new JsonObject();
            ev.addProperty(
                "time",
                row[0] == null ? null : normalizeTimestamp(row[0]));
            ev.addProperty("type", row[1] == null ? null : row[1].toString());
            ev.addProperty(
                "registrar", row[2] == null ? null : row[2].toString());
            history.add(ev);
          }
          out.add("history", history);
          out.addProperty("historyCount", history.size());
          out.addProperty("historyTruncated", history.size() >= MAX_HISTORY_EVENTS);
          return ToolResult.ok(out);
        });
  }

  private static String normalizeTimestamp(Object val) {
    if (val instanceof java.sql.Timestamp ts) {
      return ts.toInstant().toString();
    }
    if (val instanceof java.time.OffsetDateTime odt) {
      return odt.toInstant().toString();
    }
    if (val instanceof Instant inst) {
      return inst.toString();
    }
    return val.toString();
  }
}
