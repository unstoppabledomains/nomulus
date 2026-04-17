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

/**
 * Enum defining valid data sources for the explore endpoint. Each source declares its allowed
 * metrics, dimensions, and filters. The query builder uses these to validate requests and reject
 * unknown fields with 400 (not 500).
 */
public enum ExploreDataSource {

  DOMAIN_ACTIVITY(
      ImmutableSet.of("count"),
      ImmutableSet.of("tld", "activity_type", "period", "registrar"),
      ImmutableSet.of("tlds", "activityTypes", "dateRange")),

  REVENUE(
      ImmutableSet.of("amount", "netAmountToRegistry"),
      ImmutableSet.of("tld", "operation", "period"),
      ImmutableSet.of("tlds", "operations", "dateRange")),

  DOMAIN_COUNTS(
      ImmutableSet.of("count"),
      ImmutableSet.of("tld", "registrar"),
      ImmutableSet.of("tlds", "registrarIds")),

  RENEWAL_RATES(
      ImmutableSet.of("renewals", "deletions", "renewalRate"),
      ImmutableSet.of("tld"),
      ImmutableSet.of("tlds", "dateRange")),

  EXPIRATION_CURVE(
      ImmutableSet.of("count"),
      ImmutableSet.of("tld", "month"),
      ImmutableSet.of("tlds", "dateRange")),

  PRICING_RULES(
      ImmutableSet.of("priceAmount"),
      ImmutableSet.of("registrar", "tld", "operation"),
      ImmutableSet.of("tlds", "registrarIds", "operations"));

  private final ImmutableSet<String> allowedMetrics;
  private final ImmutableSet<String> allowedDimensions;
  private final ImmutableSet<String> allowedFilters;

  ExploreDataSource(
      ImmutableSet<String> allowedMetrics,
      ImmutableSet<String> allowedDimensions,
      ImmutableSet<String> allowedFilters) {
    this.allowedMetrics = allowedMetrics;
    this.allowedDimensions = allowedDimensions;
    this.allowedFilters = allowedFilters;
  }

  public ImmutableSet<String> getAllowedMetrics() {
    return allowedMetrics;
  }

  public ImmutableSet<String> getAllowedDimensions() {
    return allowedDimensions;
  }

  /**
   * Validates the descriptor against this source's allowlist. Throws IllegalArgumentException on
   * unknown fields.
   */
  public void validate(ExploreQueryDescriptor desc) {
    for (ExploreQueryDescriptor.MetricSpec m : desc.getMetrics()) {
      if (!allowedMetrics.contains(m.getField())) {
        throw new IllegalArgumentException(
            String.format(
                "Unknown metric '%s' for data source %s. Allowed: %s",
                m.getField(), name(), allowedMetrics));
      }
    }
    for (String dim : desc.getDimensions()) {
      if (!allowedDimensions.contains(dim)) {
        throw new IllegalArgumentException(
            String.format(
                "Unknown dimension '%s' for data source %s. Allowed: %s",
                dim, name(), allowedDimensions));
      }
    }
    ExploreQueryDescriptor.ExploreFilters f = desc.getFilters();
    if (!f.getTlds().isEmpty() && !allowedFilters.contains("tlds")) {
      throw new IllegalArgumentException(
          "Filter 'tlds' not supported for " + name());
    }
    if (!f.getRegistrarIds().isEmpty()
        && !allowedFilters.contains("registrarIds")) {
      throw new IllegalArgumentException(
          "Filter 'registrarIds' not supported for " + name());
    }
    if (!f.getActivityTypes().isEmpty()
        && !allowedFilters.contains("activityTypes")) {
      throw new IllegalArgumentException(
          "Filter 'activityTypes' not supported for " + name());
    }
    if (!f.getOperations().isEmpty()
        && !allowedFilters.contains("operations")) {
      throw new IllegalArgumentException(
          "Filter 'operations' not supported for " + name());
    }
    if (f.getDateRange() != null && !allowedFilters.contains("dateRange")) {
      throw new IllegalArgumentException(
          "Filter 'dateRange' not supported for " + name());
    }
  }
}
