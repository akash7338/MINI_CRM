import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../services/api.service';
import { SessionStateService } from '../services/session-state.service';
import { Subscription } from 'rxjs';

@Component({
  selector: 'app-agent-panel',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="card" style="height: 100%;">
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px;">
        <div>
          <h2 style="margin: 0;">Agent Session</h2>
          <p style="margin: 4px 0 0 0; font-size: 13px; color: var(--text-muted);">Manage your presence and availability</p>
        </div>
        <span class="status-badge" [ngClass]="getStatusClass()">
          {{ status }}
        </span>
      </div>

      <div style="margin-bottom: 24px;">
        <label style="display: block; font-size: 12px; font-weight: 700; color: var(--text-muted); margin-bottom: 8px; text-transform: uppercase;">Agent Identity</label>
        <div style="display: flex; gap: 12px;">
          <input type="text" [(ngModel)]="agentId" (ngModelChange)="onAgentIdChange()" placeholder="Enter Agent ID" [disabled]="status !== 'OFFLINE'">
          <button class="btn btn-primary" (click)="login()" [disabled]="status !== 'OFFLINE' || !agentId">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4"></path><polyline points="10 17 15 12 10 7"></polyline><line x1="15" y1="12" x2="3" y2="12"></line></svg>
            Sign In
          </button>
        </div>
      </div>

      <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 16px;">
        <button class="btn btn-outline" (click)="setAvailable()" [disabled]="status === 'OFFLINE' || status === 'AVAILABLE' || status === 'BUSY'">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"></path><polyline points="22 4 12 14.01 9 11.01"></polyline></svg>
          Go Available
        </button>
        <button class="btn btn-ghost" style="color: var(--danger);" (click)="logout()" [disabled]="status === 'OFFLINE'">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"></path><polyline points="16 17 21 12 16 7"></polyline><line x1="21" y1="12" x2="9" y2="12"></line></svg>
          Logout
        </button>
      </div>

      <div *ngIf="errorMessage" style="margin-top: 20px; padding: 12px; background: var(--danger-soft); color: var(--danger); border-radius: var(--radius-md); font-size: 13px; font-weight: 600; display: flex; gap: 8px; align-items: center;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>
        {{ errorMessage }}
      </div>
    </div>
  `
})
export class AgentPanelComponent implements OnInit, OnDestroy {
  agentId = 'agent-ui-1';
  status = 'OFFLINE';
  errorMessage = '';
  private sub?: Subscription;

  constructor(private api: ApiService, private session: SessionStateService) {}

  ngOnInit() {
    // Rehydrate from shared state on mount
    this.sub = this.session.agent$.subscribe(state => {
      this.agentId = state.agentId;
      this.status = state.status;
    });
  }

  ngOnDestroy() {
    this.sub?.unsubscribe();
    // Do NOT stop heartbeat here — SessionStateService owns it
  }

  onAgentIdChange() {
    this.session.setAgentId(this.agentId);
  }

  getStatusClass() {
    switch (this.status) {
      case 'AVAILABLE': return 'status-available';
      case 'BUSY': return 'status-busy';
      default: return 'status-offline';
    }
  }

  login() {
    this.errorMessage = '';
    const cleanId = this.agentId.trim();
    this.api.loginAgent(cleanId).subscribe({
      next: (res) => {
        this.session.setAgentStatus(res.status);
        this.session.startHeartbeat();
      },
      error: (err) => {
        if (err.status === 409) this.syncState(cleanId);
        else if (err.status === 401) this.errorMessage = 'Session expired. Please re-login as Admin.';
        else this.errorMessage = `Login failed: ${err.statusText || 'Connection error'}`;
      }
    });
  }

  setAvailable() {
    this.errorMessage = '';
    const cleanId = this.agentId.trim();
    this.api.setAgentState(cleanId, 'AVAILABLE').subscribe({
      next: (res) => {
        this.session.setAgentStatus(res.status);
        this.session.startHeartbeat();
      },
      error: (err) => {
        if (err.status === 409) this.syncState(cleanId);
        else this.errorMessage = `Status update failed`;
      }
    });
  }

  private syncState(agentId: string) {
    this.api.getAgentState(agentId).subscribe({
      next: (res) => {
        this.session.setAgentStatus(res.status);
        this.session.startHeartbeat();
      },
      error: () => this.errorMessage = `State sync failed`
    });
  }

  logout() {
    this.errorMessage = '';
    const cleanId = this.agentId.trim();
    this.api.logoutAgent(cleanId).subscribe({
      next: (res) => {
        this.session.setAgentStatus(res.status);
        this.session.stopHeartbeat();
        this.session.clearCall();
      },
      error: (err) => this.errorMessage = `Logout failed`
    });
  }
}
