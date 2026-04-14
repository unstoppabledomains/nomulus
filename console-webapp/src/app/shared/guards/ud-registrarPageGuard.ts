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

// UD: Registry Dashboard — redirect REGISTRY_OPERATOR users away from registrar-scoped pages
import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { toObservable } from '@angular/core/rxjs-interop';
import { filter, map, take } from 'rxjs';
import { UserDataService } from '../services/userData.service';

export const udRegistrarPageGuard: CanActivateFn = () => {
  const userDataService = inject(UserDataService);
  const router = inject(Router);

  const userData = userDataService.userData();
  if (userData) {
    return userData.globalRole === 'REGISTRY_OPERATOR'
      ? router.createUrlTree(['/registry-dash'])
      : true;
  }

  // userData not yet loaded — wait for signal, then check
  return toObservable(userDataService.userData).pipe(
    filter((data) => data !== undefined),
    take(1),
    map((data) =>
      data!.globalRole === 'REGISTRY_OPERATOR'
        ? router.createUrlTree(['/registry-dash'])
        : true
    )
  );
};
