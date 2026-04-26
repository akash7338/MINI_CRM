import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../services/api.service';

@Component({
  selector: 'app-metrics-panel',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="panel">
      <h2>Metrics Panel (tenant1)</h2>
      
      <div *ngIf="loading && !metrics" style="color: #666;">Loading metrics...</div>
      
      <div *ngIf="error" style="color: red; margin-bottom: 10px;">
        {{ error }}
      </div>

      <div *ngIf="metrics" class="dashboard-grid">
        <div>
          <p><strong>Total Calls:</strong> {{ metrics.totalCalls }}</p>
          <p><strong>Queued Calls:</strong> {{ metrics.queuedCalls }}</p>
          <p><strong>Routed Calls:</strong> {{ metrics.routedCalls }}</p>
          <p><strong>Completed Calls:</strong> {{ metrics.completedCalls }}</p>
          <p><strong>Abandoned Calls:</strong> {{ metrics.abandonedCalls }}</p>
        </div>
        <div>
          <p><strong>Active Agents:</strong> {{ metrics.activeAgents }}</p>
          <p><strong>Busy Agents:</strong> {{ metrics.busyAgents }}</p>
          <p><strong>Offline Agents:</strong> {{ metrics.offlineAgents }}</p>
        </div>
      </div>
      <p style="font-size: 0.8rem; color: #666;" *ngIf="metrics">
        Last Updated: {{ metrics.updatedAt | date:'mediumTime' }}
      </p>
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
    this.intervalId = setInterval(() => this.fetchMetrics(), 5000); // Poll every 5s
  }

  ngOnDestroy() {
    if (this.intervalId) {
      clearInterval(this.intervalId);
    }
  }

  fetchMetrics() {
    this.api.getMetrics('tenant1').subscribe({
      next: (res) => {
        this.metrics = res;
        this.loading = false;
        this.error = '';
      },
      error: (err) => {
        this.error = 'Failed to load metrics.';
        this.loading = false;
      }
    });
  }
}

