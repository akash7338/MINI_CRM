import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { WebsocketService } from '../services/websocket.service';
import { Subscription } from 'rxjs';

interface RecentCall {
  id: string;
  tenantId: string;
  skill: string;
  status: string;
  agentId?: string;
  startTime: Date;
}

@Component({
  selector: 'app-recent-calls',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="card" style="height: 100%;">
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px;">
        <div>
          <h2 style="margin: 0;">Call History</h2>
          <p style="margin: 4px 0 0 0; font-size: 13px; color: var(--text-muted);">Real-time queue monitoring</p>
        </div>
        <button class="btn btn-ghost">
          View All
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 18 15 12 9 6"></polyline></svg>
        </button>
      </div>

      <div style="overflow-x: auto;">
        <table>
          <thead>
            <tr>
              <th>Call Reference</th>
              <th>Queue</th>
              <th>Agent</th>
              <th>Status</th>
              <th>Started</th>
            </tr>
          </thead>
          <tbody>
            <tr *ngIf="calls.length === 0">
              <td colspan="5" style="text-align: center; padding: 60px 0;">
                <div style="color: var(--text-muted); font-weight: 500;">No live call data available.</div>
              </td>
            </tr>
            <tr *ngFor="let call of calls">
              <td>
                <div style="display: flex; align-items: center; gap: 12px;">
                  <div style="width: 32px; height: 32px; background: var(--neutral-soft); border-radius: 8px; display: flex; align-items: center; justify-content: center; color: var(--text-muted);">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"></path></svg>
                  </div>
                  <span style="font-family: monospace; font-weight: 600;">{{ call.id.substring(0, 10) }}</span>
                </div>
              </td>
              <td>
                <span style="font-weight: 500; color: var(--text-main);">{{ call.skill }}</span>
              </td>
              <td>
                <div *ngIf="call.agentId" style="display: flex; align-items: center; gap: 8px;">
                  <div style="width: 24px; height: 24px; background: var(--primary-soft); color: var(--primary); border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 10px; font-weight: 700;">
                    {{ call.agentId.substring(0, 1).toUpperCase() }}
                  </div>
                  <span>{{ call.agentId }}</span>
                </div>
                <span *ngIf="!call.agentId" style="color: var(--text-muted);">Unassigned</span>
              </td>
              <td>
                <span class="status-badge" [ngClass]="getStatusClass(call.status)">
                  {{ call.status }}
                </span>
              </td>
              <td>{{ call.startTime | date:'HH:mm:ss' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  `
})
export class RecentCallsComponent implements OnInit, OnDestroy {
  calls: RecentCall[] = [];
  private sub?: Subscription;

  constructor(private ws: WebsocketService) {}

  ngOnInit() {
    this.sub = this.ws.events$.subscribe(event => {
      this.handleEvent(event);
    });
  }

  ngOnDestroy() {
    this.sub?.unsubscribe();
  }

  private handleEvent(event: any) {
    const topic = event.topic;
    const payload = event.payload;
    if (!payload?.callId) return;

    if (topic === 'call-events' && payload.eventType === 'CALL_QUEUED') {
      const newCall: RecentCall = {
        id: payload.callId,
        tenantId: payload.tenantId,
        skill: payload.skills?.[0] || 'Sales',
        status: 'QUEUED',
        startTime: new Date()
      };
      this.calls.unshift(newCall);
    } else {
      const call = this.calls.find(c => c.id === payload.callId);
      if (call) {
        if (topic === 'routing-events' && payload.status === 'ASSIGNED') {
          call.status = 'ROUTED';
          call.agentId = payload.agentId;
        } else if (topic === 'call-lifecycle-events') {
          call.status = payload.eventType;
          if (payload.agentId) call.agentId = payload.agentId;
        }
      }
    }

    if (this.calls.length > 10) this.calls.pop();
  }

  getStatusClass(status: string) {
    switch (status) {
      case 'QUEUED': return 'status-queued';
      case 'ROUTED': return 'status-progress';
      case 'IN_PROGRESS': return 'status-progress';
      case 'CALL_COMPLETED': return 'status-available';
      default: return 'status-offline';
    }
  }
}

