import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../services/api.service';
import { SessionStateService } from '../services/session-state.service';
import { Subscription } from 'rxjs';

@Component({
  selector: 'app-call-panel',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="card" style="height: 100%;">
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px;">
        <div>
          <h2 style="margin: 0;">Call Controls</h2>
          <p style="margin: 4px 0 0 0; font-size: 13px; color: var(--text-muted);">Manage active interactions</p>
        </div>
        <span *ngIf="callStatus" class="status-badge" [ngClass]="getStatusClass()">
          {{ getDisplayStatus() }}
        </span>
      </div>

      <div *ngIf="!callId" style="flex: 1; display: flex; flex-direction: column; justify-content: center; align-items: center; padding: 40px 0; border: 2px dashed var(--border); border-radius: var(--radius-lg); margin-bottom: 24px; background: var(--neutral-soft);">
        <div style="width: 48px; height: 48px; background: white; border-radius: 50%; display: flex; align-items: center; justify-content: center; margin-bottom: 16px; box-shadow: var(--shadow-card);">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="var(--text-muted)" stroke-width="2"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"></path></svg>
        </div>
        <p style="text-align: center; color: var(--text-muted); font-size: 14px; font-weight: 500;">No active calls at the moment</p>
      </div>

      <div *ngIf="callId" class="card" style="background: var(--neutral-soft); border: none; margin-bottom: 24px; padding: 16px;">
        <div style="display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px;">
          <div>
            <span style="font-size: 11px; font-weight: 700; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.05em;">Active Call</span>
            <div style="font-family: monospace; font-size: 14px; font-weight: 700; color: var(--text-main); margin-top: 4px;">ID: {{ callId!.substring(0, 13) }}...</div>
          </div>
          <div *ngIf="callStatus === 'IN_PROGRESS'" style="display: flex; align-items: center; gap: 6px;">
             <div class="pulse" style="width: 8px; height: 8px; background: var(--danger); border-radius: 50%;"></div>
             <span style="font-size: 12px; font-weight: 700; color: var(--danger);">LIVE</span>
          </div>
        </div>
        
        <div style="display: flex; align-items: center; gap: 12px; padding: 12px; background: white; border-radius: var(--radius-md); border: 1px solid var(--border);">
          <div style="width: 32px; height: 32px; background: var(--primary-soft); border-radius: 50%; display: flex; align-items: center; justify-content: center; color: var(--primary);">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path></svg>
          </div>
          <div>
            <div style="font-size: 13px; font-weight: 600; color: var(--text-main);">Sales Queue</div>
            <div style="font-size: 11px; color: var(--text-muted);">Incoming from {{ api.tenantId }}</div>
          </div>
        </div>
      </div>

      <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-top: auto;">
        <button class="btn btn-outline" (click)="createCall()" [disabled]="!!callId" style="grid-column: span 2; justify-content: center;">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"></path></svg>
          Simulate Call
        </button>
        <button class="btn btn-primary" (click)="startCall()" [disabled]="callStatus !== 'ROUTED'" style="justify-content: center;">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"></path><polyline points="22 4 12 14.01 9 11.01"></polyline></svg>
          Start Call
        </button>
        <button class="btn btn-outline" style="color: var(--danger); border-color: var(--danger); justify-content: center;" (click)="completeCall()" [disabled]="callStatus !== 'IN_PROGRESS'">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect><line x1="9" y1="9" x2="15" y2="15"></line><line x1="15" y1="9" x2="9" y2="15"></line></svg>
          Complete Call
        </button>
      </div>

      <div *ngIf="error" style="margin-top: 16px; padding: 12px; background: var(--danger-soft); color: var(--danger); border-radius: var(--radius-md); font-size: 13px; font-weight: 600;">
        {{ error }}
      </div>
    </div>
  `,
  styles: [`
    @keyframes pulse {
      0% { transform: scale(1); opacity: 1; }
      50% { transform: scale(1.5); opacity: 0.5; }
      100% { transform: scale(1); opacity: 1; }
    }
    .pulse { animation: pulse 2s infinite ease-in-out; }
  `]
})
export class CallPanelComponent implements OnInit, OnDestroy {
  callId: string | null = null;
  callStatus: string | null = null;
  error: string | null = null;
  private sub?: Subscription;

  constructor(private api: ApiService, private session: SessionStateService) {}

  ngOnInit() {
    // Rehydrate from shared state on mount
    this.sub = this.session.call$.subscribe(state => {
      this.callId = state.callId;
      this.callStatus = state.status;
    });
  }

  ngOnDestroy() {
    this.sub?.unsubscribe();
    // Do NOT clear call state — SessionStateService owns it
  }

  getStatusClass() {
    switch (this.callStatus) {
      case 'QUEUED': return 'status-badge status-queued';
      case 'ROUTED': return 'status-badge status-progress';
      case 'ASSIGNED': return 'status-badge status-progress';
      case 'IN_PROGRESS': return 'status-badge status-progress';
      case 'CALL_COMPLETED': return 'status-badge status-available';
      default: return 'status-badge status-offline';
    }
  }

  getDisplayStatus() {
    switch (this.callStatus) {
      case 'QUEUED': return 'In Queue';
      case 'ROUTED': return 'Call Assigned';
      case 'ASSIGNED': return 'Call Assigned';
      case 'IN_PROGRESS': return 'Active Call';
      case 'CALL_COMPLETED': return 'Completed';
      default: return this.callStatus || 'Idle';
    }
  }

  createCall() {
    this.error = null;
    const tenantId = this.api.tenantId;
    if (!tenantId) {
      this.error = 'No tenant context found. Please re-login.';
      return;
    }
    this.api.createCall(tenantId, 'sales').subscribe({
      next: (res) => {
        this.session.setCall(res.callId, res.status);
      },
      error: (err) => this.error = 'Failed to create call'
    });
  }

  startCall() {
    if (!this.callId) return;
    this.api.updateCallStatus(this.callId, 'IN_PROGRESS').subscribe({
      next: (res) => {
        this.session.setCall(this.callId, res.status);
      },
      error: (err) => this.error = 'Failed to start call'
    });
  }

  completeCall() {
    if (!this.callId) return;
    this.api.updateCallStatus(this.callId, 'COMPLETED').subscribe({
      next: (res) => {
        this.session.setCall(this.callId, res.status);
      },
      error: (err) => this.error = 'Failed to complete call'
    });
  }
}
