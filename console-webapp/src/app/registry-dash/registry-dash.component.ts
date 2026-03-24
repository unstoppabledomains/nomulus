import { Component } from '@angular/core';
import { RouterModule } from '@angular/router';
import { MaterialModule } from '../material.module';

@Component({
  selector: 'app-registry-dash',
  imports: [MaterialModule, RouterModule],
  templateUrl: './registry-dash.component.html',
  styleUrls: ['./registry-dash.component.scss'],
})
export class RegistryDashComponent {
  static readonly PATH = 'registry-dash';
}
