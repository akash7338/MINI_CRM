import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Device, Call } from '@twilio/voice-sdk';
import { BehaviorSubject, Observable, tap } from 'rxjs';

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
  
  private activeCallSubject = new BehaviorSubject<Call | null>(null);
  activeCall$ = this.activeCallSubject.asObservable();

  private incomingCallSubject = new BehaviorSubject<Call | null>(null);
  incomingCall$ = this.incomingCallSubject.asObservable();

  constructor(private http: HttpClient) {}

  async initialize(agentId: string): Promise<void> {
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
      });

      call.on('cancel', () => {
        this.incomingCallSubject.next(null);
      });
    });

    await this.device.register();
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
