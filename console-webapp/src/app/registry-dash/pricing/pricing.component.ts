import { Component, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MaterialModule } from '../../material.module';
import { CommonModule } from '@angular/common';
import { MatSnackBar } from '@angular/material/snack-bar';
import { SnackBarModule } from '../../snackbar.module';
import { PricingRule, RegistryDashService } from '../registry-dash.service';

@Component({
  selector: 'app-registry-dash-pricing',
  imports: [MaterialModule, CommonModule, ReactiveFormsModule, SnackBarModule],
  templateUrl: './pricing.component.html',
  styleUrls: ['./pricing.component.scss'],
})
export class PricingComponent {
  displayedColumns = [
    'registrarId',
    'tld',
    'operation',
    'priceAmount',
    'priceCurrency',
    'effectiveDate',
    'isActive',
    'actions',
  ];

  showForm = signal(false);
  editingRule = signal<PricingRule | undefined>(undefined);

  pricingForm = new FormGroup({
    registrarId: new FormControl('', [Validators.required]),
    tld: new FormControl('', [Validators.required]),
    operation: new FormControl('', [Validators.required]),
    priceAmount: new FormControl<number | null>(null, [
      Validators.required,
      Validators.min(0),
    ]),
    priceCurrency: new FormControl('USD', [Validators.required]),
    effectiveDate: new FormControl(''),
    expiryDate: new FormControl(''),
    isActive: new FormControl(true),
  });

  constructor(
    protected dashService: RegistryDashService,
    private snackBar: MatSnackBar
  ) {
    this.dashService.getPricing().subscribe();
  }

  openAddForm() {
    this.editingRule.set(undefined);
    this.pricingForm.reset({ priceCurrency: 'USD', isActive: true });
    this.showForm.set(true);
  }

  openEditForm(rule: PricingRule) {
    this.editingRule.set(rule);
    this.pricingForm.patchValue({
      registrarId: rule.registrarId,
      tld: rule.tld,
      operation: rule.operation,
      priceAmount: rule.priceAmount,
      priceCurrency: rule.priceCurrency,
      effectiveDate: rule.effectiveDate,
      expiryDate: rule.expiryDate || '',
      isActive: rule.isActive,
    });
    this.showForm.set(true);
  }

  cancelForm() {
    this.showForm.set(false);
    this.editingRule.set(undefined);
  }

  onSubmit() {
    if (!this.pricingForm.valid) return;

    const formValue = this.pricingForm.value;
    const rule: PricingRule = {
      registrarId: formValue.registrarId!,
      tld: formValue.tld!,
      operation: formValue.operation!,
      priceAmount: formValue.priceAmount!,
      priceCurrency: formValue.priceCurrency!,
      effectiveDate: formValue.effectiveDate || '',
      expiryDate: formValue.expiryDate || undefined,
      isActive: formValue.isActive ?? true,
    };

    const editing = this.editingRule();
    if (editing?.id) {
      rule.id = editing.id;
      this.dashService.updatePricingRule(rule).subscribe({
        next: () => {
          this.snackBar.open('Pricing rule updated', 'OK', { duration: 3000 });
          this.cancelForm();
        },
        error: (err) => this.snackBar.open(err.error || 'Update failed'),
      });
    } else {
      this.dashService.createPricingRule(rule).subscribe({
        next: () => {
          this.snackBar.open('Pricing rule created', 'OK', { duration: 3000 });
          this.cancelForm();
        },
        error: (err) => this.snackBar.open(err.error || 'Create failed'),
      });
    }
  }
}
