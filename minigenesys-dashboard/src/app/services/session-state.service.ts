import { Injectable, OnDestroy } from '@angular/core';
import { BehaviorSubject, Subscription } from 'rxjs';
import { ApiService } from './api.service';
import { WebsocketService } from './websocket.service';

export interface AgentState {
  agentId: string;
  status: string;        // Offline | Ready | On Call
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
    agentId: '',
    status: 'Offline'
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
      console.log('[SessionState] WebSocket Event Received:', event);
      this.handleEvent(event);
      this.addToHistory(event);
    });

    // Sync agent ID from storage if available
    const storedAgentId = localStorage.getItem('agentId');
    if (storedAgentId) {
      this.patchAgent({ agentId: storedAgentId });
      this.loadInitialState(storedAgentId);
      // Ensure we are subscribed to the correct tenant events
      this.ws.subscribeToTenantEvents();
    }
  }

  private loadInitialState(agentId: string) {
    console.log('[SessionState] Loading initial state for:', agentId);
    this.api.getAgentState(agentId).subscribe({
      next: (res: any) => {
        console.log('[SessionState] Initial State Loaded:', res);
        const uiStatus = this.mapAgentStatus(res.status);
        this.setAgentStatus(uiStatus);
        
        // Ensure heartbeat resumes after a page refresh
        if (uiStatus !== 'Offline') {
          this.startHeartbeat();
        }
        
        if (res.activeCallId && res.tenantId) {
          // Fetch the real status from call-service instead of guessing
          this.api.getCall(res.activeCallId, res.tenantId).subscribe({
            next: (callRes: any) => {
              console.log('[SessionState] Restored Call Data:', callRes);
              this.setCall(callRes.callId, callRes.status);
            },
            error: (err: any) => {
              console.warn('[SessionState] Could not fetch active call details, falling back to basic state');
              const fallbackStatus = uiStatus === 'On Call' ? 'ROUTED' : 'QUEUED';
              this.setCall(res.activeCallId, fallbackStatus);
            }
          });
        }
      },
      error: (err: any) => console.error('[SessionState] Failed to load initial state', err)
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
    console.log('[SessionState] Setting Agent ID:', id);
    this.patchAgent({ agentId: id });
    this.api.setAgentId(id);
    localStorage.setItem('agentId', id);
  }

  setAgentStatus(status: string) {
    console.log('[SessionState] Setting Agent Status:', status);
    this.patchAgent({ status });
    if (status === 'Offline') {
      this.stopHeartbeat();
    }
  }

  startHeartbeat() {
    this.stopHeartbeat();
    
    const agentId = this.agent.agentId.trim();
    if (!agentId || this.agent.status === 'Offline') return;

    // Send first heartbeat immediately
    this.api.heartbeatAgent(agentId).subscribe({
      error: (err) => {
        if (err.status === 409) this.setAgentStatus('Offline');
      }
    });

    this.heartbeatInterval = setInterval(() => {
      if (!agentId || this.agent.status === 'Offline') {
        this.stopHeartbeat();
        return;
      }
      this.api.heartbeatAgent(agentId).subscribe({
        error: (err) => {
          if (err.status === 409) {
            this.setAgentStatus('Offline');
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
    console.log('[SessionState] Updating Call State:', { callId, status });
    this.callSubject.next({ callId, status });
  }

  clearCall() {
    console.log('[SessionState] Clearing Call State');
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
        const rawStatus = payload.eventType || payload.newStatus || '';
        const newStatus = this.mapAgentStatus(rawStatus);
        
        if (newStatus === 'Offline' && this.call.callId) {
          const callStatus = this.call.status;
          if (callStatus === 'ROUTED' || callStatus === 'IN_PROGRESS' || callStatus === 'QUEUED') {
            console.warn('[SessionState] Ignoring Offline event — active call exists:', this.call.callId);
            return;
          }
        }
        
        if (newStatus === 'On Call' && !this.call.callId) {
          console.warn('[SessionState] Ignoring On Call event — no active call in session');
          return;
        }

        this.setAgentStatus(newStatus);
      }
    } else if (topic === 'routing-events') {
      // 1. Check if the assignment is for THIS agent
      const isForMe = payload.agentId === currentAgentId;
      
      if (isForMe && (payload.status === 'ASSIGNED' || payload.status === 'ROUTED')) {
        console.log('[SessionState] Call Assigned to ME:', payload.callId);
        this.setAgentStatus('On Call');
        this.setCall(payload.callId, 'ROUTED');
      } 
      // 2. Also handle general status updates for the call we are currently tracking
      else if (payload.callId === this.call.callId && payload.status === 'ROUTED') {
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
        this.setAgentStatus('Ready');
      }
    }
  }

  // Map backend agent status values to our UI states
  public mapAgentStatus(backendStatus: string): string {
    if (!backendStatus) return 'Offline';
    const s = backendStatus.toUpperCase();
    if (s.includes('AVAILABLE')) return 'Ready';
    if (s.includes('BUSY')) return 'On Call';
    if (s.includes('LOGGED_IN')) return 'Offline';
    if (s.includes('DISCONNECTED') || s.includes('OFFLINE') || s.includes('LOGGED_OUT')) return 'Offline';
    return backendStatus; // pass-through for any we haven't mapped
  }


  // --- Private helpers ---
  private patchAgent(patch: Partial<AgentState>) {
    this.agentSubject.next({ ...this.agent, ...patch });
  }
}
