import { Component, effect } from '@angular/core';
import { MaterialModule } from '../../material.module';
import { CommonModule } from '@angular/common';
import { RegistryDashService } from '../registry-dash.service';

@Component({
  selector: 'app-registry-dash-overview',
  imports: [MaterialModule, CommonModule],
  templateUrl: './overview.component.html',
  styleUrls: ['./overview.component.scss'],
})
export class OverviewComponent {
  displayedColumns = ['registrarId', 'name', 'count'];

  constructor(protected dashService: RegistryDashService) {
    this.dashService.getOverview().subscribe();
  }
}
