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

import { Injectable, signal } from '@angular/core';
import { Observable, throwError } from 'rxjs';
import { catchError, tap } from 'rxjs/operators';
import { HttpErrorResponse } from '@angular/common/http';
import { BackendService } from '../../shared/services/backend.service';
import { ExploreQuery, ExploreResult, SavedExploreView } from './explore.models';

const RECENT_VIEWS_KEY = 'registry-dash-explore-recent';
const MAX_RECENT_VIEWS = 5;

@Injectable({ providedIn: 'root' })
export class ExploreService {
  result = signal<ExploreResult | undefined>(undefined);
  loading = signal(false);
  error = signal<string | undefined>(undefined);

  constructor(private backend: BackendService) {}

  explore(query: ExploreQuery): Observable<ExploreResult> {
    this.loading.set(true);
    this.error.set(undefined);
    return this.backend.postRegistryDashExplore(query).pipe(
      tap((data: ExploreResult) => {
        this.result.set(data);
        this.loading.set(false);
      }),
      catchError((err: HttpErrorResponse) => {
        const msg = typeof err.error === 'string' ? err.error
          : err.error?.message ?? `Error ${err.status}`;
        this.error.set(msg);
        this.loading.set(false);
        return throwError(() => err);
      }),
    );
  }

  getRecentViews(): SavedExploreView[] {
    try {
      const raw = localStorage.getItem(RECENT_VIEWS_KEY);
      return raw ? JSON.parse(raw) : [];
    } catch {
      return [];
    }
  }

  saveRecentView(name: string, query: ExploreQuery, chartType: string): void {
    const views = this.getRecentViews();
    const newView: SavedExploreView = {
      name,
      query,
      chartType: chartType as any,
      savedAt: new Date().toISOString(),
    };
    const updated = [newView, ...views.filter(v => v.name !== name)].slice(0, MAX_RECENT_VIEWS);
    localStorage.setItem(RECENT_VIEWS_KEY, JSON.stringify(updated));
  }

  deleteRecentView(name: string): void {
    const views = this.getRecentViews().filter(v => v.name !== name);
    localStorage.setItem(RECENT_VIEWS_KEY, JSON.stringify(views));
  }
}
