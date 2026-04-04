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
