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

import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.Config;
import google.registry.privileges.secretmanager.SecretManagerClient;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

@Module
public final class AnthropicModule {

  private static final String LATEST_SECRET_VERSION = "latest";

  @Provides
  @Singleton
  @Named("anthropicHttpClient")
  static OkHttpClient provideAnthropicHttpClient() {
    return new OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build();
  }

  @Provides
  @Singleton
  @Named("anthropicApiKey")
  static String provideAnthropicApiKey(
      SecretManagerClient secretManagerClient,
      @Config("anthropicApiKeySecretName") String secretName) {
    String envKey = System.getenv("ANTHROPIC_API_KEY");
    if (envKey != null && !envKey.isEmpty()) {
      return envKey;
    }
    return secretManagerClient.getSecretData(secretName, Optional.of(LATEST_SECRET_VERSION));
  }

  @Provides
  @Named("anthropicApiBaseUrl")
  @SuppressWarnings("UseBinds")
  static String provideAnthropicApiBaseUrl(@Config("anthropicApiBaseUrl") String baseUrl) {
    return baseUrl;
  }

  @Provides
  @Named("anthropicDefaultModel")
  @SuppressWarnings("UseBinds")
  static String provideAnthropicDefaultModel(@Config("anthropicDefaultModel") String model) {
    return model;
  }

  @Provides
  @Singleton
  @Named("aiRateLimitPerHour")
  @SuppressWarnings("UseBinds")
  static int provideAiRateLimitPerHour(@Config("anthropicRateLimitPerHour") int limit) {
    return limit;
  }
}
