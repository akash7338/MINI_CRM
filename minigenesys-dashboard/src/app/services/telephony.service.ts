import { Injectable, NgZone } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Device, Call } from '@twilio/voice-sdk';
import { BehaviorSubject, Observable } from 'rxjs';
import { ApiService } from './api.service';
import { SessionStateService } from './session-state.service';

export interface TelephonyToken {
  token: string;
  identity: string;
}

@Injectable({
  providedIn: 'root'
})
export class TelephonyService {
  private readonly GATEWAY_URL = 'http://localhost:8080';
  private device: Device | null = null;
  private initPromise: Promise<void> | null = null;
  
  private activeCallSubject = new BehaviorSubject<Call | null>(null);
  activeCall$ = this.activeCallSubject.asObservable();

  private incomingCallSubject = new BehaviorSubject<Call | null>(null);
  incomingCall$ = this.incomingCallSubject.asObservable();

  constructor(
    private http: HttpClient,
    private apiService: ApiService,
    private session: SessionStateService,
    private zone: NgZone
  ) {}

  async initialize(agentId: string): Promise<void> {
    if (this.device) return;
    if (this.initPromise) return this.initPromise;

    this.initPromise = this._initialize(agentId);
    return this.initPromise;
  }

  private async _initialize(agentId: string): Promise<void> {
    try {
      const res = await this.http.get<TelephonyToken>(`${this.GATEWAY_URL}/api/v1/telephony/twilio/token?agentId=${agentId}`).toPromise();
      if (!res) return;

      this.device = new Device(res.token, {
        logLevel: 'debug',
        edge: 'ashburn'
      });

      this.device.on('registered', () => console.log('Twilio Device Registered'));
      this.device.on('error', (error) => console.error('Twilio Device Error:', error));
      
      this.device.on('incoming', (call: Call) => {
        this.zone.run(() => {
          console.log('Incoming call from:', call.parameters['From']);
          this.incomingCallSubject.next(call);
        });
        
        call.on('disconnect', () => {
          this.zone.run(() => {
            this.incomingCallSubject.next(null);
            this.activeCallSubject.next(null);
          });
          
          // Ensure the backend knows the call ended
          const currentCallId = this.session.call.callId;
          if (currentCallId) {
            this.apiService.updateCallStatus(currentCallId, 'COMPLETED').subscribe({
              next: (res) => this.session.setCall(currentCallId, res.status),
              error: (err) => console.error('Failed to notify backend of call completion', err)
            });
          }
        });

        call.on('cancel', () => {
          this.zone.run(() => {
            this.incomingCallSubject.next(null);
          });
          
          const currentCallId = this.session.call.callId;
          if (currentCallId) {
            this.apiService.updateCallStatus(currentCallId, 'COMPLETED').subscribe({
              next: (res) => this.session.setCall(currentCallId, res.status),
              error: (err) => console.error('Failed to clear abandoned call', err)
            });
          }
        });
      });

      await this.device.register();
    } catch (error) {
      console.error('Telephony initialization failed:', error);
      this.initPromise = null;
      throw error;
    }
  }

  acceptCall(call: Call) {
    this.zone.run(() => {
      call.accept();
      this.incomingCallSubject.next(null);
      this.activeCallSubject.next(call);

      // Notify backend that the call has physically started
      const currentCallId = this.session.call.callId;
      if (currentCallId) {
        this.apiService.updateCallStatus(currentCallId, 'IN_PROGRESS').subscribe({
          next: (res) => this.session.setCall(currentCallId, res.status),
          error: (err) => console.error('Failed to start call on backend', err)
        });
      }
    });
  }

  rejectCall(call: Call) {
    this.zone.run(() => {
      call.reject();
      this.incomingCallSubject.next(null);
      
      const currentCallId = this.session.call.callId;
      const agentId = this.session.agent.agentId;

      if (!agentId) return;

      // 1. Immediately update UI to show Offline
      this.session.setAgentStatus('Offline');

      // 2. We MUST wait for the backend to confirm Offline status BEFORE rejecting the call.
      // This ensures the routing engine sees us as Offline before the call is requeued.
      this.apiService.updateAgentStatus(agentId, 'logout').subscribe({
        next: () => {
          console.log('Agent marked OFFLINE successfully. Proceeding with call rejection.');
          
          if (currentCallId) {
            this.apiService.updateCallStatus(currentCallId, 'REJECTED').subscribe({
              next: () => {
                console.log('Successfully rejected call on backend');
                this.session.setCall(null, null);
              },
              error: (err) => console.error('Failed to notify backend of call rejection', err)
            });
          }
        },
        error: (err) => {
          console.error('Failed to force logout on rejection. Rejecting call anyway.', err);
          // Fallback: Reject anyway even if logout failed
          if (currentCallId) {
            this.apiService.updateCallStatus(currentCallId, 'REJECTED').subscribe(() => {
              this.session.setCall(null, null);
            });
          }
        }
      });
    });
  }

  hangup() {
    this.zone.run(() => {
      const call = this.activeCallSubject.value;
      if (call) {
        call.disconnect();
        this.activeCallSubject.next(null);
      }
    });
  }
}
