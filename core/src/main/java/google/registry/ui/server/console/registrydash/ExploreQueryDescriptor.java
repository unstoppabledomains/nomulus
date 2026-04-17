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

import com.google.common.collect.ImmutableList;
import java.util.List;

/** Request body for the data exploration endpoint. Deserialized from JSON via Gson. */
public class ExploreQueryDescriptor {

  private String dataSource;
  private List<MetricSpec> metrics;
  private List<String> dimensions;
  private ExploreFilters filters;
  private String granularity;
  private Integer limit;

  public String getDataSource() {
    return dataSource;
  }

  public List<MetricSpec> getMetrics() {
    return metrics != null ? metrics : ImmutableList.of();
  }

  public List<String> getDimensions() {
    return dimensions != null ? dimensions : ImmutableList.of();
  }

  public ExploreFilters getFilters() {
    return filters != null ? filters : new ExploreFilters();
  }

  public String getGranularity() {
    return granularity;
  }

  public int getLimit() {
    return limit != null ? Math.min(limit, 10000) : 1000;
  }

  public static class MetricSpec {
    private String field;
    private String aggregation;

    public String getField() {
      return field;
    }

    public String getAggregation() {
      return aggregation != null ? aggregation : "sum";
    }
  }

  public static class ExploreFilters {
    private List<String> tlds;
    private List<String> registrarIds;
    private List<String> activityTypes;
    private List<String> operations;
    private DateRange dateRange;

    public List<String> getTlds() {
      return tlds != null ? tlds : ImmutableList.of();
    }

    public List<String> getRegistrarIds() {
      return registrarIds != null ? registrarIds : ImmutableList.of();
    }

    public List<String> getActivityTypes() {
      return activityTypes != null ? activityTypes : ImmutableList.of();
    }

    public List<String> getOperations() {
      return operations != null ? operations : ImmutableList.of();
    }

    public DateRange getDateRange() {
      return dateRange;
    }
  }

  public static class DateRange {
    private String start;
    private String end;

    public String getStart() {
      return start;
    }

    public String getEnd() {
      return end;
    }
  }
}
