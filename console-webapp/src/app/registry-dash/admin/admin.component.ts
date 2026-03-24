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

import { Component, OnInit, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl, Validators } from '@angular/forms';
import { MaterialModule } from '../../material.module';
import { RegistryDashService } from '../registry-dash.service';

@Component({
  selector: 'app-admin',
  standalone: true,
  imports: [CommonModule, MaterialModule, ReactiveFormsModule],
  templateUrl: './admin.component.html',
  styleUrls: ['./admin.component.scss'],
})
export class AdminComponent implements OnInit {
  mappingColumns = ['userEmailAddress', 'registrarId', 'createdAt', 'actions'];
  registrarColumns = ['registrarId', 'registrarName', 'allowedTlds'];

  addForm = new FormGroup({
    userEmailAddress: new FormControl('', [Validators.required, Validators.email]),
    registrarId: new FormControl('', [Validators.required]),
  });

  mappings = computed(() => this.dashService.adminData()?.mappings || []);
  systemInfo = computed(() => this.dashService.adminData()?.systemInfo);

  constructor(public dashService: RegistryDashService) {}

  ngOnInit() {
    this.dashService.getAdminData().subscribe();
  }

  onAddMapping() {
    if (this.addForm.invalid) return;
    const { userEmailAddress, registrarId } = this.addForm.value;
    this.dashService
      .createMapping({ userEmailAddress: userEmailAddress!, registrarId: registrarId! })
      .subscribe(() => {
        this.addForm.reset();
      });
  }

  onDeleteMapping(id: number) {
    this.dashService.deleteMapping(id).subscribe();
  }
}
