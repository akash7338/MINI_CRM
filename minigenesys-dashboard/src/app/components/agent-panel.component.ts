import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../services/api.service';

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
      <div>
        <button (click)="login()" [disabled]="!agentId">Login</button>
        <button (click)="setAvailable()" [disabled]="!agentId">Available</button>
        <button (click)="heartbeat()" [disabled]="!agentId">Heartbeat</button>
        <button (click)="logout()" [disabled]="!agentId">Logout</button>
      </div>
      <div *ngIf="status">
        <strong>Current Status:</strong> {{ status }}
      </div>
    </div>
  `
})
export class AgentPanelComponent {
  agentId = 'agent-ui-1';
  status = 'OFFLINE';

  constructor(private api: ApiService) {}

  login() {
    this.api.loginAgent(this.agentId).subscribe(res => this.status = res.status);
  }

  setAvailable() {
    this.api.setAgentState(this.agentId, 'AVAILABLE').subscribe(res => this.status = res.status);
  }

  heartbeat() {
    this.api.heartbeatAgent(this.agentId).subscribe(() => console.log('Heartbeat sent'));
  }

  logout() {
    this.api.logoutAgent(this.agentId).subscribe(res => this.status = res.status);
  }
}
