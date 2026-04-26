import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../services/api.service';

@Component({
  selector: 'app-metrics-panel',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div style="margin-bottom: 32px;">
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;">
        <h2 style="margin: 0;">Service Performance</h2>
        <div style="display: flex; gap: 8px; align-items: center;">
          <span style="width: 8px; height: 8px; background: var(--primary); border-radius: 50%;"></span>
          <span style="font-size: 12px; font-weight: 600; color: var(--text-muted);">Real-time Refresh Enabled</span>
        </div>
      </div>

      <div class="grid grid-cols-4">
        <!-- Calls KPI -->
        <div class="card">
          <div style="display: flex; justify-content: space-between;">
            <div class="kpi-label">Total Volume</div>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="var(--primary)" stroke-width="2"><path d="M22 12h-4l-3 9L9 3l-3 9H2"></path></svg>
          </div>
          <div class="kpi-value">{{ metrics?.totalCalls || 0 }}</div>
          <div style="margin-top: 8px; font-size: 12px; color: var(--success); font-weight: 600;">+12% from last hour</div>
        </div>

        <div class="card">
          <div class="kpi-label">Current Queue</div>
          <div class="kpi-value" [style.color]="(metrics?.queuedCalls || 0) > 5 ? 'var(--danger)' : 'var(--warning)'">
            {{ metrics?.queuedCalls || 0 }}
          </div>
          <div style="margin-top: 8px; font-size: 12px; color: var(--text-muted);">Avg. wait: 2m 14s</div>
        </div>

        <div class="card">
          <div class="kpi-label">Active Agents</div>
          <div class="kpi-value">{{ metrics?.activeAgents || 0 }}</div>
          <div style="margin-top: 8px; font-size: 12px; color: var(--text-muted);">Capacity: 84%</div>
        </div>

        <div class="card">
          <div class="kpi-label">Completed</div>
          <div class="kpi-value" style="color: var(--success);">{{ metrics?.completedCalls || 0 }}</div>
          <div style="margin-top: 8px; font-size: 12px; color: var(--success); font-weight: 600;">98% CSAT Score</div>
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
    this.api.getMetrics('tenant1').subscribe({
      next: (res) => {
        this.metrics = res;
        this.loading = false;
        this.error = '';
      },
      error: (err) => {
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


