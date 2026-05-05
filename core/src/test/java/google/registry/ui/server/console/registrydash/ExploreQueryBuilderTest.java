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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

class ExploreQueryBuilderTest {

  private static final Gson GSON = new Gson();

  private ExploreQueryDescriptor parse(String json) {
    return GSON.fromJson(json, ExploreQueryDescriptor.class);
  }

  @Test
  void domainActivity_basicQuery_generatesValidSql() {
    ExploreQueryDescriptor desc = parse("""
        {
          "dataSource": "DOMAIN_ACTIVITY",
          "metrics": [{"field": "count", "aggregation": "sum"}],
          "dimensions": ["tld", "period"],
          "filters": {"dateRange": {"start": "2025-01-01", "end": "2025-12-31"}},
          "granularity": "month"
        }""");
    String sql = ExploreQueryBuilder.build(
        ExploreDataSource.DOMAIN_ACTIVITY, desc, ImmutableSet.of("modem", "nft"));
    assertThat(sql).contains("\"DomainHistory\"");
    assertThat(sql).contains("\"Domain\"");
    assertThat(sql).contains("date_trunc('month'");
    assertThat(sql).contains("d.tld IN (:tlds)");
    assertThat(sql).contains("COUNT(*)");
    assertThat(sql).contains("GROUP BY");
    assertThat(sql).contains("LIMIT :maxRows");
  }

  @Test
  void domainActivity_adminNoTlds_omitsTldFilter() {
    ExploreQueryDescriptor desc = parse("""
        {
          "dataSource": "DOMAIN_ACTIVITY",
          "metrics": [{"field": "count"}],
          "dimensions": ["tld"],
          "filters": {}
        }""");
    String sql = ExploreQueryBuilder.build(
        ExploreDataSource.DOMAIN_ACTIVITY, desc, ImmutableSet.of());
    assertThat(sql).doesNotContain("d.tld IN");
  }

  @Test
  void domainActivity_withActivityTypeFilter_includesFilter() {
    ExploreQueryDescriptor desc = parse("""
        {
          "dataSource": "DOMAIN_ACTIVITY",
          "metrics": [{"field": "count"}],
          "dimensions": ["tld", "activity_type"],
          "filters": {"activityTypes": ["CREATES", "RENEWS"]}
        }""");
    String sql = ExploreQueryBuilder.build(
        ExploreDataSource.DOMAIN_ACTIVITY, desc, ImmutableSet.of("modem"));
    assertThat(sql).contains(":activityTypes");
  }

  @Test
  void domainActivity_withRegistrarIdsFilter_includesRegistrarClause() {
    // SRE-1958 phase A bug fix: DOMAIN_ACTIVITY must apply registrarIds filter when
    // present, otherwise restricted users would see all registrars' activity.
    ExploreQueryDescriptor desc = parse("""
        {
          "dataSource": "DOMAIN_ACTIVITY",
          "metrics": [{"field": "count"}],
          "dimensions": ["tld", "registrar"],
          "filters": {"registrarIds": ["registrar1", "registrar2"]}
        }""");
    String sql = ExploreQueryBuilder.build(
        ExploreDataSource.DOMAIN_ACTIVITY, desc, ImmutableSet.of("modem"));
    assertThat(sql).contains("d.current_sponsor_registrar_id IN (:registrarIds)");
  }

  @Test
  void domainActivity_emptyRegistrarIds_omitsRegistrarClause() {
    ExploreQueryDescriptor desc = parse("""
        {
          "dataSource": "DOMAIN_ACTIVITY",
          "metrics": [{"field": "count"}],
          "dimensions": ["tld"],
          "filters": {}
        }""");
    String sql = ExploreQueryBuilder.build(
        ExploreDataSource.DOMAIN_ACTIVITY, desc, ImmutableSet.of("modem"));
    assertThat(sql).doesNotContain(":registrarIds");
  }

  @Test
  void revenue_basicQuery_includesCostBasisJoin() {
    ExploreQueryDescriptor desc = parse("""
        {
          "dataSource": "REVENUE",
          "metrics": [{"field": "netAmountToRegistry"}],
          "dimensions": ["tld", "period"],
          "filters": {"dateRange": {"start": "2025-01-01", "end": "2025-12-31"}},
          "granularity": "month"
        }""");
    String sql = ExploreQueryBuilder.build(
        ExploreDataSource.REVENUE, desc, ImmutableSet.of("modem"));
    assertThat(sql).contains("\"BillingEvent\"");
    assertThat(sql).contains("RegistryDashboardCostBasis");
    assertThat(sql).contains("rsp_retained_fee_amount");
  }

  @Test
  void domainCounts_basicQuery_generatesSimpleCount() {
    ExploreQueryDescriptor desc = parse("""
        {
          "dataSource": "DOMAIN_COUNTS",
          "metrics": [{"field": "count"}],
          "dimensions": ["tld"],
          "filters": {}
        }""");
    String sql = ExploreQueryBuilder.build(
        ExploreDataSource.DOMAIN_COUNTS, desc, ImmutableSet.of());
    assertThat(sql).contains("\"Domain\"");
    assertThat(sql).contains("deletion_time > CURRENT_TIMESTAMP");
    assertThat(sql).contains("COUNT(*)");
  }

  @Test
  void renewalRates_basicQuery_includesRenewAndDelete() {
    ExploreQueryDescriptor desc = parse("""
        {
          "dataSource": "RENEWAL_RATES",
          "metrics": [{"field": "renewals"}, {"field": "deletions"}],
          "dimensions": ["tld"],
          "filters": {"dateRange": {"start": "2025-01-01", "end": "2025-12-31"}}
        }""");
    String sql = ExploreQueryBuilder.build(
        ExploreDataSource.RENEWAL_RATES, desc, ImmutableSet.of("modem"));
    assertThat(sql).contains("DOMAIN_RENEW");
    assertThat(sql).contains("DOMAIN_DELETE");
  }

  @Test
  void accessScoping_alwaysApplied_forNonEmptyTlds() {
    ExploreQueryDescriptor desc = parse("""
        {
          "dataSource": "DOMAIN_ACTIVITY",
          "metrics": [{"field": "count"}],
          "dimensions": ["tld"],
          "filters": {}
        }""");
    String sql = ExploreQueryBuilder.build(
        ExploreDataSource.DOMAIN_ACTIVITY, desc, ImmutableSet.of("modem"));
    assertThat(sql).contains("d.tld IN (:tlds)");
  }

  @Test
  void invalidGranularity_defaultsToMonth() {
    ExploreQueryDescriptor desc = parse("""
        {
          "dataSource": "DOMAIN_ACTIVITY",
          "metrics": [{"field": "count"}],
          "dimensions": ["tld", "period"],
          "filters": {},
          "granularity": "invalid_granularity"
        }""");
    String sql = ExploreQueryBuilder.build(
        ExploreDataSource.DOMAIN_ACTIVITY, desc, ImmutableSet.of());
    assertThat(sql).contains("date_trunc('month'");
  }

  @Test
  void limitClause_alwaysPresent() {
    ExploreQueryDescriptor desc = parse("""
        {
          "dataSource": "DOMAIN_COUNTS",
          "metrics": [{"field": "count"}],
          "dimensions": ["tld"],
          "filters": {}
        }""");
    String sql = ExploreQueryBuilder.build(
        ExploreDataSource.DOMAIN_COUNTS, desc, ImmutableSet.of());
    assertThat(sql).contains("LIMIT :maxRows");
  }
}
