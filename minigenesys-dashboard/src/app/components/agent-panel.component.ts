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
      <div class="card-header">
        <div>
          <div class="card-title">Agent Session</div>
          <div class="card-subtitle">Manage your presence and availability</div>
        </div>
        <span class="status-badge" [ngClass]="getStatusClass()">
          <span class="status-dot" [ngClass]="getStatusDotClass()"></span>
          {{ status }}
        </span>
      </div>

      <!-- Agent Identity Card -->
      <div class="card-inner" style="margin-bottom: 20px;">
        <div style="display: flex; align-items: center; gap: 14px;">
          <div style="width: 44px; height: 44px; border-radius: 50%; background: var(--primary-gradient); display: flex; align-items: center; justify-content: center; color: white; font-weight: 700; font-size: 16px; box-shadow: 0 4px 12px rgba(59,130,246,0.25);">
            {{ agentId.substring(0, 2).toUpperCase() }}
          </div>
          <div>
            <div style="font-size: 15px; font-weight: 700; color: var(--text-main);">{{ agentId }}</div>
            <div style="font-size: 12px; color: var(--text-muted); font-weight: 500;">Agent Identity</div>
          </div>
        </div>
      </div>

      <!-- Session Metrics -->
      <div *ngIf="status !== 'Offline'" style="display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 20px;">
        <div class="card-inner" style="text-align: center; padding: 14px;">
          <div style="font-size: 11px; font-weight: 600; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.06em; margin-bottom: 4px;">Status</div>
          <div style="font-size: 16px; font-weight: 700;" [style.color]="status === 'Ready' ? 'var(--success)' : status === 'On Call' ? 'var(--warning)' : 'var(--neutral)'">{{ status }}</div>
        </div>
        <div class="card-inner" style="text-align: center; padding: 14px;">
          <div style="font-size: 11px; font-weight: 600; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.06em; margin-bottom: 4px;">Session</div>
          <div style="font-size: 16px; font-weight: 700; color: var(--primary);">Active</div>
        </div>
      </div>

      <!-- Action Buttons -->
      <div style="display: grid; grid-template-columns: 1fr; gap: 10px;">
        <button class="btn btn-primary" (click)="login()" *ngIf="status === 'Offline'" style="width: 100%; justify-content: center;">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4"></path><polyline points="10 17 15 12 10 7"></polyline><line x1="15" y1="12" x2="3" y2="12"></line></svg>
          Start Shift
        </button>
        
        <button class="btn btn-outline" (click)="setAvailable()" *ngIf="status !== 'Offline' && status !== 'Ready'" [disabled]="status === 'On Call'" style="width: 100%; justify-content: center;">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"></path><polyline points="22 4 12 14.01 9 11.01"></polyline></svg>
          Set Ready
        </button>

        <button class="btn btn-danger-outline" (click)="logout()" *ngIf="status !== 'Offline'" [disabled]="status === 'On Call'" style="width: 100%; justify-content: center;">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"></path><polyline points="16 17 21 12 16 7"></polyline><line x1="21" y1="12" x2="9" y2="12"></line></svg>
          End Shift
        </button>
      </div>

      <div *ngIf="errorMessage" class="alert alert-error" style="margin-top: 16px;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>
        {{ errorMessage }}
      </div>
    </div>
  `
})
export class AgentPanelComponent implements OnInit, OnDestroy {
  agentId = 'agent-ui-1';
  status = 'Offline';
  errorMessage = '';
  private sub?: Subscription;

  constructor(private api: ApiService, private session: SessionStateService) {}

  ngOnInit() {
    const savedAgentId = localStorage.getItem('agentId');
    if (savedAgentId) {
      this.agentId = savedAgentId;
      this.session.setAgentId(savedAgentId);
    }
    
    // Rehydrate from shared state on mount
    this.sub = this.session.agent$.subscribe(state => {
      this.status = state.status;
      if (state.agentId && state.agentId !== 'agent-ui-1') {
        this.agentId = state.agentId;
      }
    });
  }

  ngOnDestroy() {
    this.sub?.unsubscribe();
  }

  getStatusClass() {
    switch (this.status) {
      case 'Ready': return 'status-available';
      case 'On Call': return 'status-busy';
      default: return 'status-offline';
    }
  }

  getStatusDotClass() {
    switch (this.status) {
      case 'Ready': return 'available';
      case 'On Call': return 'busy';
      default: return 'offline';
    }
  }

  login() {
    this.errorMessage = '';
    const cleanId = this.agentId.trim();
    this.api.updateAgentStatus(cleanId, 'login').subscribe({
      next: (res: any) => {
        this.session.setAgentStatus(this.session.mapAgentStatus(res.status));
        this.session.startHeartbeat();
      },
      error: (err: any) => {
        if (err.status === 409) this.syncState(cleanId);
        else if (err.status === 401) this.errorMessage = 'Session expired. Please re-login as Admin.';
        else this.errorMessage = `Login failed: ${err.statusText || 'Connection error'}`;
      }
    });
  }

  setAvailable() {
    this.errorMessage = '';
    const cleanId = this.agentId.trim();
    this.api.updateAgentStatus(cleanId, 'available').subscribe({
      next: (res: any) => {
        this.session.setAgentStatus(this.session.mapAgentStatus(res.status));
        this.session.startHeartbeat();
      },
      error: (err: any) => {
        if (err.status === 409) this.syncState(cleanId);
        else this.errorMessage = `Status update failed`;
      }
    });
  }

  private syncState(agentId: string) {
    this.api.getAgentState(agentId).subscribe({
      next: (res: any) => {
        this.session.setAgentStatus(this.session.mapAgentStatus(res.status));
        this.session.startHeartbeat();
      },
      error: () => this.errorMessage = `State sync failed`
    });
  }

  logout() {
    this.errorMessage = '';
    const cleanId = this.agentId.trim();
    this.api.updateAgentStatus(cleanId, 'logout').subscribe({
      next: (res: any) => {
        this.session.setAgentStatus(this.session.mapAgentStatus(res.status));
        this.session.stopHeartbeat();
        this.session.clearCall();
      },
      error: (err: any) => this.errorMessage = `Logout failed`
    });
  }
}
