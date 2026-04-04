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

// UD: Registry Dashboard — tests for FTE-only route guard
import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';
import { signal, WritableSignal } from '@angular/core';
import { UserData, UserDataService } from '../services/userData.service';
import { udFteOnlyGuard } from './ud-fteOnlyGuard';

describe('udFteOnlyGuard', () => {
  let mockUserDataService: { userData: WritableSignal<Partial<UserData> | undefined> };
  let router: Router;

  beforeEach(() => {
    mockUserDataService = {
      userData: signal<Partial<UserData> | undefined>({ globalRole: 'NONE' }),
    };

    TestBed.configureTestingModule({
      imports: [RouterTestingModule],
      providers: [
        { provide: UserDataService, useValue: mockUserDataService },
      ],
    });

    router = TestBed.inject(Router);
  });

  function runGuard(): ReturnType<typeof udFteOnlyGuard> {
    return TestBed.runInInjectionContext(() =>
      udFteOnlyGuard({} as any, {} as any)
    );
  }

  it('should allow FTE role through', () => {
    mockUserDataService.userData.set({ globalRole: 'FTE' });
    const result = runGuard();
    expect(result).toBe(true);
  });

  it('should redirect REGISTRY_OPERATOR to /registry-dash/overview', () => {
    mockUserDataService.userData.set({ globalRole: 'REGISTRY_OPERATOR' });
    const result = runGuard();
    expect(result).toBeInstanceOf(UrlTree);
    expect((result as UrlTree).toString()).toBe('/registry-dash/overview');
  });

  it('should redirect NONE role to /registry-dash/overview', () => {
    mockUserDataService.userData.set({ globalRole: 'NONE' });
    const result = runGuard();
    expect(result).toBeInstanceOf(UrlTree);
    expect((result as UrlTree).toString()).toBe('/registry-dash/overview');
  });

  it('should redirect SUPPORT_AGENT to /registry-dash/overview', () => {
    mockUserDataService.userData.set({ globalRole: 'SUPPORT_AGENT' });
    const result = runGuard();
    expect(result).toBeInstanceOf(UrlTree);
    expect((result as UrlTree).toString()).toBe('/registry-dash/overview');
  });
});
