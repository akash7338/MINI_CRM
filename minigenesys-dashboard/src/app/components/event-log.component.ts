import { Component, OnInit, OnDestroy, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SessionStateService } from '../services/session-state.service';
import { Subscription } from 'rxjs';

@Component({
  selector: 'app-event-log',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div style="max-height: 400px; overflow-y: auto;">
      <div *ngIf="filteredEvents.length === 0" style="text-align: center; color: var(--text-muted); padding: 40px 0;">
        <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" style="margin-bottom: 12px; opacity: 0.3;"><path d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"></path></svg>
        <p>No activity recorded yet.</p>
      </div>

      <div *ngFor="let event of filteredEvents" class="feed-item">
        <div class="feed-icon" [style.background]="getIconBg(event.topic)" [style.color]="getIconColor(event.topic)">
          <svg *ngIf="event.topic === 'agent-events'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg>
          <svg *ngIf="event.topic === 'call-events'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"></path></svg>
          <svg *ngIf="event.topic === 'routing-events'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 12 20 22 4 22 4 12"></polyline><path d="M20 7L12 3L4 7L12 11L20 7Z"></path></svg>
          <svg *ngIf="event.topic === 'call-lifecycle-events'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline></svg>
        </div>
        <div style="flex: 1;">
          <div style="display: flex; justify-content: space-between; margin-bottom: 2px;">
            <span style="font-size: 13px; font-weight: 600; color: var(--text-main);">{{ formatTopic(event.topic) }}</span>
            <span style="font-size: 11px; font-weight: 500; color: var(--text-muted);">{{ event.receivedAt | date:'HH:mm:ss' }}</span>
          </div>
          <p style="margin: 0; font-size: 13px; color: var(--text-muted); line-height: 1.4;">{{ formatPayload(event.payload) }}</p>
        </div>
      </div>
    </div>
  `
})
export class EventLogComponent implements OnInit, OnDestroy {
  @Input() filterAgentId: string | null = null;
  events: any[] = [];
  private sub?: Subscription;

  constructor(private session: SessionStateService) {}

  get filteredEvents() {
    if (!this.filterAgentId) return this.events;
    return this.events.filter(e => e.payload?.agentId === this.filterAgentId);
  }

  ngOnInit() {
    this.sub = this.session.events$.subscribe(events => {
      this.events = events;
    });
  }

  ngOnDestroy() {
    this.sub?.unsubscribe();
  }

  getIconBg(topic: string): string {
    if (topic === 'agent-events') return 'var(--danger-soft)';
    if (topic === 'call-events') return 'var(--warning-soft)';
    if (topic === 'routing-events') return 'var(--primary-soft)';
    return 'var(--neutral-soft)';
  }

  getIconColor(topic: string): string {
    if (topic === 'agent-events') return 'var(--danger)';
    if (topic === 'call-events') return 'var(--warning)';
    if (topic === 'routing-events') return 'var(--primary)';
    return 'var(--neutral)';
  }

  formatTopic(topic: string): string {
    return topic.split('-').map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
  }

  formatPayload(payload: any): string {
    if (!payload) return '';
    if (payload.eventType) return `${payload.eventType} for ${payload.agentId || payload.callId}`;
    if (payload.status) return `Call ${payload.callId} status changed to ${payload.status}`;
    return JSON.stringify(payload);
  }
}


