import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from './services/api.service';
import { SessionStateService } from './services/session-state.service';
import { AgentPanelComponent } from './components/agent-panel.component';
import { CallPanelComponent } from './components/call-panel.component';
import { MetricsPanelComponent } from './components/metrics-panel.component';
import { EventLogComponent } from './components/event-log.component';
import { RecentCallsComponent } from './components/recent-calls.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule, 
    AgentPanelComponent, 
    CallPanelComponent, 
    MetricsPanelComponent, 
    EventLogComponent,
    RecentCallsComponent
  ],
  template: `
    <div class="app-layout">
      <!-- Sidebar -->
      <aside class="app-sidebar">
        <div style="display: flex; align-items: center; gap: 12px; margin-bottom: 40px; padding: 0 16px;">
          <div style="width: 32px; height: 32px; background: var(--primary); border-radius: 8px; display: flex; align-items: center; justify-content: center;">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2.5"><path d="M22 12h-4l-3 9L9 3l-3 9H2"></path></svg>
          </div>
          <span style="font-size: 20px; font-weight: 800; letter-spacing: -0.02em;">MiniGenesys</span>
        </div>

        <nav style="flex: 1;">
          <div class="nav-link" [class.active]="view === 'supervisor'" (click)="view = 'supervisor'">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7"></rect><rect x="14" y="3" width="7" height="7"></rect><rect x="14" y="14" width="7" height="7"></rect><rect x="3" y="14" width="7" height="7"></rect></svg>
            Supervisor
          </div>
          <div class="nav-link" [class.active]="view === 'agent'" (click)="view = 'agent'">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg>
            Agent Workspace
          </div>
        </nav>

        <div style="padding: 16px; background: rgba(255,255,255,0.05); border-radius: var(--radius-lg);">
          <p style="font-size: 11px; color: #94A3B8; text-transform: uppercase; margin-bottom: 12px; letter-spacing: 0.05em;">System Status</p>
          <div style="display: flex; align-items: center; gap: 8px; font-size: 13px;">
            <div style="width: 8px; height: 8px; background: var(--success); border-radius: 50%;"></div>
            All Systems Operational
          </div>
        </div>
      </aside>

      <!-- Main Section -->
      <div class="app-main">
        <header class="app-header">
          <div style="display: flex; align-items: center; gap: 12px;">
            <span style="color: var(--text-muted); font-size: 14px; font-weight: 500;">Console</span>
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="var(--text-muted)" stroke-width="2"><polyline points="9 18 15 12 9 6"></polyline></svg>
            <span style="color: var(--text-main); font-size: 14px; font-weight: 600;">{{ view === 'supervisor' ? 'Monitoring' : 'Workspace' }}</span>
          </div>

          <div style="display: flex; align-items: center; gap: 20px;">
            <div *ngIf="!isLoggedIn" style="font-size: 13px; color: var(--warning); font-weight: 600;">⚠️ Session Access Restricted</div>
            <button class="btn" [class.btn-primary]="!isLoggedIn" [class.btn-outline]="isLoggedIn" (click)="isLoggedIn ? logout() : loginAsAdmin()">
              {{ isLoggedIn ? 'Logout' : 'Admin Login' }}
            </button>
            <div style="width: 36px; height: 36px; border-radius: 50%; background: #E2E8F0; display: flex; align-items: center; justify-content: center; font-weight: 700; color: #64748B;">AS</div>
          </div>
        </header>

        <div class="app-content">
          <div *ngIf="isLoggedIn">
            <!-- SUPERVISOR VIEW -->
            <div *ngIf="view === 'supervisor'">
              <app-metrics-panel></app-metrics-panel>
              <div class="grid grid-cols-2">
                <app-recent-calls></app-recent-calls>
                <div class="card">
                  <h2>Real-time Event Stream</h2>
                  <p style="margin-bottom: 20px;">Live monitoring of system-wide events</p>
                  <app-event-log></app-event-log>
                </div>
              </div>
            </div>

            <!-- AGENT VIEW -->
            <div *ngIf="view === 'agent'">
              <div class="grid grid-cols-2" style="margin-bottom: 24px;">
                <app-agent-panel></app-agent-panel>
                <app-call-panel></app-call-panel>
              </div>
              <div class="card">
                <h2>My Activity Log</h2>
                <p style="margin-bottom: 20px;">Filtered events for current agent session</p>
                <app-event-log [filterAgentId]="currentAgentId"></app-event-log>
              </div>
            </div>
          </div>

          <!-- UNAUTHENTICATED STATE -->
          <div *ngIf="!isLoggedIn" style="display: flex; flex-direction: column; align-items: center; justify-content: center; height: 60vh;">
            <div style="width: 64px; height: 64px; background: var(--warning-soft); border-radius: 50%; display: flex; align-items: center; justify-content: center; margin-bottom: 24px;">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="var(--warning)" stroke-width="2"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"></rect><path d="M7 11V7a5 5 0 0 1 10 0v4"></path></svg>
            </div>
            <h1>Authentication Required</h1>
            <p style="margin-top: 8px;">Please log in as an administrator to access the monitoring tools.</p>
            <button class="btn btn-primary" style="margin-top: 24px;" (click)="loginAsAdmin()">Login as Admin</button>
          </div>
        </div>
      </div>
    </div>
  `
})
export class AppComponent {
  isLoggedIn = false;
  view: 'supervisor' | 'agent' = 'agent';
  currentAgentId = 'agent-ui-1';

  constructor(private api: ApiService, private session: SessionStateService) {
    if (localStorage.getItem('token')) {
      this.isLoggedIn = true;
    }
    this.session.agent$.subscribe(state => this.currentAgentId = state.agentId);
  }

  loginAsAdmin() {
    this.api.login().subscribe({
      next: () => {
        this.isLoggedIn = true;
        window.location.reload();
      },
      error: (err) => console.error('Login failed', err)
    });
  }

  logout() {
    localStorage.removeItem('token');
    this.isLoggedIn = false;
    window.location.reload();
  }
}
