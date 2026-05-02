import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../services/api.service';

@Component({
  selector: 'app-metrics-panel',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="grid grid-cols-4" style="margin-bottom: 24px;">
      <!-- Total Volume -->
      <div class="metric-card">
        <div class="metric-card-header">
          <div class="metric-card-label">Total Volume</div>
          <div class="metric-card-icon" style="background: var(--primary-soft); color: var(--primary);">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 12h-4l-3 9L9 3l-3 9H2"></path></svg>
          </div>
        </div>
        <div class="metric-card-value">{{ metrics?.totalCalls || 0 }}</div>
        <div class="metric-card-footer">
          <span class="metric-card-trend-up">↑</span>
          Total calls processed
        </div>
      </div>

      <!-- Current Queue -->
      <div class="metric-card">
        <div class="metric-card-header">
          <div class="metric-card-label">Current Queue</div>
          <div class="metric-card-icon" style="background: var(--warning-soft); color: var(--warning);">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect><line x1="8" y1="12" x2="16" y2="12"></line></svg>
          </div>
        </div>
        <div class="metric-card-value" [style.color]="(metrics?.queuedCalls || 0) > 5 ? 'var(--danger)' : 'var(--warning)'">
          {{ metrics?.queuedCalls || 0 }}
        </div>
        <div class="metric-card-footer">
          Calls waiting for assignment
        </div>
      </div>

      <!-- Active Agents -->
      <div class="metric-card">
        <div class="metric-card-header">
          <div class="metric-card-label">Active Agents</div>
          <div class="metric-card-icon" style="background: var(--success-soft); color: var(--success);">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"></path><circle cx="9" cy="7" r="4"></circle><path d="M23 21v-2a4 4 0 0 0-3-3.87"></path><path d="M16 3.13a4 4 0 0 1 0 7.75"></path></svg>
          </div>
        </div>
        <div class="metric-card-value" style="color: var(--success);">{{ metrics?.activeAgents || 0 }}</div>
        <div class="metric-card-footer">
          Currently online
        </div>
      </div>

      <!-- Completed -->
      <div class="metric-card">
        <div class="metric-card-header">
          <div class="metric-card-label">Completed</div>
          <div class="metric-card-icon" style="background: #F0FDF4; color: #16A34A;">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"></path><polyline points="22 4 12 14.01 9 11.01"></polyline></svg>
          </div>
        </div>
        <div class="metric-card-value" style="color: var(--success);">{{ metrics?.completedCalls || 0 }}</div>
        <div class="metric-card-footer">
          <span class="metric-card-trend-up">✓</span>
          Successfully resolved
        </div>
      </div>
    </div>
  `
})
export class MetricsPanelComponent implements OnInit, OnDestroy {
  metrics: any = null;
  intervalId: any;
  loading = true;
  error = '';

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.fetchMetrics();
    this.intervalId = setInterval(() => this.fetchMetrics(), 5000);
  }

  ngOnDestroy() {
    if (this.intervalId) clearInterval(this.intervalId);
  }

  fetchMetrics() {
    const tenantId = this.api.tenantId;
    if (!tenantId) return;
    this.api.getMetrics(tenantId).subscribe({
      next: (res: any) => {
        this.metrics = res;
        this.loading = false;
        this.error = '';
      },
      error: (err: any) => {
        if (err.status === 401) {
          this.error = 'Session expired. Please log in as admin.';
        } else {
          this.error = 'Failed to load metrics.';
        }
        this.loading = false;
      }
    });
  }
}
