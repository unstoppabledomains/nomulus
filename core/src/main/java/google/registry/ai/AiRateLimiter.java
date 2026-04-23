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

import com.google.common.annotations.VisibleForTesting;
import google.registry.util.Clock;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.joda.time.Duration;

@Singleton
public class AiRateLimiter {

  private static final Duration WINDOW = Duration.standardHours(1);

  private final Clock clock;
  private final int maxPerHour;
  private final ConcurrentHashMap<String, Deque<Long>> requests = new ConcurrentHashMap<>();

  @Inject
  @VisibleForTesting
  AiRateLimiter(Clock clock, @Named("aiRateLimitPerHour") int maxPerHour) {
    this.clock = clock;
    this.maxPerHour = maxPerHour;
  }

  public boolean tryAcquire(String userEmail) {
    long now = clock.nowUtc().getMillis();
    long cutoff = now - WINDOW.getMillis();
    Deque<Long> timestamps =
        requests.computeIfAbsent(userEmail, k -> new ConcurrentLinkedDeque<>());

    while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
      timestamps.pollFirst();
    }

    if (timestamps.size() >= maxPerHour) {
      return false;
    }
    timestamps.addLast(now);
    return true;
  }

  public long getRetryAfterSeconds(String userEmail) {
    long now = clock.nowUtc().getMillis();
    Deque<Long> timestamps = requests.get(userEmail);
    if (timestamps == null || timestamps.isEmpty()) {
      return 0;
    }
    long oldest = timestamps.peekFirst();
    long expiresAt = oldest + WINDOW.getMillis();
    return Math.max(1, (expiresAt - now) / 1000);
  }
}
