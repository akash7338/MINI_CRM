import { Injectable, OnDestroy } from '@angular/core';
import { BehaviorSubject, Subscription } from 'rxjs';
import { ApiService } from './api.service';
import { WebsocketService } from './websocket.service';

export interface AgentState {
  agentId: string;
  status: string;        // OFFLINE | AVAILABLE | BUSY
}

export interface CallState {
  callId: string | null;
  status: string | null;  // QUEUED | ROUTED | IN_PROGRESS | CALL_COMPLETED
}

@Injectable({
  providedIn: 'root'
})
export class SessionStateService implements OnDestroy {
  // --- Agent State ---
  private agentSubject = new BehaviorSubject<AgentState>({
    agentId: 'agent-ui-1',
    status: 'OFFLINE'
  });
  agent$ = this.agentSubject.asObservable();

  // --- Call State ---
  private callSubject = new BehaviorSubject<CallState>({
    callId: null,
    status: null
  });
  call$ = this.callSubject.asObservable();

  // --- Internal ---
  private wsSub?: Subscription;
  private heartbeatInterval: any = null;
  private completionTimeout: any = null;

  // --- Event History ---
  private eventsSubject = new BehaviorSubject<any[]>([]);
  events$ = this.eventsSubject.asObservable();

  constructor(private api: ApiService, private ws: WebsocketService) {
    // Connect WebSocket ONCE from the singleton service.
    // This ensures the connection is never dropped during view switches.
    this.ws.connect();
    
    // Subscribe to WebSocket events ONCE at the service level.
    // This subscription lives for the entire app lifetime.
    this.wsSub = this.ws.events$.subscribe(event => {
      this.handleEvent(event);
      this.addToHistory(event);
    });
  }

  private addToHistory(event: any) {
    const currentEvents = this.eventsSubject.value;
    const newEvents = [{ ...event, receivedAt: new Date() }, ...currentEvents].slice(0, 50);
    this.eventsSubject.next(newEvents);
  }

  ngOnDestroy() {
    this.wsSub?.unsubscribe();
    this.stopHeartbeat();
    if (this.completionTimeout) clearTimeout(this.completionTimeout);
  }

  // --- Getters for current snapshot ---
  get agent(): AgentState { return this.agentSubject.value; }
  get call(): CallState { return this.callSubject.value; }

  // --- Agent Actions ---
  setAgentId(id: string) {
    this.patchAgent({ agentId: id });
    this.api.setAgentId(id);
  }

  setAgentStatus(status: string) {
    this.patchAgent({ status });
    if (status === 'OFFLINE') {
      this.stopHeartbeat();
    }
  }

  startHeartbeat() {
    this.stopHeartbeat();
    
    const agentId = this.agent.agentId.trim();
    if (!agentId || this.agent.status === 'OFFLINE') return;

    // Send first heartbeat immediately
    this.api.heartbeatAgent(agentId).subscribe({
      error: (err) => {
        if (err.status === 409) this.setAgentStatus('OFFLINE');
      }
    });

    this.heartbeatInterval = setInterval(() => {
      if (!agentId || this.agent.status === 'OFFLINE') {
        this.stopHeartbeat();
        return;
      }
      this.api.heartbeatAgent(agentId).subscribe({
        error: (err) => {
          if (err.status === 409) {
            this.setAgentStatus('OFFLINE');
          }
        }
      });
    }, 15000);
  }

  stopHeartbeat() {
    if (this.heartbeatInterval) {
      clearInterval(this.heartbeatInterval);
      this.heartbeatInterval = null;
    }
  }

  // --- Call Actions ---
  setCall(callId: string | null, status: string | null) {
    this.callSubject.next({ callId, status });
  }

  clearCall() {
    this.callSubject.next({ callId: null, status: null });
  }

  // --- WebSocket Event Handler ---
  private handleEvent(event: any) {
    const topic = event.topic;
    const payload = event.payload;
    if (!payload) return;

    const currentAgentId = this.agent.agentId;

    if (topic === 'agent-events') {
      if (payload.agentId === currentAgentId) {
        // Backend sends eventType like AGENT_BUSY, AGENT_AVAILABLE, AGENT_DISCONNECTED
        // and may also send newStatus with raw status values
        const rawStatus = payload.eventType || payload.newStatus || '';
        const newStatus = this.mapAgentStatus(rawStatus);
        
        // Guard: Do NOT go OFFLINE if we have an active call (ROUTED / IN_PROGRESS).
        // The backend may send AGENT_DISCONNECTED due to heartbeat race conditions
        // even while the agent has an active call assignment.
        if (newStatus === 'OFFLINE' && this.call.callId) {
          const callStatus = this.call.status;
          if (callStatus === 'ROUTED' || callStatus === 'IN_PROGRESS' || callStatus === 'QUEUED') {
            console.warn('[SessionState] Ignoring OFFLINE event — active call exists:', this.call.callId);
            return;
          }
        }
        
        this.setAgentStatus(newStatus);
      }
    } else if (topic === 'routing-events') {
      if (payload.status === 'ASSIGNED' && payload.agentId === currentAgentId) {
        this.setAgentStatus('BUSY');
        if (payload.callId) {
          this.setCall(payload.callId, 'ROUTED');
        }
      }
      // Also update call status if it matches
      if (payload.callId === this.call.callId && payload.status === 'ROUTED') {
        this.setCall(this.call.callId, 'ROUTED');
      }
    } else if (topic === 'call-lifecycle-events') {
      if (payload.callId === this.call.callId) {
        this.setCall(this.call.callId, payload.eventType);

        if (payload.eventType === 'CALL_COMPLETED') {
          // Clear call after a short delay so user sees the completed state
          if (this.completionTimeout) clearTimeout(this.completionTimeout);
          this.completionTimeout = setTimeout(() => {
            this.clearCall();
          }, 3000);
        }
      }
      // Update agent status on call completion
      if (payload.eventType === 'CALL_COMPLETED' && payload.agentId === currentAgentId) {
        this.setAgentStatus('AVAILABLE');
      }
    }
  }

  // Map backend agent status values to our UI states
  private mapAgentStatus(backendStatus: string): string {
    if (!backendStatus) return 'OFFLINE';
    const s = backendStatus.toUpperCase();
    if (s.includes('AVAILABLE')) return 'AVAILABLE';
    if (s.includes('BUSY')) return 'BUSY';
    if (s.includes('LOGGED_IN')) return 'LOGGED_IN';
    if (s.includes('DISCONNECTED') || s.includes('OFFLINE') || s.includes('LOGGED_OUT')) return 'OFFLINE';
    return backendStatus; // pass-through for any we haven't mapped
  }


  // --- Private helpers ---
  private patchAgent(patch: Partial<AgentState>) {
    this.agentSubject.next({ ...this.agent, ...patch });
  }
}
