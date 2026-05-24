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
import { FreeswitchWebRtcService } from './services/freeswitch-webrtc.service';

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
    <!-- UNAUTHENTICATED: Full-screen login -->
    <app-login *ngIf="!isLoggedIn"></app-login>

    <!-- AUTHENTICATED: Full app layout -->
    <div class="app-layout" *ngIf="isLoggedIn">
      <app-telephony-overlay></app-telephony-overlay>

      <!-- ========== SIDEBAR ========== -->
      <aside class="app-sidebar">
        <div class="sidebar-brand">
          <div class="sidebar-brand-icon">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2.5"><path d="M22 12h-4l-3 9L9 3l-3 9H2"></path></svg>
          </div>
          <span class="sidebar-brand-text">MiniGenesys</span>
        </div>

        <nav style="flex: 1;" *ngIf="isLoggedIn">
          <!-- Supervisor Sidebar -->
          <ng-container *ngIf="role === 'SUPERVISOR'">
            <div class="sidebar-section-label">Operations</div>
            <div class="nav-link" [class.active]="view === 'overview'" (click)="view = 'overview'">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7"></rect><rect x="14" y="3" width="7" height="7"></rect><rect x="14" y="14" width="7" height="7"></rect><rect x="3" y="14" width="7" height="7"></rect></svg>
              Dashboard
            </div>
            <div class="nav-link" [class.active]="view === 'agents'" (click)="view = 'agents'">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"></path><circle cx="9" cy="7" r="4"></circle><path d="M23 21v-2a4 4 0 0 0-3-3.87"></path><path d="M16 3.13a4 4 0 0 1 0 7.75"></path></svg>
              Agent Management
            </div>
            <div class="nav-link" [class.active]="view === 'calls'" (click)="view = 'calls'">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"></path></svg>
              Call History
            </div>
            <div class="nav-link" [class.active]="view === 'audit'" (click)="view = 'audit'">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line></svg>
              Audit Trail
            </div>
          </ng-container>

          <!-- Agent Sidebar -->
          <ng-container *ngIf="role === 'AGENT'">
            <div class="sidebar-section-label">Workspace</div>
            <div class="nav-link" [class.active]="view === 'workspace'" (click)="view = 'workspace'">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg>
              My Workspace
            </div>
            <div class="nav-link" [class.active]="view === 'activity'" (click)="view = 'activity'">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 12h-4l-3 9L9 3l-3 9H2"></path></svg>
              Activity Log
            </div>
          </ng-container>
        </nav>

        <div class="sidebar-footer">
          <div class="sidebar-footer-label">System Status</div>
          <div class="sidebar-footer-status">
            <div class="sidebar-footer-dot"></div>
            All Systems Operational
          </div>
        </div>
      </aside>

      <!-- ========== MAIN ========== -->
      <div class="app-main">
        <header class="app-header">
          <div class="header-breadcrumb">
            <span class="header-breadcrumb-base">Console</span>
            <svg class="header-breadcrumb-separator" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 18 15 12 9 6"></polyline></svg>
            <span class="header-breadcrumb-current">{{ getViewTitle() }}</span>
          </div>

          <div class="header-actions">
            <button *ngIf="isLoggedIn" class="btn btn-outline btn-sm" (click)="logout()">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"></path><polyline points="16 17 21 12 16 7"></polyline><line x1="21" y1="12" x2="9" y2="12"></line></svg>
              Logout
            </button>
            <div *ngIf="isLoggedIn" class="header-avatar">
              {{ role === 'SUPERVISOR' ? 'SU' : 'AG' }}
            </div>
          </div>
        </header>

        <div class="app-content">
          <div *ngIf="isLoggedIn">
            <!-- SUPERVISOR VIEWS -->
            <ng-container *ngIf="role === 'SUPERVISOR'">
              <div *ngIf="view === 'overview'" class="animate-fade-in">
                <div class="section-header">
                  <div>
                    <div class="section-title">Dashboard Overview</div>
                    <div class="section-subtitle">Real-time performance and system metrics</div>
                  </div>
                  <div class="live-indicator">
                    <div class="live-indicator-dot"></div>
                    LIVE
                  </div>
                </div>
                <app-metrics-panel></app-metrics-panel>
                <div class="card" style="margin-top: 20px;">
                  <div class="card-header">
                    <div>
                      <div class="card-title">Event Stream</div>
                      <div class="card-subtitle">Live monitoring of system-wide events</div>
                    </div>
                  </div>
                  <app-event-log></app-event-log>
                </div>
              </div>

              <div *ngIf="view === 'agents'" class="animate-fade-in">
                <div class="section-header">
                  <div>
                    <div class="section-title">Agent Management</div>
                    <div class="section-subtitle">Monitor and manage agent availability and skills</div>
                  </div>
                </div>
                <app-create-agent></app-create-agent>
              </div>

              <div *ngIf="view === 'calls'" class="animate-fade-in">
                <div class="section-header">
                  <div>
                    <div class="section-title">Call History</div>
                    <div class="section-subtitle">Review and analyze past interactions across all queues</div>
                  </div>
                </div>
                <app-recent-calls></app-recent-calls>
              </div>

              <div *ngIf="view === 'audit'" class="animate-fade-in">
                <div class="section-header">
                  <div>
                    <div class="section-title">Audit Trail</div>
                    <div class="section-subtitle">Historical audit logs and compliance records</div>
                  </div>
                </div>
                <div class="card">
                  <div class="empty-state">
                    <div class="empty-state-icon">
                      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline></svg>
                    </div>
                    <div class="empty-state-text">Audit log viewer coming soon</div>
                  </div>
                </div>
              </div>
            </ng-container>

            <!-- AGENT VIEWS -->
            <ng-container *ngIf="role === 'AGENT'">
              <div *ngIf="view === 'workspace'" class="animate-fade-in">
                <div class="section-header">
                  <div>
                    <div class="section-title">Agent Workspace</div>
                    <div class="section-subtitle">Manage your presence and handle interactions</div>
                  </div>
                </div>
                <div class="grid grid-cols-2" style="margin-bottom: 20px;">
                  <app-agent-panel></app-agent-panel>
                  <app-call-panel></app-call-panel>
                </div>
              </div>

              <div *ngIf="view === 'activity'" class="animate-fade-in">
                <div class="section-header">
                  <div>
                    <div class="section-title">Activity Log</div>
                    <div class="section-subtitle">Filtered events for your current session</div>
                  </div>
                </div>
                <div class="card">
                  <app-event-log [filterAgentId]="currentAgentId"></app-event-log>
                </div>
              </div>
            </ng-container>
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

  constructor(
    private api: ApiService, 
    private session: SessionStateService, 
    private telephony: TelephonyService,
    private freeswitchWebRtc: FreeswitchWebRtcService
  ) {
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
        const provider = localStorage.getItem('telephonyProvider') || 'TWILIO';
        if (provider === 'TWILIO') {
          console.log('[Telephony] Initializing Twilio voice for agent:', agentId);
          this.telephony.initialize(agentId);
        } else if (provider === 'FREESWITCH') {
          console.log('[Telephony] Initializing FreeSWITCH WebRTC voice for agent:', agentId);
          this.freeswitchWebRtc.initialize(agentId);
        }
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

  getViewTitle(): string {
    const titles: Record<string, string> = {
      'overview': 'Dashboard',
      'agents': 'Agent Management',
      'calls': 'Call History',
      'audit': 'Audit Trail',
      'workspace': 'Workspace',
      'activity': 'Activity Log'
    };
    return titles[this.view] || this.view;
  }

  logout() {
    if (this.loggingOut) return;
    this.loggingOut = true;

    const agentId = localStorage.getItem('agentId');
    const role = localStorage.getItem('role');
    
    if (role === 'AGENT' && agentId) {
      this.api.updateAgentStatus(agentId, 'logout').subscribe({
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
