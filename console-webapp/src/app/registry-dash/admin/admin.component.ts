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
