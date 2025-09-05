// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.dns.writer.powerdns;

import com.google.common.flogger.FluentLogger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Duration;

public class ZoneRectificationState {
  // Class configuration
  public static final String NAME = "ZoneRectificationState";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private DateTime lastRectificationTime;
  private final ReentrantLock rectifyZoneLock;
  private final AtomicBoolean isRectificationRequested;
  private final String zoneId;
  private final Integer autoRectifyThresholdMinutes;

  public ZoneRectificationState(String zoneId, Integer autoRectifyThresholdMinutes) {
    this.rectifyZoneLock = new ReentrantLock();
    this.isRectificationRequested = new AtomicBoolean(false);
    this.zoneId = zoneId;
    this.autoRectifyThresholdMinutes = autoRectifyThresholdMinutes;
  }

  public boolean tryLock() {
    return rectifyZoneLock.tryLock();
  }

  public void unlock() {
    rectifyZoneLock.unlock();
  }

  public void setLastRectificationTime() {
    logger.atInfo().log("Updating last rectification time for PowerDNS TLD zone %s", zoneId);
    this.lastRectificationTime = DateTime.now(DateTimeZone.UTC);
  }

  public void setIsRectificationRequested(boolean isRectificationRequested) {
    this.isRectificationRequested.set(isRectificationRequested);
  }

  public boolean isRectificationRequired() {
    boolean isRequired = isRectificationRequested.get() && isRectificationTimeExpired();
    if (isRequired) {
      logger.atInfo().log("Rectification is required for PowerDNS TLD zone %s", zoneId);
      return true;
    }
    logger.atInfo().log("Rectification is not required for PowerDNS TLD zone %s", zoneId);
    return false;
  }

  private boolean isRectificationTimeExpired() {
    return lastRectificationTime == null
        || lastRectificationTime
            .plus(Duration.standardMinutes(autoRectifyThresholdMinutes))
            .isBeforeNow();
  }
}
