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

import { ComponentFixture, TestBed } from '@angular/core/testing';

import { HeaderComponent } from './header.component';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MaterialModule } from '../material.module';
import { ActivatedRoute } from '@angular/router';
import { AppModule, SelectedRegistrarModule } from '../app.module';
import { AppRoutingModule } from '../app-routing.module';
import { BackendService } from '../shared/services/backend.service';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal, WritableSignal } from '@angular/core';
import { UserData, UserDataService } from '../shared/services/userData.service';
import { Registrar, RegistrarService } from '../registrar/registrar.service';

describe('HeaderComponent', () => {
  let component: HeaderComponent;
  let fixture: ComponentFixture<HeaderComponent>;
  let mockUserDataService: { userData: WritableSignal<Partial<UserData> | undefined> };
  let mockRegistrarService: {
    registrarId: WritableSignal<string>;
    registrars: WritableSignal<Array<Partial<Registrar>>>;
  };

  function setup(globalRole: string) {
    mockUserDataService = {
      userData: signal<Partial<UserData> | undefined>({ globalRole }),
    };
    mockRegistrarService = {
      registrarId: signal('test-registrar'),
      registrars: signal([]),
    };
  }

  beforeEach(async () => {
    setup('NONE');

    await TestBed.configureTestingModule({
      imports: [
        SelectedRegistrarModule,
        MaterialModule,
        NoopAnimationsModule,
        AppRoutingModule,
        AppModule,
      ],
      providers: [
        BackendService,
        { provide: ActivatedRoute, useValue: {} as ActivatedRoute },
        { provide: UserDataService, useValue: mockUserDataService },
        { provide: RegistrarService, useValue: mockRegistrarService },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
      declarations: [HeaderComponent],
    }).compileComponents();

    fixture = TestBed.createComponent(HeaderComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  // UD: Registry Dashboard — verify registrar selector visibility by role
  describe('registrar selector visibility', () => {
    it('should hide registrar selector for REGISTRY_OPERATOR', () => {
      mockUserDataService.userData.set({ globalRole: 'REGISTRY_OPERATOR' });
      fixture.detectChanges();
      TestBed.flushEffects();
      fixture.detectChanges();

      const selector = fixture.nativeElement.querySelector('app-registrar-selector');
      expect(selector).toBeTruthy();
      expect(selector.style.display).toBe('none');
    });

    it('should show registrar selector for NONE role', () => {
      mockUserDataService.userData.set({ globalRole: 'NONE' });
      fixture.detectChanges();
      TestBed.flushEffects();
      fixture.detectChanges();

      const selector = fixture.nativeElement.querySelector('app-registrar-selector');
      expect(selector).toBeTruthy();
      expect(selector.style.display).not.toBe('none');
    });

    it('should show registrar selector for FTE role', () => {
      mockUserDataService.userData.set({ globalRole: 'FTE' });
      fixture.detectChanges();
      TestBed.flushEffects();
      fixture.detectChanges();

      const selector = fixture.nativeElement.querySelector('app-registrar-selector');
      expect(selector).toBeTruthy();
      expect(selector.style.display).not.toBe('none');
    });
  });
});
