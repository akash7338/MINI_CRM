import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { WebsocketService } from '../services/websocket.service';
import { Subscription } from 'rxjs';

@Component({
  selector: 'app-event-log',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="panel">
      <h2>Real-time Event Log</h2>
      <div class="event-log">
        <div *ngIf="events.length === 0" style="color: #999;">Waiting for events...</div>
        <div class="event-entry" *ngFor="let event of events">
          <span style="color: #0066cc;">[{{ event.receivedAt | date:'HH:mm:ss.SSS' }}]</span> 
          <strong>{{ event.topic }}:</strong> 
          {{ formatPayload(event.payload) }}
        </div>
      </div>
    </div>
  `
})
export class EventLogComponent implements OnInit, OnDestroy {
  events: any[] = [];
  private sub?: Subscription;

  constructor(private ws: WebsocketService) {}

  ngOnInit() {
    this.ws.connect();
    this.sub = this.ws.events$.subscribe(event => {
      this.events.unshift(event);
      if (this.events.length > 50) {
        this.events.pop(); // Keep only last 50 events
      }
    });
  }

  ngOnDestroy() {
    this.sub?.unsubscribe();
    this.ws.disconnect();
  }

  formatPayload(payload: any): string {
    if (!payload) return '';
    
    if (payload.eventType) {
       return `${payload.eventType} (${payload.agentId || payload.callId})`;
    }
    if (payload.status) {
       return `${payload.status} - Call: ${payload.callId} - Agent: ${payload.agentId || 'none'}`;
    }
    
    return JSON.stringify(payload);
  }
}
