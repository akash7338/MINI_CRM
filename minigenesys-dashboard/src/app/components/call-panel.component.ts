import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../services/api.service';
import { WebsocketService } from '../services/websocket.service';
import { Subscription } from 'rxjs';

@Component({
  selector: 'app-call-panel',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="panel">
      <h2>Call Panel</h2>
      <div>
        <button (click)="createCall()">Create Call (sales)</button>
        <button (click)="startCall()" [disabled]="!callId || status !== 'ROUTED'">Start Call</button>
        <button (click)="completeCall()" [disabled]="!callId || status !== 'IN_PROGRESS'">Complete Call</button>
      </div>
      <div *ngIf="callId" style="margin-top: 15px;">
        <p><strong>Call ID:</strong> {{ callId }}</p>
        <p>
          <strong>Status:</strong> 
          <span [ngClass]="{'status-queued': status === 'QUEUED', 'status-routed': status === 'ROUTED', 'status-progress': status === 'IN_PROGRESS', 'status-completed': status === 'COMPLETED'}">
            {{ status }}
          </span>
        </p>
        <p *ngIf="agentId"><strong>Assigned Agent:</strong> <span style="color: #0066cc;">{{ agentId }}</span></p>
      </div>
    </div>
  `,
  styles: [`
    .status-queued { color: orange; font-weight: bold; }
    .status-routed { color: #17a2b8; font-weight: bold; }
    .status-progress { color: #28a745; font-weight: bold; }
    .status-completed { color: gray; font-weight: bold; }
  `]
})
export class CallPanelComponent implements OnInit, OnDestroy {
  callId: string | null = null;
  status: string | null = null;
  agentId: string | null = null;
  private sub?: Subscription;

  constructor(private api: ApiService, private ws: WebsocketService) {}

  ngOnInit() {
    this.sub = this.ws.events$.subscribe(event => {
      const payload = event.payload;
      if (!payload) return;

      // Listen for routing assignments to update call status
      if (payload.status === 'ASSIGNED' && payload.callId === this.callId) {
        this.status = 'ROUTED';
        this.agentId = payload.agentId;
      }
    });
  }

  ngOnDestroy() {
    if (this.sub) {
      this.sub.unsubscribe();
    }
  }

  createCall() {
    this.api.createCall(['sales']).subscribe(res => {
      this.callId = res.id;
      this.status = res.status;
      this.agentId = res.assignedAgentId;
    });
  }

  startCall() {
    if (this.callId) {
      this.api.startCall(this.callId).subscribe(res => {
        this.status = res.status;
      });
    }
  }

  completeCall() {
    if (this.callId) {
      this.api.completeCall(this.callId).subscribe(res => {
        this.status = res.status;
      });
    }
  }
}

