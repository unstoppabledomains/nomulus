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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Tests for date/time parsing in {@link RegistryDashExploreAction}. */
class RegistryDashExploreActionTest {

  @Test
  void parseDateTime_dateOnlyStart_returnsStartOfDay() {
    Instant result = RegistryDashExploreAction.parseDateTime("2026-04-21", false);
    assertThat(result).isEqualTo(Instant.parse("2026-04-21T00:00:00Z"));
  }

  @Test
  void parseDateTime_dateOnlyEnd_returnsNextDayStartOfDay() {
    Instant result = RegistryDashExploreAction.parseDateTime("2026-04-21", true);
    assertThat(result).isEqualTo(Instant.parse("2026-04-22T00:00:00Z"));
  }

  @Test
  void parseDateTime_datetimeStart_returnsExactTime() {
    Instant result = RegistryDashExploreAction.parseDateTime("2026-04-21T14:30:00", false);
    assertThat(result).isEqualTo(Instant.parse("2026-04-21T14:30:00Z"));
  }

  @Test
  void parseDateTime_datetimeEnd_returnsExactTime() {
    Instant result = RegistryDashExploreAction.parseDateTime("2026-04-21T14:30:00", true);
    assertThat(result).isEqualTo(Instant.parse("2026-04-21T14:30:00Z"));
  }

  @Test
  void parseDateTime_mixedFormats_dateStartDatetimeEnd() {
    Instant start = RegistryDashExploreAction.parseDateTime("2026-04-14", false);
    Instant end = RegistryDashExploreAction.parseDateTime("2026-04-21T14:30:00", true);
    assertThat(start).isEqualTo(Instant.parse("2026-04-14T00:00:00Z"));
    assertThat(end).isEqualTo(Instant.parse("2026-04-21T14:30:00Z"));
  }

  @Test
  void parseDateTime_invalidFormat_throws() {
    assertThrows(Exception.class, () ->
        RegistryDashExploreAction.parseDateTime("not-a-date", false));
  }
}
