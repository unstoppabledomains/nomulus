import { Component } from '@angular/core';
import { MaterialModule } from '../../material.module';
import { CommonModule } from '@angular/common';
import { RegistryDashService } from '../registry-dash.service';

@Component({
  selector: 'app-registry-dash-portfolio',
  imports: [MaterialModule, CommonModule],
  templateUrl: './portfolio.component.html',
  styleUrls: ['./portfolio.component.scss'],
})
export class PortfolioComponent {
  displayedColumns = [
    'registrarId',
    'registrarName',
    'state',
    'domainCount',
    'allowedTlds',
  ];

  constructor(protected dashService: RegistryDashService) {
    this.dashService.getPortfolio().subscribe();
  }
}
