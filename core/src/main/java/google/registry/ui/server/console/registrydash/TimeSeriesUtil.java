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

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Utilities for zero-filling sparse time-series data returned by registry dashboard queries. */
final class TimeSeriesUtil {

  private TimeSeriesUtil() {}

  /**
   * Generates all expected period labels between startDate and endDate at the given granularity,
   * then merges with existing data — inserting zero-value entries for missing periods.
   *
   * @param startDate the start of the time range
   * @param endDate the end of the time range
   * @param granularity one of "15min", "hour", "day", "month"
   * @param existingData the sparse data rows, each containing a "period" key
   * @param periodKey the key name for the period field (e.g., "period" or "month")
   * @param zeroTemplate a map of keys to zero values for missing periods (e.g., {"amount": 0})
   * @return the data with zero-filled gaps, preserving original ordering
   */
  static List<Map<String, Object>> zeroFill(
      Instant startDate,
      Instant endDate,
      String granularity,
      List<Map<String, Object>> existingData,
      String periodKey,
      Map<String, Object> zeroTemplate) {

    List<String> allPeriods = generatePeriodLabels(startDate, endDate, granularity);
    Set<String> existingPeriods = new LinkedHashSet<>();
    for (Map<String, Object> row : existingData) {
      existingPeriods.add(String.valueOf(row.get(periodKey)));
    }

    List<Map<String, Object>> result = new ArrayList<>(existingData);
    for (String period : allPeriods) {
      if (!existingPeriods.contains(period)) {
        Map<String, Object> zeroRow = new java.util.HashMap<>(zeroTemplate);
        zeroRow.put(periodKey, period);
        result.add(zeroRow);
      }
    }

    result.sort((a, b) -> String.valueOf(a.get(periodKey))
        .compareTo(String.valueOf(b.get(periodKey))));
    return result;
  }

  static List<String> generatePeriodLabels(Instant startDate, Instant endDate, String granularity) {
    List<String> labels = new ArrayList<>();
    ZonedDateTime current = truncate(startDate.atZone(ZoneOffset.UTC), granularity);
    ZonedDateTime end = endDate.atZone(ZoneOffset.UTC);

    while (!current.isAfter(end)) {
      labels.add(formatPeriod(current.toInstant(), granularity));
      current = advance(current, granularity);
    }
    return labels;
  }

  private static ZonedDateTime truncate(ZonedDateTime zdt, String granularity) {
    return switch (granularity) {
      case "15min" -> zdt.truncatedTo(ChronoUnit.HOURS)
          .plusMinutes((zdt.getMinute() / 15) * 15L);
      case "hour" -> zdt.truncatedTo(ChronoUnit.HOURS);
      case "day" -> zdt.truncatedTo(ChronoUnit.DAYS);
      case "month" -> zdt.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
      default -> zdt;
    };
  }

  private static ZonedDateTime advance(ZonedDateTime zdt, String granularity) {
    return switch (granularity) {
      case "15min" -> zdt.plusMinutes(15);
      case "hour" -> zdt.plusHours(1);
      case "day" -> zdt.plusDays(1);
      case "month" -> zdt.plusMonths(1);
      default -> zdt.plusDays(1);
    };
  }

  static String formatPeriod(Instant instant, String granularity) {
    ZonedDateTime zdt = instant.atZone(ZoneOffset.UTC);
    return switch (granularity) {
      case "month" -> zdt.toLocalDate().toString().substring(0, 7);
      case "day" -> zdt.toLocalDate().toString();
      default -> instant.toString();
    };
  }
}
