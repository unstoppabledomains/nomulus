// UD: Registry Dashboard — restrict certain routes to FTE users only
import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { toObservable } from '@angular/core/rxjs-interop';
import { filter, map, take } from 'rxjs';
import { UserDataService } from '../services/userData.service';

export const udFteOnlyGuard: CanActivateFn = () => {
  const userDataService = inject(UserDataService);
  const router = inject(Router);

  const userData = userDataService.userData();
  if (userData) {
    return userData.globalRole === 'FTE'
      ? true
      : router.createUrlTree(['/registry-dash/overview']);
  }

  // userData not yet loaded — wait for signal, then check
  return toObservable(userDataService.userData).pipe(
    filter((data) => data !== undefined),
    take(1),
    map((data) =>
      data!.globalRole === 'FTE'
        ? true
        : router.createUrlTree(['/registry-dash/overview'])
    )
  );
};
