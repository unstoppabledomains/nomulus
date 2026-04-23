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

package google.registry.ai;

import static com.google.common.truth.Truth.assertThat;

import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.Test;

class AiRateLimiterTest {

  private final FakeClock clock = new FakeClock(DateTime.parse("2026-01-01T00:00:00Z"));

  @Test
  void testAllowsRequestsUnderLimit() {
    AiRateLimiter limiter = new AiRateLimiter(clock, 5);
    for (int i = 0; i < 5; i++) {
      assertThat(limiter.tryAcquire("user@test.com")).isTrue();
    }
  }

  @Test
  void testBlocksRequestsOverLimit() {
    AiRateLimiter limiter = new AiRateLimiter(clock, 3);
    for (int i = 0; i < 3; i++) {
      assertThat(limiter.tryAcquire("user@test.com")).isTrue();
    }
    assertThat(limiter.tryAcquire("user@test.com")).isFalse();
  }

  @Test
  void testSlidingWindowExpiry() {
    AiRateLimiter limiter = new AiRateLimiter(clock, 2);
    assertThat(limiter.tryAcquire("user@test.com")).isTrue();
    assertThat(limiter.tryAcquire("user@test.com")).isTrue();
    assertThat(limiter.tryAcquire("user@test.com")).isFalse();

    clock.advanceBy(Duration.standardMinutes(61));

    assertThat(limiter.tryAcquire("user@test.com")).isTrue();
  }

  @Test
  void testIndependentPerUser() {
    AiRateLimiter limiter = new AiRateLimiter(clock, 1);
    assertThat(limiter.tryAcquire("a@test.com")).isTrue();
    assertThat(limiter.tryAcquire("a@test.com")).isFalse();
    assertThat(limiter.tryAcquire("b@test.com")).isTrue();
  }

  @Test
  void testGetRetryAfterSeconds() {
    AiRateLimiter limiter = new AiRateLimiter(clock, 1);
    limiter.tryAcquire("user@test.com");
    limiter.tryAcquire("user@test.com");

    long retryAfter = limiter.getRetryAfterSeconds("user@test.com");
    assertThat(retryAfter).isGreaterThan(0);
    assertThat(retryAfter).isAtMost(3600);
  }
}
