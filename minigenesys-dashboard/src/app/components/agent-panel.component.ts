import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../services/api.service';
import { WebsocketService } from '../services/websocket.service';
import { Subscription } from 'rxjs';

@Component({
  selector: 'app-agent-panel',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="panel">
      <h2>Agent Panel</h2>
      <div>
        <input [(ngModel)]="agentId" placeholder="Enter Agent ID" />
      </div>
      <div style="margin-top: 10px;">
        <button (click)="login()" [disabled]="!agentId">Login</button>
        <button (click)="setAvailable()" [disabled]="!agentId">Available</button>
        <button (click)="heartbeat()" [disabled]="!agentId">Heartbeat</button>
        <button (click)="logout()" [disabled]="!agentId">Logout</button>
      </div>
      <div *ngIf="status" style="margin-top: 10px;">
        <strong>Current Status:</strong> 
        <span [ngClass]="{'status-available': status === 'AVAILABLE', 'status-busy': status === 'BUSY', 'status-offline': status === 'OFFLINE'}">
          {{ status }}
        </span>
      </div>
      <div *ngIf="errorMessage" style="color: red; margin-top: 10px; font-size: 0.9em;">
        {{ errorMessage }}
      </div>
    </div>
  `,
  styles: [`
    .status-available { color: green; font-weight: bold; }
    .status-busy { color: red; font-weight: bold; }
    .status-offline { color: gray; font-weight: bold; }
  `]
})
export class AgentPanelComponent implements OnInit, OnDestroy {
  agentId = 'agent-ui-1';
  status = 'OFFLINE';
  errorMessage = '';
  private sub?: Subscription;

  private heartbeatInterval?: any;

  constructor(private api: ApiService, private ws: WebsocketService) {}

  ngOnInit() {
    this.sub = this.ws.events$.subscribe(event => {
      const payload = event.payload;
      if (!payload) return;

      // 2. agent-events
      if (payload.eventType?.startsWith('AGENT_') && payload.agentId === this.agentId) {
        if (payload.newStatus) {
          this.status = payload.newStatus;
        } else if (payload.eventType === 'AGENT_BUSY') {
          this.status = 'BUSY';
        } else if (payload.eventType === 'AGENT_AVAILABLE') {
          this.status = 'AVAILABLE';
        } else if (payload.eventType === 'AGENT_DISCONNECTED') {
          this.status = 'OFFLINE';
          this.stopHeartbeat();
        }
      }
      
      // 3. routing-events ASSIGNED
      if (payload.status === 'ASSIGNED' && payload.agentId === this.agentId) {
        this.status = 'BUSY';
      }

      // 4. call-lifecycle-events CALL_COMPLETED
      if (payload.eventType === 'CALL_COMPLETED' && payload.agentId === this.agentId) {
        this.status = 'AVAILABLE';
      }
    });
  }

  ngOnDestroy() {
    if (this.sub) {
      this.sub.unsubscribe();
    }
    this.stopHeartbeat();
  }

  private startHeartbeat() {
    this.stopHeartbeat();
    // Send heartbeat immediately to populate lastHeartbeatAt on backend
    this.heartbeat();
    // Then every 15 seconds
    this.heartbeatInterval = setInterval(() => {
      if (this.status !== 'OFFLINE') {
        this.heartbeat();
      } else {
        this.stopHeartbeat();
      }
    }, 15000);
  }

  private stopHeartbeat() {
    if (this.heartbeatInterval) {
      clearInterval(this.heartbeatInterval);
      this.heartbeatInterval = undefined;
    }
  }

  login() {
    this.errorMessage = '';
    const cleanId = this.agentId.trim();
    this.api.loginAgent(cleanId).subscribe({
      next: (res) => {
        this.status = res.status;
        this.startHeartbeat();
      },
      error: (err) => {
        if (err.status === 409) {
          // Already logged in, just sync state
          this.syncState(cleanId);
        } else {
          this.errorMessage = `Login failed (Status: ${err.status}). Agent may not exist.`;
          console.error(err);
        }
      }
    });
  }

  setAvailable() {
    this.errorMessage = '';
    const cleanId = this.agentId.trim();
    this.api.setAgentState(cleanId, 'AVAILABLE').subscribe({
      next: (res) => {
        this.status = res.status;
        this.startHeartbeat();
      },
      error: (err) => {
        if (err.status === 409) {
          // Already available or busy, just sync state
          this.syncState(cleanId);
        } else {
          this.errorMessage = `Failed to set status (Status: ${err.status}).`;
          console.error(err);
        }
      }
    });
  }

  private syncState(agentId: string) {
    this.api.getAgentState(agentId).subscribe({
      next: (res) => {
        this.status = res.status;
        this.startHeartbeat(); // Keep heartbeat alive since they are active
        this.errorMessage = ''; // Clear error if sync succeeds
      },
      error: (err) => {
        this.errorMessage = `Failed to sync agent state.`;
        console.error(err);
      }
    });
  }

  heartbeat() {
    this.errorMessage = '';
    const cleanId = this.agentId.trim();
    this.api.heartbeatAgent(cleanId).subscribe({
      next: () => console.log('Heartbeat sent automatically'),
      error: (err) => {
        this.errorMessage = 'Heartbeat failed.';
        console.error(err);
      }
    });
  }

  logout() {
    this.errorMessage = '';
    const cleanId = this.agentId.trim();
    this.api.logoutAgent(cleanId).subscribe({
      next: (res) => {
        this.status = res.status;
        this.stopHeartbeat();
      },
      error: (err) => {
        this.errorMessage = `Logout failed (Status: ${err.status}).`;
        console.error(err);
      }
    });
  }
}

