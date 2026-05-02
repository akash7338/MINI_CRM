import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Device, Call } from '@twilio/voice-sdk';
import { BehaviorSubject, Observable, tap } from 'rxjs';
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
    private session: SessionStateService
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
      console.log('Incoming call from:', call.parameters['From']);
      this.incomingCallSubject.next(call);
      
      call.on('disconnect', () => {
        this.incomingCallSubject.next(null);
        this.activeCallSubject.next(null);
        
        // Ensure the backend knows the call ended if the caller hung up
        const currentCallId = this.session.call.callId;
        if (currentCallId) {
          this.apiService.updateCallStatus(currentCallId, 'COMPLETED').subscribe({
            next: (res) => this.session.setCall(currentCallId, res.status),
            error: (err) => console.error('Failed to notify backend of call completion', err)
          });
        }
      });

      call.on('cancel', () => {
        this.incomingCallSubject.next(null);
        
        // If caller hangs up before we answer, we need to clear the backend state
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
    call.accept();
    this.incomingCallSubject.next(null);
    this.activeCallSubject.next(call);
  }

  rejectCall(call: Call) {
    call.reject();
    this.incomingCallSubject.next(null);
  }

  hangup() {
    const call = this.activeCallSubject.value;
    if (call) {
      call.disconnect();
      this.activeCallSubject.next(null);
    }
  }
}
