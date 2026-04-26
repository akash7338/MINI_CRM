import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../services/api.service';

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
      <div *ngIf="callId">
        <p><strong>Call ID:</strong> {{ callId }}</p>
        <p><strong>Status:</strong> {{ status }}</p>
        <p *ngIf="agentId"><strong>Assigned Agent:</strong> {{ agentId }}</p>
      </div>
    </div>
  `
})
export class CallPanelComponent {
  callId: string | null = null;
  status: string | null = null;
  agentId: string | null = null;

  constructor(private api: ApiService) {}

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
