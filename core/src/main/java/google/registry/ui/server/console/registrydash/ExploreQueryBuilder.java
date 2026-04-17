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

import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ExploreQueryBuilder {

  private static final ImmutableSet<String> VALID_GRANULARITIES =
      ImmutableSet.of("hour", "day", "month");

  private ExploreQueryBuilder() {}

  public static String build(
      ExploreDataSource source,
      ExploreQueryDescriptor desc,
      ImmutableSet<String> effectiveTlds) {
    return switch (source) {
      case DOMAIN_ACTIVITY -> buildDomainActivity(desc, effectiveTlds);
      case REVENUE -> buildRevenue(desc, effectiveTlds);
      case DOMAIN_COUNTS -> buildDomainCounts(desc, effectiveTlds);
      case RENEWAL_RATES -> buildRenewalRates(desc, effectiveTlds);
      case EXPIRATION_CURVE -> buildExpirationCurve(desc, effectiveTlds);
      case PRICING_RULES -> buildPricingRules(desc, effectiveTlds);
    };
  }

  private static String buildDomainActivity(
      ExploreQueryDescriptor desc, ImmutableSet<String> tlds) {
    String gran = resolveGranularity(desc.getGranularity());
    Set<String> dims = Set.copyOf(desc.getDimensions());
    ExploreQueryDescriptor.ExploreFilters f = desc.getFilters();

    List<String> selectCols = new ArrayList<>();
    List<String> groupByCols = new ArrayList<>();

    if (dims.contains("period")) {
      selectCols.add(
          String.format("date_trunc('%s', dh.history_modification_time) AS period", gran));
      groupByCols.add("period");
    }
    if (dims.contains("tld")) {
      selectCols.add("d.tld");
      groupByCols.add("d.tld");
    }
    if (dims.contains("activity_type")) {
      selectCols.add(
          "CASE"
              + " WHEN dh.history_type = 'DOMAIN_CREATE' THEN 'CREATES'"
              + " WHEN dh.history_type IN ('DOMAIN_RENEW', 'DOMAIN_AUTORENEW') THEN 'RENEWS'"
              + " WHEN dh.history_type = 'DOMAIN_TRANSFER_APPROVE' THEN 'TRANSFERS'"
              + " WHEN dh.history_type = 'DOMAIN_DELETE' THEN 'DELETES'"
              + " WHEN dh.history_type = 'DOMAIN_RESTORE' THEN 'RESTORES'"
              + " ELSE 'OTHER'"
              + " END AS activity_type");
      groupByCols.add("activity_type");
    }
    if (dims.contains("registrar")) {
      selectCols.add("d.current_sponsor_registrar_id AS registrar");
      groupByCols.add("d.current_sponsor_registrar_id");
    }
    selectCols.add("COUNT(*) AS count_value");

    List<String> whereClauses = new ArrayList<>();
    whereClauses.add(
        "dh.history_type IN ('DOMAIN_CREATE', 'DOMAIN_RENEW', 'DOMAIN_AUTORENEW',"
            + " 'DOMAIN_TRANSFER_APPROVE', 'DOMAIN_DELETE', 'DOMAIN_RESTORE')");
    if (!tlds.isEmpty()) {
      whereClauses.add("d.tld IN (:tlds)");
    }
    if (f.getDateRange() != null) {
      whereClauses.add("dh.history_modification_time >= :startDate");
      whereClauses.add("dh.history_modification_time <= :endDate");
    }
    if (!f.getActivityTypes().isEmpty()) {
      whereClauses.add("dh.history_type IN (:activityTypes)");
    }
    return assembleQuery(
        selectCols,
        "\"DomainHistory\" dh JOIN \"Domain\" d ON d.repo_id = dh.domain_repo_id",
        whereClauses,
        groupByCols,
        dims.contains("period") ? "period" : null);
  }

  private static String buildRevenue(
      ExploreQueryDescriptor desc, ImmutableSet<String> tlds) {
    String gran = resolveGranularity(desc.getGranularity());
    Set<String> dims = Set.copyOf(desc.getDimensions());
    Set<String> metricFields = new HashSet<>();
    for (ExploreQueryDescriptor.MetricSpec m : desc.getMetrics()) {
      metricFields.add(m.getField());
    }
    ExploreQueryDescriptor.ExploreFilters f = desc.getFilters();

    List<String> selectCols = new ArrayList<>();
    List<String> groupByCols = new ArrayList<>();

    if (dims.contains("period")) {
      selectCols.add(
          String.format(
              "date_trunc('%s', dh.history_modification_time) AS period", gran));
      groupByCols.add("period");
    }
    if (dims.contains("tld")) {
      selectCols.add("d.tld");
      groupByCols.add("d.tld");
    }
    if (dims.contains("operation")) {
      selectCols.add("b.reason AS operation");
      groupByCols.add("b.reason");
    }
    if (metricFields.contains("amount")) {
      selectCols.add("SUM(b.cost_amount) AS amount");
    }
    if (metricFields.contains("netAmountToRegistry")) {
      selectCols.add(
          "SUM(b.cost_amount - COALESCE(cb.rsp_retained_fee_amount, 0))"
              + " AS net_amount_to_registry");
    }
    selectCols.add("b.cost_currency");
    groupByCols.add("b.cost_currency");

    String fromClause =
        "\"BillingEvent\" b"
            + " JOIN \"Domain\" d ON d.repo_id = b.domain_repo_id"
            + " JOIN \"DomainHistory\" dh ON dh.history_revision_id ="
            + " b.domain_history_revision_id"
            + " AND dh.domain_repo_id = b.domain_repo_id"
            + " LEFT JOIN LATERAL ("
            + " SELECT rsp_retained_fee_amount"
            + " FROM \"RegistryDashboardCostBasis\""
            + " WHERE (tld = d.tld OR tld = '*')"
            + " AND operation = b.reason"
            + " AND effective_date <= dh.history_modification_time"
            + " ORDER BY CASE WHEN tld = d.tld THEN 0 ELSE 1 END, effective_date DESC"
            + " LIMIT 1"
            + ") cb ON true";

    List<String> whereClauses = new ArrayList<>();
    whereClauses.add("b.reason IN ('CREATE', 'RENEW', 'TRANSFER', 'RESTORE')");
    if (!tlds.isEmpty()) {
      whereClauses.add("d.tld IN (:tlds)");
    }
    if (f.getDateRange() != null) {
      whereClauses.add("dh.history_modification_time >= :startDate");
      whereClauses.add("dh.history_modification_time <= :endDate");
    }
    if (!f.getOperations().isEmpty()) {
      whereClauses.add("b.reason IN (:operations)");
    }
    return assembleQuery(
        selectCols,
        fromClause,
        whereClauses,
        groupByCols,
        dims.contains("period") ? "period" : null);
  }

  private static String buildDomainCounts(
      ExploreQueryDescriptor desc, ImmutableSet<String> tlds) {
    Set<String> dims = Set.copyOf(desc.getDimensions());
    ExploreQueryDescriptor.ExploreFilters f = desc.getFilters();

    List<String> selectCols = new ArrayList<>();
    List<String> groupByCols = new ArrayList<>();

    if (dims.contains("tld")) {
      selectCols.add("d.tld");
      groupByCols.add("d.tld");
    }
    if (dims.contains("registrar")) {
      selectCols.add("d.current_sponsor_registrar_id AS registrar");
      groupByCols.add("d.current_sponsor_registrar_id");
    }
    selectCols.add("COUNT(*) AS count_value");

    List<String> whereClauses = new ArrayList<>();
    whereClauses.add("d.deletion_time > CURRENT_TIMESTAMP");
    if (!tlds.isEmpty()) {
      whereClauses.add("d.tld IN (:tlds)");
    }
    if (!f.getRegistrarIds().isEmpty()) {
      whereClauses.add("d.current_sponsor_registrar_id IN (:registrarIds)");
    }
    return assembleQuery(selectCols, "\"Domain\" d", whereClauses, groupByCols, null);
  }

  private static String buildRenewalRates(
      ExploreQueryDescriptor desc, ImmutableSet<String> tlds) {
    ExploreQueryDescriptor.ExploreFilters f = desc.getFilters();

    List<String> selectCols = new ArrayList<>();
    selectCols.add("d.tld");
    selectCols.add(
        "COUNT(CASE WHEN dh.history_type IN ('DOMAIN_RENEW', 'DOMAIN_AUTORENEW') THEN 1 END)"
            + " AS renewals");
    selectCols.add(
        "COUNT(CASE WHEN dh.history_type = 'DOMAIN_DELETE' THEN 1 END) AS deletions");

    List<String> whereClauses = new ArrayList<>();
    whereClauses.add(
        "dh.history_type IN ('DOMAIN_RENEW', 'DOMAIN_AUTORENEW', 'DOMAIN_DELETE')");
    if (!tlds.isEmpty()) {
      whereClauses.add("d.tld IN (:tlds)");
    }
    if (f.getDateRange() != null) {
      whereClauses.add("dh.history_modification_time >= :startDate");
      whereClauses.add("dh.history_modification_time <= :endDate");
    }
    return assembleQuery(
        selectCols,
        "\"DomainHistory\" dh JOIN \"Domain\" d ON d.repo_id = dh.domain_repo_id",
        whereClauses,
        List.of("d.tld"),
        null);
  }

  private static String buildExpirationCurve(
      ExploreQueryDescriptor desc, ImmutableSet<String> tlds) {
    ExploreQueryDescriptor.ExploreFilters f = desc.getFilters();

    List<String> selectCols = new ArrayList<>();
    selectCols.add("date_trunc('month', d.registration_expiration_time) AS month");
    selectCols.add("d.tld");
    selectCols.add("COUNT(*) AS count_value");

    List<String> whereClauses = new ArrayList<>();
    whereClauses.add("d.deletion_time > CURRENT_TIMESTAMP");
    whereClauses.add("d.registration_expiration_time > CURRENT_TIMESTAMP");
    if (!tlds.isEmpty()) {
      whereClauses.add("d.tld IN (:tlds)");
    }
    if (f.getDateRange() != null && f.getDateRange().getEnd() != null) {
      whereClauses.add("d.registration_expiration_time < :endDate");
    }
    return assembleQuery(
        selectCols, "\"Domain\" d", whereClauses, List.of("month", "d.tld"), "month");
  }

  private static String buildPricingRules(
      ExploreQueryDescriptor desc, ImmutableSet<String> tlds) {
    Set<String> dims = Set.copyOf(desc.getDimensions());
    ExploreQueryDescriptor.ExploreFilters f = desc.getFilters();

    List<String> selectCols = new ArrayList<>();
    List<String> groupByCols = new ArrayList<>();

    if (dims.contains("registrar")) {
      selectCols.add("p.registrar_id AS registrar");
      groupByCols.add("p.registrar_id");
    }
    if (dims.contains("tld")) {
      selectCols.add("p.tld");
      groupByCols.add("p.tld");
    }
    if (dims.contains("operation")) {
      selectCols.add("p.operation");
      groupByCols.add("p.operation");
    }
    selectCols.add("AVG(p.price_amount) AS price_amount");
    selectCols.add("p.price_currency");
    groupByCols.add("p.price_currency");

    List<String> whereClauses = new ArrayList<>();
    whereClauses.add("p.is_active = true");
    if (!tlds.isEmpty()) {
      whereClauses.add("p.tld IN (:tlds)");
    }
    if (!f.getRegistrarIds().isEmpty()) {
      whereClauses.add("p.registrar_id IN (:registrarIds)");
    }
    if (!f.getOperations().isEmpty()) {
      whereClauses.add("p.operation IN (:operations)");
    }
    return assembleQuery(selectCols, "\"RegistryDashboardRegistrarPricing\" p",
        whereClauses, groupByCols, null);
  }

  private static String resolveGranularity(String granularity) {
    if (granularity != null && VALID_GRANULARITIES.contains(granularity)) {
      return granularity;
    }
    return "month";
  }

  private static String assembleQuery(
      List<String> selectCols,
      String fromClause,
      List<String> whereClauses,
      List<String> groupByCols,
      String orderBy) {
    StringBuilder sb = new StringBuilder();
    sb.append("SELECT ").append(String.join(", ", selectCols));
    sb.append(" FROM ").append(fromClause);
    if (!whereClauses.isEmpty()) {
      sb.append(" WHERE ").append(String.join(" AND ", whereClauses));
    }
    if (!groupByCols.isEmpty()) {
      sb.append(" GROUP BY ").append(String.join(", ", groupByCols));
    }
    if (orderBy != null) {
      sb.append(" ORDER BY ").append(orderBy);
    }
    sb.append(" LIMIT :maxRows");
    return sb.toString();
  }
}
