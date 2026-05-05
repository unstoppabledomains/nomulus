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

import { Component, Input, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { EffectiveFeesComponent } from './effective-fees.component';
import { RegistryDashService, EffectiveFeeEntry } from '../../registry-dash.service';
import { AiSparkleButtonComponent } from '../../ai/ai-sparkle-button.component';
import { PRICING_PROMPTS, ASK_ANYTHING_PROMPT } from '../../ai/ai-prompts';

@Component({ selector: 'app-ai-sparkle-button', standalone: true, template: '' })
class StubAiSparkleButtonComponent {
  @Input() page: any;
  @Input() prompts: any;
  @Input() chartData: any;
  @Input() isAdmin: any;
}

describe('EffectiveFeesComponent', () => {
  let fixture: ComponentFixture<EffectiveFeesComponent>;
  let component: EffectiveFeesComponent;
  let dashServiceStub: any;

  const sampleFees: EffectiveFeeEntry[] = [
    {
      registrarId: 'reg-a', registrarName: 'Reg A', tld: 'app',
      operation: 'CREATE', price: 10, currency: 'USD', source: 'Default',
    },
    {
      registrarId: 'reg-b', registrarName: 'Reg B', tld: 'dev',
      operation: 'RENEW', price: 8, currency: 'USD', source: 'Custom',
    },
  ];

  beforeEach(async () => {
    dashServiceStub = {
      loading: signal(false),
      error: signal<string | undefined>(undefined),
      effectiveFees: signal<EffectiveFeeEntry[]>(sampleFees),
      filteredEffectiveFees: signal<EffectiveFeeEntry[]>(sampleFees),
      getEffectiveFees: jasmine.createSpy('getEffectiveFees').and.returnValue(of(sampleFees)),
    };

    await TestBed.configureTestingModule({
      imports: [EffectiveFeesComponent, NoopAnimationsModule],
      providers: [
        { provide: RegistryDashService, useValue: dashServiceStub },
      ],
    })
      .overrideComponent(EffectiveFeesComponent, {
        remove: { imports: [AiSparkleButtonComponent] },
        add: { imports: [StubAiSparkleButtonComponent] },
      })
      .compileComponents();

    fixture = TestBed.createComponent(EffectiveFeesComponent);
    component = fixture.componentInstance;
  });

  it('creates', () => {
    fixture.detectChanges();
    expect(component).toBeTruthy();
  });

  it('exposes PRICING_PROMPTS for the ✨ menu', () => {
    expect(component.aiPrompts).toBe(PRICING_PROMPTS);
  });

  it('PRICING_PROMPTS ends with ASK_ANYTHING_PROMPT (cold-start always available)', () => {
    expect(component.aiPrompts[component.aiPrompts.length - 1])
      .toBe(ASK_ANYTHING_PROMPT);
  });

  it('renders an ✨ button with page="pricing", PRICING_PROMPTS, and the filtered fees as chartData', () => {
    fixture.detectChanges();
    const stubs = fixture.debugElement.queryAll(
      e => e.componentInstance instanceof StubAiSparkleButtonComponent,
    );
    expect(stubs.length).toBe(1);
    const inst = stubs[0].componentInstance as StubAiSparkleButtonComponent;
    expect(inst.page).toBe('pricing');
    expect(inst.prompts).toBe(PRICING_PROMPTS);
    expect(inst.chartData).toEqual(sampleFees);
  });
});
