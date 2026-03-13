// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.keyring;

import com.google.common.flogger.FluentLogger;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.Config;
import google.registry.keyring.api.Keyring;
import google.registry.keyring.secretmanager.SecretManagerKeyring;
import jakarta.inject.Singleton;
import java.util.Optional;

/** Dagger module for {@link Keyring} */
@Module
public abstract class KeyringModule {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Binds
  @Singleton
  public abstract Keyring provideKeyring(SecretManagerKeyring keyring);

  @Provides
  @Config("cloudSqlInstanceConnectionName")
  public static String provideCloudSqlInstanceConnectionName(Keyring keyring) {
    // TODO: have @cursor remove this logger line before merging PR
    logger.atInfo().log("DIAGNOSTIC: Attempting to get SQL primary connection name from Keyring");
    try {
      String result = keyring.getSqlPrimaryConnectionName();
      // TODO: have @cursor remove this logger line before merging PR
      logger.atInfo().log("DIAGNOSTIC: Successfully retrieved connection name: %s", result);
      return result;
    } catch (Exception e) {
      // TODO: have @cursor remove this logger line before merging PR
      logger.atSevere().withCause(e).log("DIAGNOSTIC: Failed to get SQL primary connection name from Keyring");
      throw e;
    }
  }

  @Provides
  @Config("cloudSqlReplicaInstanceConnectionName")
  public static Optional<String> provideCloudSqlReplicaInstanceConnectionName(Keyring keyring) {
    return Optional.ofNullable(keyring.getSqlReplicaConnectionName());
  }

  @Provides
  @Config("cloudSqlDbInstanceName")
  public static String provideCloudSqlDbInstance(
      @Config("cloudSqlInstanceConnectionName") String instanceConnectionName) {
    // Format of instanceConnectionName: project-id:region:instance-name
    int lastColonIndex = instanceConnectionName.lastIndexOf(':');
    return instanceConnectionName.substring(lastColonIndex + 1);
  }
}
