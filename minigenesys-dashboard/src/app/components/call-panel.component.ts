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
    <div class="card" style="height: 100%; display: flex; flex-direction: column;">
      <div class="card-header">
        <div>
          <div class="card-title">Call Controls</div>
          <div class="card-subtitle">Manage active interactions</div>
        </div>
        <span *ngIf="callStatus" class="status-badge" [ngClass]="getStatusClass()">
          <span class="status-dot" [ngClass]="getStatusDotClass()"></span>
          {{ getDisplayStatus() }}
        </span>
      </div>

      <!-- Empty State -->
      <div *ngIf="!callId" class="empty-state" style="flex: 1; margin-bottom: 20px;">
        <div class="empty-state-icon">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"></path></svg>
        </div>
        <div class="empty-state-text">No active calls at the moment</div>
      </div>

      <!-- Active Call Card -->
      <div *ngIf="callId" class="card-inner" style="margin-bottom: 20px;">
        <div style="display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 14px;">
          <div>
            <div style="font-size: 11px; font-weight: 600; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.06em;">Active Call</div>
            <div class="font-mono" style="font-size: 14px; font-weight: 700; color: var(--text-main); margin-top: 4px;">ID: {{ callId!.substring(0, 13) }}...</div>
          </div>
          <div *ngIf="callStatus === 'IN_PROGRESS'" class="live-indicator">
            <div class="live-indicator-dot"></div>
            LIVE
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

      <!-- Action Buttons -->
      <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: auto;">
        <button class="btn btn-outline" (click)="createCall()" [disabled]="!!callId" style="grid-column: span 2; justify-content: center;">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"></path></svg>
          Simulate Call
        </button>
        <button class="btn btn-primary" (click)="startCall()" [disabled]="callStatus !== 'ROUTED'" style="justify-content: center;">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"></path><polyline points="22 4 12 14.01 9 11.01"></polyline></svg>
          Answer
        </button>
        <button class="btn btn-danger-outline" (click)="completeCall()" [disabled]="callStatus !== 'IN_PROGRESS'" style="justify-content: center;">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect><line x1="9" y1="9" x2="15" y2="15"></line><line x1="15" y1="9" x2="9" y2="15"></line></svg>
          End Call
        </button>
      </div>

      <div *ngIf="error" class="alert alert-error" style="margin-top: 16px;">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>
        {{ error }}
      </div>
    </div>
  `
})
export class CallPanelComponent implements OnInit, OnDestroy {
  callId: string | null = null;
  callStatus: string | null = null;
  error: string | null = null;
  private sub?: Subscription;

  constructor(public api: ApiService, private session: SessionStateService) {}

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
      case 'QUEUED': return 'status-queued';
      case 'ROUTED': return 'status-progress';
      case 'ASSIGNED': return 'status-progress';
      case 'IN_PROGRESS': return 'status-danger';
      case 'CALL_COMPLETED': return 'status-available';
      default: return 'status-offline';
    }
  }

  getStatusDotClass() {
    switch (this.callStatus) {
      case 'QUEUED': return 'busy';
      case 'ROUTED': return '';
      case 'IN_PROGRESS': return 'live';
      case 'CALL_COMPLETED': return 'available';
      default: return 'offline';
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
      next: (res: any) => {
        this.session.setCall(res.callId, res.status);
        this.error = null;
      },
      error: (err: any) => {
        this.error = `Failed to create test call: ${err.error?.message || err.message}`;
      }
    });
  }

  startCall() {
    if (!this.callId) return;
    this.api.updateCallStatus(this.callId, 'IN_PROGRESS').subscribe({
      next: (res: any) => {
        this.session.setCall(this.callId, res.status);
      },
      error: (err: any) => this.error = 'Failed to start call'
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
