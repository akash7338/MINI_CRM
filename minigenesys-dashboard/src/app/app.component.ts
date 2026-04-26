import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from './services/api.service';
import { AgentPanelComponent } from './components/agent-panel.component';
import { CallPanelComponent } from './components/call-panel.component';
import { MetricsPanelComponent } from './components/metrics-panel.component';
import { EventLogComponent } from './components/event-log.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule, 
    AgentPanelComponent, 
    CallPanelComponent, 
    MetricsPanelComponent, 
    EventLogComponent
  ],
  template: `
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;">
      <h1 style="color: #333; margin: 0;">Mini Genesys Dashboard</h1>
      <button (click)="loginAsAdmin()" [disabled]="isLoggedIn" style="margin: 0; background-color: #28a745;">
        {{ isLoggedIn ? 'Authenticated' : 'Login as Admin' }}
      </button>
    </div>

    <div *ngIf="!isLoggedIn" style="background: #fff3cd; color: #856404; padding: 15px; border-radius: 4px; border: 1px solid #ffeeba; margin-bottom: 20px;">
      <strong>Notice:</strong> Please log in to access the backend APIs.
    </div>

    <div *ngIf="isLoggedIn">
      <div class="dashboard-grid">
        <app-agent-panel></app-agent-panel>
        <app-call-panel></app-call-panel>
      </div>
      
      <app-metrics-panel></app-metrics-panel>
      <app-event-log></app-event-log>
    </div>
  `
})
export class AppComponent {
  isLoggedIn = false;

  constructor(private api: ApiService) {
    if (localStorage.getItem('token')) {
      this.isLoggedIn = true;
    }
  }

  loginAsAdmin() {
    this.api.login().subscribe({
      next: () => this.isLoggedIn = true,
      error: (err) => console.error('Login failed', err)
    });
  }
}
