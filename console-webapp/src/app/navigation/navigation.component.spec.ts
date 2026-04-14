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

// UD: Registry Dashboard — tests for navigation auto-expand and admin visibility
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { RouterTestingModule } from '@angular/router/testing';
import { signal, WritableSignal } from '@angular/core';
import { NavigationComponent } from './navigation.component';
import { UserData, UserDataService } from '../shared/services/userData.service';
import { PATHS, RouteWithIcon } from '../app-routing.module';
import { RESTRICTED_ELEMENTS } from '../shared/directives/userLevelVisiblity.directive';
import { AppModule } from '../app.module';

describe('NavigationComponent', () => {
  let component: NavigationComponent;
  let fixture: ComponentFixture<NavigationComponent>;
  let mockUserDataService: { userData: WritableSignal<Partial<UserData> | undefined> };

  function setup(globalRole: string) {
    mockUserDataService = {
      userData: signal<Partial<UserData> | undefined>({ globalRole }),
    };

    TestBed.configureTestingModule({
      imports: [NoopAnimationsModule, RouterTestingModule, AppModule],
      providers: [
        { provide: UserDataService, useValue: mockUserDataService },
      ],
    });

    fixture = TestBed.createComponent(NavigationComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    TestBed.flushEffects();
  }

  it('should create', () => {
    setup('NONE');
    expect(component).toBeTruthy();
  });

  it('should auto-expand Registry Dashboard for REGISTRY_OPERATOR', () => {
    setup('REGISTRY_OPERATOR');
    const registryDashNode = component.dataSource.data.find(
      (node) => node.path === PATHS.RegistryDash
    );
    expect(registryDashNode).toBeTruthy();
    expect(component.treeControl.isExpanded(registryDashNode!)).toBe(true);
  });

  it('should NOT auto-expand Registry Dashboard for NONE role', () => {
    setup('NONE');
    const registryDashNode = component.dataSource.data.find(
      (node) => node.path === PATHS.RegistryDash
    );
    expect(registryDashNode).toBeTruthy();
    expect(component.treeControl.isExpanded(registryDashNode!)).toBe(false);
  });

  it('should NOT auto-expand Registry Dashboard for FTE role', () => {
    setup('FTE');
    const registryDashNode = component.dataSource.data.find(
      (node) => node.path === PATHS.RegistryDash
    );
    expect(registryDashNode).toBeTruthy();
    expect(component.treeControl.isExpanded(registryDashNode!)).toBe(false);
  });

  // UD: Registry Dashboard — admin tab should map to REGISTRY_DASH_ADMIN restriction
  it('should map admin child of registry-dash to REGISTRY_DASH_ADMIN', () => {
    setup('NONE');
    const registryDashNode = component.dataSource.data.find(
      (node) => node.path === PATHS.RegistryDash
    );
    expect(registryDashNode?.children).toBeTruthy();
    const adminChild = registryDashNode!.children!.find(
      (c) => c.path === 'admin'
    );
    expect(adminChild).toBeTruthy();
    expect(component.getElementId(adminChild as RouteWithIcon)).toBe(
      RESTRICTED_ELEMENTS.REGISTRY_DASH_ADMIN
    );
  });
});
