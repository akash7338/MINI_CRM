import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from './services/api.service';
import { SessionStateService } from './services/session-state.service';
import { AgentPanelComponent } from './components/agent-panel.component';
import { CallPanelComponent } from './components/call-panel.component';
import { MetricsPanelComponent } from './components/metrics-panel.component';
import { EventLogComponent } from './components/event-log.component';
import { RecentCallsComponent } from './components/recent-calls.component';
import { LoginComponent } from './components/login.component';
import { CreateAgentComponent } from './components/create-agent.component';
import { TelephonyOverlayComponent } from './components/telephony/telephony-overlay.component';
import { TelephonyService } from './services/telephony.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule, 
    AgentPanelComponent, 
    CallPanelComponent, 
    MetricsPanelComponent, 
    EventLogComponent,
    RecentCallsComponent,
    LoginComponent,
    CreateAgentComponent,
    TelephonyOverlayComponent
  ],
  template: `
    <div class="app-layout">
      <!-- ... existing template ... -->
      <app-telephony-overlay></app-telephony-overlay>
      <aside class="app-sidebar">
        <div style="display: flex; align-items: center; gap: 12px; margin-bottom: 40px; padding: 0 16px;">
          <div style="width: 32px; height: 32px; background: var(--primary); border-radius: 8px; display: flex; align-items: center; justify-content: center;">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2.5"><path d="M22 12h-4l-3 9L9 3l-3 9H2"></path></svg>
          </div>
          <span style="font-size: 20px; font-weight: 800; letter-spacing: -0.02em;">MiniGenesys</span>
        </div>

        <nav style="flex: 1;" *ngIf="isLoggedIn">
          <!-- Supervisor Sidebar -->
          <ng-container *ngIf="role === 'SUPERVISOR'">
            <div class="nav-link" [class.active]="view === 'overview'" (click)="view = 'overview'">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7"></rect><rect x="14" y="3" width="7" height="7"></rect><rect x="14" y="14" width="7" height="7"></rect><rect x="3" y="14" width="7" height="7"></rect></svg>
              Overview
            </div>
            <div class="nav-link" [class.active]="view === 'agents'" (click)="view = 'agents'">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"></path><circle cx="9" cy="7" r="4"></circle><path d="M23 21v-2a4 4 0 0 0-3-3.87"></path><path d="M16 3.13a4 4 0 0 1 0 7.75"></path></svg>
              Agents
            </div>
            <div class="nav-link" [class.active]="view === 'calls'" (click)="view = 'calls'">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"></path></svg>
              Calls
            </div>
            <div class="nav-link" [class.active]="view === 'audit'" (click)="view = 'audit'">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><polyline points="10 9 9 9 8 9"></polyline></svg>
              Audit Trail
            </div>
          </ng-container>

          <!-- Agent Sidebar -->
          <ng-container *ngIf="role === 'AGENT'">
            <div class="nav-link" [class.active]="view === 'workspace'" (click)="view = 'workspace'">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg>
              Workspace
            </div>
            <div class="nav-link" [class.active]="view === 'activity'" (click)="view = 'activity'">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 12h-4l-3 9L9 3l-3 9H2"></path></svg>
              My Activity
            </div>
          </ng-container>
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
            <span style="color: var(--text-main); font-size: 14px; font-weight: 600; text-transform: capitalize;">{{ view }}</span>
          </div>

          <div style="display: flex; align-items: center; gap: 20px;">
            <button *ngIf="isLoggedIn" class="btn btn-outline" (click)="logout()">Logout</button>
            <div *ngIf="isLoggedIn" style="width: 36px; height: 36px; border-radius: 50%; background: #E2E8F0; display: flex; align-items: center; justify-content: center; font-weight: 700; color: #64748B;">
              {{ role === 'SUPERVISOR' ? 'SU' : 'AG' }}
            </div>
          </div>
        </header>

        <div class="app-content">
          <div *ngIf="isLoggedIn">
            <!-- SUPERVISOR VIEWS -->
            <ng-container *ngIf="role === 'SUPERVISOR'">
              <div *ngIf="view === 'overview'">
                <app-metrics-panel></app-metrics-panel>
                <div class="grid grid-cols-2">
                  <div class="card">
                    <h2>Real-time Event Stream</h2>
                    <p style="margin-bottom: 20px;">Live monitoring of system-wide events</p>
                    <app-event-log></app-event-log>
                  </div>
                </div>
              </div>
              <div *ngIf="view === 'agents'">
                <app-create-agent></app-create-agent>
              </div>
              <div *ngIf="view === 'calls'">
                <app-recent-calls></app-recent-calls>
              </div>
              <div *ngIf="view === 'audit'">
                <div class="card">
                  <h2>Audit Trail</h2>
                  <p>Historical audit logs will appear here.</p>
                </div>
              </div>
            </ng-container>

            <!-- AGENT VIEWS -->
            <ng-container *ngIf="role === 'AGENT'">
              <div *ngIf="view === 'workspace'">
                <div class="grid grid-cols-2" style="margin-bottom: 24px;">
                  <app-agent-panel></app-agent-panel>
                  <app-call-panel></app-call-panel>
                </div>
              </div>
              <div *ngIf="view === 'activity'">
                <div class="card">
                  <h2>My Activity Log</h2>
                  <p style="margin-bottom: 20px;">Filtered events for current agent session</p>
                  <app-event-log [filterAgentId]="currentAgentId"></app-event-log>
                </div>
              </div>
            </ng-container>
          </div>

          <!-- UNAUTHENTICATED STATE -->
          <div *ngIf="!isLoggedIn">
            <app-login></app-login>
          </div>
        </div>
      </div>
    </div>
  `
})
export class AppComponent {
  isLoggedIn = false;
  role = '';
  view = 'overview';
  currentAgentId = '';
  private loggingOut = false;

  constructor(private api: ApiService, private session: SessionStateService, private telephony: TelephonyService) {
    const token = localStorage.getItem('token');
    const role = localStorage.getItem('role');
    
    if (token && role) {
      this.isLoggedIn = true;
      this.role = role;
      this.view = role === 'SUPERVISOR' ? 'overview' : 'workspace';
      
      const agentId = localStorage.getItem('agentId');
      if (agentId) {
        this.api.setAgentId(agentId);
      }
    }

    this.session.agent$.subscribe(state => this.currentAgentId = state.agentId);

    // Reactively initialize telephony when agent ID is set (e.g. after login)
    this.api.agentId$.subscribe(agentId => {
      if (agentId && this.role === 'AGENT') {
        this.telephony.initialize(agentId);
      }
    });

    // Handle successful login from LoginComponent
    this.api.loginSuccess$.subscribe(success => {
      if (success) {
        this.isLoggedIn = true;
        this.role = localStorage.getItem('role') || '';
        this.view = this.role === 'SUPERVISOR' ? 'overview' : 'workspace';
      }
    });
  }

  logout() {
    if (this.loggingOut) return;
    this.loggingOut = true;

    const agentId = localStorage.getItem('agentId');
    const role = localStorage.getItem('role');
    
    if (role === 'AGENT' && agentId) {
      this.api.logoutAgent(agentId).subscribe({
        next: () => this.finalizeLogout(),
        error: () => this.finalizeLogout()
      });
    } else {
      this.finalizeLogout();
    }
  }

  private finalizeLogout() {
    this.api.logout();
    this.isLoggedIn = false;
    this.loggingOut = false;
  }
}
