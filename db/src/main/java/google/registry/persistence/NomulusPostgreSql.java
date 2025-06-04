// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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
package google.registry.persistence;

import org.testcontainers.utility.DockerImageName;

/** Information about Nomulus' Cloud SQL PostgreSql instance. */
public class NomulusPostgreSql {

  /** The current PostgreSql version in Cloud SQL. */
  // TODO(weiminyu): setup periodic checks to detect version changes in Cloud SQL.
  private static final String TARGET_VERSION = "17-alpine";

  /**
   * Returns the docker image of the targeted Postgresql server version.
   *
   * <p>If the architecture is not amd64, the image will be prefixed with the architecture name.
   *
   * @see <a href="https://hub.docker.com/_/postgres">Postgres Docker Hub</a>
   */
  public static DockerImageName getDockerImageName() {
    String image = "postgres:" + TARGET_VERSION;
    return DockerImageName.parse(image).asCompatibleSubstituteFor("postgres");
  }
}
