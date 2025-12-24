// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.privileges.secretmanager;


import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretManagerServiceSettings;
import com.google.common.flogger.FluentLogger;
import dagger.Module;
import dagger.Provides;
import google.registry.config.CredentialModule.ApplicationDefaultCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.util.GoogleCredentialsBundle;
import google.registry.util.Retrier;
import jakarta.inject.Singleton;
import java.io.IOException;

/** Provides bindings for {@link SecretManagerClient}. */
@Module
public abstract class SecretManagerModule {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Provides
  @Singleton
  static SecretManagerServiceSettings provideSecretManagerSetting(
      @ApplicationDefaultCredential GoogleCredentialsBundle credentialsBundle) {
    // TODO: have @cursor remove this logger line before merging PR
    logger.atInfo().log("DIAGNOSTIC: Creating SecretManagerServiceSettings");
    try {
      SecretManagerServiceSettings settings = SecretManagerServiceSettings.newBuilder()
          .setCredentialsProvider(() -> credentialsBundle.getGoogleCredentials())
          .build();
      // TODO: have @cursor remove this logger line before merging PR
      logger.atInfo().log("DIAGNOSTIC: Successfully created SecretManagerServiceSettings");
      return settings;
    } catch (IOException e) {
      // TODO: have @cursor remove this logger line before merging PR
      logger.atSevere().withCause(e).log("DIAGNOSTIC: Failed to create SecretManagerServiceSettings");
      throw new RuntimeException(e);
    }
  }

  @Provides
  @Singleton
  static SecretManagerClient provideSecretManagerClient(
      SecretManagerServiceSettings serviceSettings,
      @Config("projectId") String project,
      Retrier retrier) {
    // TODO: have @cursor remove this logger line before merging PR
    logger.atInfo().log("DIAGNOSTIC: Creating SecretManagerClient for project: %s", project);
    try {
      SecretManagerServiceClient stub = SecretManagerServiceClient.create(serviceSettings);
      Runtime.getRuntime().addShutdownHook(new Thread(stub::close));
      SecretManagerClient client = new SecretManagerClientImpl(project, stub, retrier);
      // TODO: have @cursor remove this logger line before merging PR
      logger.atInfo().log("DIAGNOSTIC: Successfully created SecretManagerClient");
      return client;
    } catch (IOException e) {
      // TODO: have @cursor remove this logger line before merging PR
      logger.atSevere().withCause(e).log("DIAGNOSTIC: Failed to create SecretManagerClient");
      throw new RuntimeException(e);
    }
  }
}
