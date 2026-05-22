import { Injectable, NgZone } from '@angular/core';
import { BehaviorSubject } from 'rxjs';
import * as JsSIP from 'jssip';

@Injectable({
  providedIn: 'root'
})
export class FreeswitchWebRtcService {
  private ua: any = null;
  
  private incomingSessionSubject = new BehaviorSubject<any | null>(null);
  incomingSession$ = this.incomingSessionSubject.asObservable();

  private activeSessionSubject = new BehaviorSubject<any | null>(null);
  activeSession$ = this.activeSessionSubject.asObservable();

  private audio: HTMLAudioElement | null = null;

  constructor(private zone: NgZone) {}

  initialize(agentId: string) {
    if (this.ua) return;

    console.log('Initializing FreeSWITCH WebRTC for agent:', agentId);
    
    // Enable debug logging for JsSIP
    JsSIP.debug.enable('JsSIP:*');

    try {
      const socket = new JsSIP.WebSocketInterface('wss://localhost:7443');
      const config = {
        sockets: [socket],
        uri: `sip:${agentId}@localhost`,
        password: 'password123', // Blind registration ignores credentials
        register: true
      };

      this.ua = new JsSIP.UA(config);

      this.ua.on('connected', () => console.log('FreeSWITCH WebSocket Connected'));
      this.ua.on('disconnected', () => console.log('FreeSWITCH WebSocket Disconnected'));
      this.ua.on('registered', () => console.log('FreeSWITCH SIP UA Registered'));
      this.ua.on('unregistered', () => console.log('FreeSWITCH SIP UA Unregistered'));
      this.ua.on('registrationFailed', (e: any) => console.error('FreeSWITCH SIP UA Registration Failed:', e));

      this.ua.on('newRTCSession', (data: any) => {
        const session = data.session;

        // Only handle incoming sessions
        if (session.direction === 'incoming') {
          this.zone.run(() => {
            console.log('Incoming FreeSWITCH WebRTC Session:', session);
            this.incomingSessionSubject.next(session);
          });

          session.on('peerconnection', (pcData: any) => {
            pcData.peerconnection.addEventListener('track', (event: any) => {
              this.zone.run(() => {
                console.log('WebRTC remote track received:', event);
                const stream = event.streams[0];
                if (!this.audio) {
                  this.audio = new Audio();
                }
                this.audio.srcObject = stream;
                this.audio.play().catch(err => console.error('Failed to play WebRTC audio:', err));
              });
            });
          });

          session.on('accepted', () => {
            this.zone.run(() => {
              console.log('FreeSWITCH call accepted');
              this.incomingSessionSubject.next(null);
              this.activeSessionSubject.next(session);
            });
          });

          session.on('ended', () => {
            this.zone.run(() => {
              console.log('FreeSWITCH call ended');
              this.cleanupSession();
            });
          });

          session.on('failed', (e: any) => {
            this.zone.run(() => {
              console.error('FreeSWITCH call failed:', e);
              this.cleanupSession();
            });
          });
        }
      });

      this.ua.start();
    } catch (e) {
      console.error('Failed to initialize JsSIP client:', e);
    }
  }

  acceptCall(session: any) {
    const options = {
      mediaConstraints: { audio: true, video: false }
    };
    session.answer(options);
  }

  rejectCall(session: any) {
    session.terminate();
    this.cleanupSession();
  }

  hangup() {
    const active = this.activeSessionSubject.value;
    if (active) {
      active.terminate();
    }
    this.cleanupSession();
  }

  private cleanupSession() {
    this.incomingSessionSubject.next(null);
    this.activeSessionSubject.next(null);
    if (this.audio) {
      this.audio.pause();
      this.audio.srcObject = null;
      this.audio = null;
    }
  }
}
