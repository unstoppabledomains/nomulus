// UD: Registry Dashboard — tests for REGISTRY_OPERATOR route guard
import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';
import { signal, WritableSignal } from '@angular/core';
import { UserData, UserDataService } from '../services/userData.service';
import { udRegistrarPageGuard } from './ud-registrarPageGuard';

describe('udRegistrarPageGuard', () => {
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

  function runGuard(): ReturnType<typeof udRegistrarPageGuard> {
    return TestBed.runInInjectionContext(() =>
      udRegistrarPageGuard({} as any, {} as any)
    );
  }

  it('should redirect REGISTRY_OPERATOR to /registry-dash', () => {
    mockUserDataService.userData.set({ globalRole: 'REGISTRY_OPERATOR' });
    const result = runGuard();
    expect(result).toBeInstanceOf(UrlTree);
    expect((result as UrlTree).toString()).toBe('/registry-dash');
  });

  it('should allow NONE role through', () => {
    mockUserDataService.userData.set({ globalRole: 'NONE' });
    const result = runGuard();
    expect(result).toBe(true);
  });

  it('should allow FTE role through', () => {
    mockUserDataService.userData.set({ globalRole: 'FTE' });
    const result = runGuard();
    expect(result).toBe(true);
  });

  it('should allow SUPPORT_AGENT role through', () => {
    mockUserDataService.userData.set({ globalRole: 'SUPPORT_AGENT' });
    const result = runGuard();
    expect(result).toBe(true);
  });

  it('should allow SUPPORT_LEAD role through', () => {
    mockUserDataService.userData.set({ globalRole: 'SUPPORT_LEAD' });
    const result = runGuard();
    expect(result).toBe(true);
  });
});
