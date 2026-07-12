import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from './services/api.service';
import { SessionStateService } from './services/session-state.service';
import { WebsocketService } from './services/websocket.service';
import { AgentPanelComponent } from './components/agent-panel.component';
import { CallPanelComponent } from './components/call-panel.component';
import { MetricsPanelComponent } from './components/metrics-panel.component';
import { EventLogComponent } from './components/event-log.component';
import { RecentCallsComponent } from './components/recent-calls.component';
import { LoginComponent } from './components/login.component';
import { CreateAgentComponent } from './components/create-agent.component';
import { TelephonyOverlayComponent } from './components/telephony/telephony-overlay.component';
import { DiagnosticsComponent } from './components/diagnostics.component';
import { DialpadComponent } from './components/dialpad/dialpad.component';
import { ContactsComponent } from './components/contacts/contacts.component';
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
    TelephonyOverlayComponent,
    DiagnosticsComponent,
    DialpadComponent,
    ContactsComponent
  ],
  template: `
    <!-- UNAUTHENTICATED: Public diagnostics or login -->
    <div *ngIf="!isLoggedIn">
      <div style="position: fixed; top: 16px; right: 16px; z-index: 120; display: flex; gap: 8px;">
        <button class="btn btn-outline btn-sm" (click)="publicView = 'diagnostics'">Public Diagnostics</button>
        <button class="btn btn-outline btn-sm" (click)="publicView = 'login'">Login</button>
      </div>
      <div *ngIf="publicView === 'diagnostics'" class="app-layout">
        <div class="app-main" style="margin-left: 0; width: 100%;">
          <header class="app-header">
            <div class="header-breadcrumb">
              <span class="header-breadcrumb-base">Console</span>
              <svg class="header-breadcrumb-separator" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 18 15 12 9 6"></polyline></svg>
              <span class="header-breadcrumb-current">Public Diagnostics</span>
            </div>
          </header>
          <div class="app-content">
            <div class="section-header">
              <div>
                <div class="section-title">System Diagnostics</div>
                <div class="section-subtitle">No login required — health, SIP/NAT, logs, and service controls.</div>
              </div>
            </div>
            <div class="card">
              <app-diagnostics></app-diagnostics>
            </div>
          </div>
        </div>
      </div>
      <div *ngIf="publicView === 'login'">
        <!-- Session-expired / force-logout banner -->
        <div *ngIf="kickMessage" style="
          position: fixed; top: 0; left: 0; right: 0; z-index: 200;
          background: #fef3c7; border-bottom: 2px solid #f59e0b;
          padding: 12px 24px; display: flex; align-items: center; gap: 12px;
          font-size: 14px; color: #92400e; font-weight: 500;">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path>
            <line x1="12" y1="9" x2="12" y2="13"></line><line x1="12" y1="17" x2="12.01" y2="17"></line>
          </svg>
          {{ kickMessage }}
          <button style="margin-left: auto; background: none; border: none; cursor: pointer; color: #92400e; font-size: 18px; line-height: 1;" (click)="kickMessage = ''">✕</button>
        </div>
        <app-login [style.marginTop]="kickMessage ? '52px' : '0'"></app-login>
      </div>
    </div>

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

            <div class="sidebar-section-label" style="margin-top: 12px;">System</div>
            <div class="nav-link" [class.active]="view === 'diagnostics'" (click)="view = 'diagnostics'">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 12h-4l-3 9L9 3l-3 9H2"></path></svg>
              Diagnostics
            </div>
          </ng-container>

          <!-- Agent Sidebar -->
          <ng-container *ngIf="role === 'AGENT'">
            <div class="sidebar-section-label">Workspace</div>
            <div class="nav-link" [class.active]="view === 'workspace'" (click)="view = 'workspace'">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg>
              My Workspace
            </div>
            <div class="nav-link" [class.active]="view === 'contacts'" (click)="view = 'contacts'">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"></path><circle cx="9" cy="7" r="4"></circle><path d="M23 21v-2a4 4 0 0 0-3-3.87"></path><path d="M16 3.13a4 4 0 0 1 0 7.75"></path></svg>
              Contacts
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

              <div *ngIf="view === 'diagnostics'" class="animate-fade-in">
                <div class="section-header">
                  <div>
                    <div class="section-title">System Diagnostics</div>
                    <div class="section-subtitle">Infrastructure health, SIP/NAT status, and service logs</div>
                  </div>
                  <div class="live-indicator">
                    <div class="live-indicator-dot"></div>
                    LIVE
                  </div>
                </div>
                <div class="card">
                  <app-diagnostics></app-diagnostics>
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
                <div class="grid grid-cols-2" style="margin-bottom: 20px; gap: 20px; align-items: start;">
                  <app-agent-panel></app-agent-panel>
                  <app-call-panel></app-call-panel>
                </div>
              </div>

              <div *ngIf="view === 'contacts'" class="animate-fade-in">
                <div class="section-header">
                  <div>
                    <div class="section-title">Contacts</div>
                    <div class="section-subtitle">Manage contacts and quick-dial outbound numbers</div>
                  </div>
                </div>
                <app-contacts></app-contacts>
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

      <!-- Floating Action Button for Dialpad (visible only for agents) -->
      <button *ngIf="role === 'AGENT'" class="dialpad-fab" (click)="showDialpad = true" title="Open Dialpad">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"></path>
        </svg>
      </button>

      <!-- Dialpad Modal -->
      <app-dialpad *ngIf="showDialpad && role === 'AGENT'" (close)="showDialpad = false"></app-dialpad>
    </div>
  `,
  styles: [`
    .dialpad-fab {
      position: fixed;
      bottom: 32px;
      right: 32px;
      width: 60px;
      height: 60px;
      border-radius: 50%;
      background: linear-gradient(135deg, #4caf50 0%, #45a049 100%);
      border: none;
      box-shadow: 0 4px 16px rgba(76, 175, 80, 0.4), 0 8px 32px rgba(76, 175, 80, 0.2);
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      transition: all 0.3s ease;
      z-index: 900;
      color: white;
    }

    .dialpad-fab:hover {
      transform: scale(1.1) translateY(-2px);
      box-shadow: 0 6px 20px rgba(76, 175, 80, 0.5), 0 12px 40px rgba(76, 175, 80, 0.3);
    }

    .dialpad-fab:active {
      transform: scale(1.05);
    }

    .dialpad-fab svg {
      filter: drop-shadow(0 2px 4px rgba(0, 0, 0, 0.2));
    }
  `]
})
export class AppComponent {
  isLoggedIn = false;
  publicView: 'diagnostics' | 'login' = 'login';
  role = '';
  view = 'overview';
  currentAgentId = '';
  /** Shown as a banner above the login form after a forced kick. */
  kickMessage = '';
  private loggingOut = false;
  showDialpad = false;

  constructor(
    private api: ApiService,
    private session: SessionStateService,
    private ws: WebsocketService,
    private telephony: TelephonyService,
    private freeswitchWebRtc: FreeswitchWebRtcService
  ) {
    // -----------------------------------------------------------------------
    // Spec §5.1 — Tab initialization
    // -----------------------------------------------------------------------

    // If the user was force-logged-out in a previous session (by the kick mechanism),
    // localStorage['reason'] will be set. We show the message on the login screen and
    // do NOT try to auto-reconnect (spec: only auto-reconnect if within reconnect window
    // and no prior kick indication).
    const kickReason = sessionStorage.getItem('reason');
    if (kickReason) {
      this.kickMessage = kickReason === 'LOGOUT_AGENT'
        ? 'Your session was taken over by another tab. Please sign in again.'
        : 'Your session has expired. Please sign in again.';
      this.setupSubscriptions();
      return;
    }

    const token = localStorage.getItem('token');
    const role  = localStorage.getItem('role');

    if (token && role) {
      this.role = role;
      this.view = role === 'SUPERVISOR' ? 'overview' : 'workspace';
      this.isLoggedIn = true;
      const agentId = localStorage.getItem('agentId');
      if (agentId) this.api.setAgentId(agentId);

      this.api.activateSession().subscribe({
        next: () => {
          console.log('[Session] Activated — this tab now owns the session.');
          this.subscribeToUserChannel();
          this.restoreAgentState();
          this.initializeTelephony(localStorage.getItem('agentId') || '');
        },
        error: (err) => {
          console.warn('[Session] activate failed:', err);
          this.finalizeLogout();
        }
      });
    }

    this.setupSubscriptions();
  }

  /** Wire up the streams that are active for the entire app lifetime. */
  private setupSubscriptions() {
    this.session.agent$.subscribe(state => this.currentAgentId = state.agentId);

    // HTTP fallback path (spec §5.2 Path B): 403 TOKEN_EXPIRED caught by
    // forceLogoutInterceptor in main.ts → notifyForceLogout() → sessionRevoked$ emits.
    this.api.sessionRevoked$.subscribe(() => {
      console.warn('[Session] Force-logout triggered — showing login with reason.');
      this.session.stopHeartbeat();
      this.freeswitchWebRtc.stop();
      this.kickMessage = sessionStorage.getItem('reason') === 'LOGOUT_AGENT'
        ? 'Your session was taken over by another tab. Please sign in again.'
        : 'Your session has expired. Please sign in again.';
      this.isLoggedIn = false;
    });

    // WebSocket push path (spec §5.2 Path A): LogoutNotification from
    // /topic/{tenantId}/user/{userId} arrives near-instantly after the new tab's
    // /activate completes on the server.
    // IMPORTANT: compare kickedJti with this tab's current token jti.
    // If they differ, this tab is the NEW winner and must ignore the notification —
    // the notification was meant for the old tab that got kicked.
    this.ws.userEvents$.subscribe((event: any) => {
      if (event?.type === 'LogoutNotification') {
        const myJti = this.getJtiFromToken();
        const kickedJti = event.kickedJti as string | undefined;
        if (kickedJti && myJti && kickedJti !== myJti) {
          console.log('[Session] LogoutNotification ignored — kickedJti does not match my token (I am the winner).');
          return;
        }
        const reason = event.reason || 'LOGOUT_AGENT';
        console.warn('[Session] WebSocket LogoutNotification accepted — kicking this tab. reason:', reason);
        this.api.notifyForceLogout(reason);
      }
    });

    // Successful credential-based login from LoginComponent.
    this.api.loginSuccess$.subscribe(success => {
      if (success) {
        this.kickMessage = '';
        this.isLoggedIn = true;
        this.role = localStorage.getItem('role') || '';
        this.view = this.role === 'SUPERVISOR' ? 'overview' : 'workspace';
        this.ws.connect();
        this.subscribeToUserChannel();
        this.restoreAgentState();
        this.initializeTelephony(localStorage.getItem('agentId') || '');
      }
    });

    this.api.agentId$.subscribe(agentId => {
      if (agentId && this.isLoggedIn) this.initializeTelephony(agentId);
    });
  }

  /** Extract userId from the active in-memory JWT (safe client-side decode, no verify). */
  private getUserIdFromToken(): string {
    return this.decodeTokenPayload()?.['sub'] || '';
  }

  /** Extract jti from the active in-memory JWT — used to filter LogoutNotifications. */
  private getJtiFromToken(): string {
    return this.decodeTokenPayload()?.['jti'] || '';
  }

  private decodeTokenPayload(): Record<string, any> | null {
    const token = this.api.getTokenForRequest() || localStorage.getItem('token');
    if (!token) return null;
    try {
      return JSON.parse(atob(token.split('.')[1]));
    } catch {
      return null;
    }
  }

  /**
   * Load agent state and subscribe to tenant events AFTER the session is
   * established (_token is set). Previously this ran in the SessionStateService
   * constructor which raced with /activate — HTTP requests carrying the old
   * token would hit the blacklist and 403 this tab out of its own session.
   */
  private restoreAgentState() {
    const agentId = localStorage.getItem('agentId');
    if (agentId) {
      this.session.loadInitialState(agentId);
      this.ws.subscribeToTenantEvents();
    }
  }

  /** Subscribe to the user's personal STOMP topic for logout notifications. */
  private subscribeToUserChannel() {
    const userId = this.getUserIdFromToken();
    if (userId) {
      this.ws.subscribeToUserChannel(userId);
    }
  }

  private initializeTelephony(agentId: string) {
    const role = this.role || localStorage.getItem('role') || '';
    if (!agentId || role !== 'AGENT') return;

    const provider = localStorage.getItem('telephonyProvider') || 'TWILIO';
    if (provider === 'TWILIO') {
      console.log('[Telephony] Initializing Twilio voice for agent:', agentId);
      this.telephony.initialize(agentId);
    } else if (provider === 'FREESWITCH') {
      console.log('[Telephony] Initializing FreeSWITCH WebRTC voice for agent:', agentId);
      this.freeswitchWebRtc.initialize(agentId);
    }
  }

  getViewTitle(): string {
    const titles: Record<string, string> = {
      'overview': 'Dashboard',
      'agents': 'Agent Management',
      'calls': 'Call History',
      'audit': 'Audit Trail',
      'diagnostics': 'Diagnostics',
      'workspace': 'Workspace',
      'contacts': 'Contacts',
      'activity': 'Activity Log'
    };
    return titles[this.view] || this.view;
  }

  logout() {
    if (this.loggingOut) return;
    this.loggingOut = true;

    const agentId = localStorage.getItem('agentId');
    const role    = localStorage.getItem('role');

    if (role === 'AGENT' && agentId) {
      this.api.updateAgentStatus(agentId, 'logout').subscribe({
        next:  () => this.finalizeLogout(),
        error: () => this.finalizeLogout()
      });
    } else {
      this.finalizeLogout();
    }
  }

  private finalizeLogout() {
    this.freeswitchWebRtc.stop();
    this.api.logout();
    this.kickMessage = '';
    this.isLoggedIn = false;
    this.loggingOut = false;
  }
}
